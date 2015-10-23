package net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashMap;

import com.google.protobuf.GeneratedMessage;

import net.protobufs.SteammessagesRemoteclientDiscovery;
import net.protobufs.SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus;
import net.protobufs.SteammessagesRemoteclientDiscovery.ERemoteClientService;

public class DiscoveryProtocolHandler implements Runnable{
	private final long timeout = 10000;	//The discovery packet timeout in milliseconds
	public static final int DISCOVERY_PORT = 27036;	//The port used for the discovery protocol
	public static final byte[] PACKET_PREHEADER = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x21, 0x4c, 0x5f, (byte) 0xa0};	//Packets start with this
	private final long clientID;
	private final long steamID;	//This is your Steam ID 64 <the 64 part is really important
	private final int authID;
	private final String localIP;	//IP address of local machine

	private DatagramSocket discoverySocket;	//The socket for discovering Steam streaming servers
	/*
	 * Keeps a list of all the Steam streaming servers
	 * To keep the list from quickly growing in size, each Status object needs to identified by a key specific to the server.
	 * Since the address/port needs to be recorded (remember, the hostname is optional in this protobuf), this value was chosen for the key.
	 */
	private HashMap<InetAddress, CMsgRemoteClientBroadcastStatus> serverTable;
	
	public DiscoveryProtocolHandler(long steamID, int authKey, String localIP){
		this(steamID, authKey, 12345678L, localIP);
	}
	
	public DiscoveryProtocolHandler(long steamID, int authKey, long clientID, String localIP){
		this.steamID = steamID;
		this.authID = authKey;
		this.clientID = (clientID == 0 ? 12345678L : clientID);
		this.localIP = localIP;
		serverTable = new HashMap<InetAddress, CMsgRemoteClientBroadcastStatus>();
	}
	
	@Override
	public void run() {
		/*
		 * Initialized the discovery variable, exits with error if an exception is thrown
		 */
		try {
			discoverySocket = new DatagramSocket(DISCOVERY_PORT);
		} catch (SocketException e) {
			System.err.println("Error thrown when createing a new UDP socket on port " + DISCOVERY_PORT +
					". Check to see if Steam is running (you can't run the client and Steam at the same time, " +
					"they use the same port because the client has to mock Steam");
			e.printStackTrace();
			System.exit(1);
		}
		DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);	//Creates a new object to hold a buffer of a received discovery packet
		long lastTime = System.currentTimeMillis();	//Time when the last discovery packet was sent
		
		//One packet has to be sent initially to get the servers started
		//TODO listen on multicast instead of doing this
		try {
			sendDiscoveryPacket();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		while(true) {
			try {
				if(System.currentTimeMillis() - lastTime > timeout){	//This is done to prevent flooding the netwok
					sendDiscoveryPacket();	//Constantly send a discovery packet to make sure other machines send them back
					System.out.println("Discovery packet sent");
					lastTime = System.currentTimeMillis();	//Update the last packet sent time
				}
			} catch (IOException e1) {
				System.err.println("Error thrown when sending discovery packet.");
				e1.printStackTrace();
				System.exit(1);
			}
			try {
				discoverySocket.receive(packet);
			} catch (IOException e) {
				System.err.println("Error thrown when receiving packet");
				e.printStackTrace();
				System.exit(1);
			}
			try {
				handlePacket(packet);
			} catch (IOException e) {
				System.err.println("Error thrown when handling a packet");
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
	
	private void handlePacket(DatagramPacket packet) throws IOException   {
		DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packet.getData()));	//Turn the packet into a DataInputStream
		
		byte[] preheaderBytes = new byte[PACKET_PREHEADER.length];
		dis.read(preheaderBytes);	//Reads off the packet preheader into the preheaderByte array. The preheader is at the beginning of all discovery packets.
		
		if (!Arrays.equals(PACKET_PREHEADER, preheaderBytes)) {	//If the read bytes aren't equal the the preheader, report an error and return null
			System.err.println("Captured packet does not contain the correct preheader bytes!");
			return;
		}
		
		int headerLength = Integer.reverseBytes(dis.readInt());	//The first byte of the header is the length of the packet's header in little endian format

		if(headerLength < 0){	//Does a sanity check to see if the length is less that 0, if it is
			return;	//Exit the function
		}
		
		byte[] headerBytes = new byte[headerLength];
		dis.read(headerBytes);	//Read the packet's header into an array
		
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader header = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader.parseFrom(headerBytes);	//Take the read header bytes and turn it into an object
		//System.out.println(packet.getAddress().toString() + ": " + header.getMsgType());	//Print the packet type and the sender's IP
		
		int bodyLength = Integer.reverseBytes(dis.readInt());	//Like the header, the first byte of the body is it's length in little endian format
		byte[] bodyBytes = new byte[bodyLength];
		dis.read(bodyBytes);	//Read the body into the bodyBytes array
		
		GeneratedMessage body = null;	//This is the body protobuf object, set in the switch below.
		
		
		/*
		 * The header (optionally, see the steammessages_remoteclient_discovery.proto file) contains three pieces of data:
		 * 	1. The client's ID
		 * 	2. An enum that conveys what type of protobuf the body is
		 * 	3. An "instance ID", that increments up every time the packet is sent. Not really needed for anything.
		 * 
		 * 	Here we call the getMsgType method, which returns an enum specific to the body's protobuf type. We then compare it to each
		 * 	of the know body type enums and create a new object of the enum's type from the body's byte array (bodyBytes). From there
		 * 	sometimes we do a little more handling.
		 */
		switch(header.getMsgType()){
			case k_ERemoteClientBroadcastMsgDiscovery:	//This is the first packet sent, to the multicast address
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastDiscovery.parseFrom(bodyBytes);
				if(!(packet.getSocketAddress().equals(new InetSocketAddress(localIP, DISCOVERY_PORT)))){	//If the packet didn't come from this client, then
					sendDiscoveryPacket();	//Send a discovery packet,
					sendStatusPacket(packet.getAddress());	//And a status packet to the sender
				}
				break;
			case k_ERemoteClientBroadcastMsgStatus:	//This is sent when a discovery packet is received
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus.parseFrom(bodyBytes);
				if(!packet.getAddress().equals(new InetSocketAddress(localIP, 27036).getAddress())){	//If the packet didn't come from this client, then
					serverTable.put(packet.getAddress(), (CMsgRemoteClientBroadcastStatus) body);	//Puts the server entry into the list
				}
				break;
			case k_ERemoteClientBroadcastMsgOffline:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastDiscovery.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceAuthorizationRequest:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceAuthorizationRequest.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceAuthorizationResponse:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastDiscovery.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceStreamingRequest:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceStreamingRequest.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceStreamingResponse:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceStreamingResponse.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceProofRequest:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceProofRequest.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceProofResponse:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceProofResponse.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceAuthorizationCancelRequest:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceAuthorizationCancelRequest.parseFrom(bodyBytes);
				break;
			case k_ERemoteDeviceStreamingCancelRequest:
				body = SteammessagesRemoteclientDiscovery.CMsgRemoteDeviceStreamingCancelRequest.parseFrom(bodyBytes);
				break;
		}		
	}
	
	private void sendDiscoveryPacket() throws IOException {
		//Create a new set of objects for constructing packet
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(bos);
		
		//Constructing the packet using the standard discovery packet format
		
		//*****Preheader*****
		dos.write(PACKET_PREHEADER);	//We start with the the packet preheader
		//*****Preheader end******
		
		//*****Header*****
		
		/*Then we build the header object, setting
		 * The client ID (this can be pretty much any number I think, don't remember the bounds. TODO Make this random so clients wont conflict
		 * The body type, set to a Discovery packet. We don't strictly have to set this, because it defaults to this value, but we should incase the default gets changed.
		 * Then we build the object.
		 */		
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader header = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader.newBuilder().
			setClientId(clientID).
			setMsgType(SteammessagesRemoteclientDiscovery.ERemoteClientBroadcastMsg.k_ERemoteClientBroadcastMsgDiscovery).
			build();
		
		byte[] headerBytes = header.toByteArray();	//Turn the header object into a byte array
		dos.writeInt(Integer.reverseBytes(headerBytes.length));	//Write the header's length in reverse endian formt to the packet
		dos.write(headerBytes);	//Write the header to the packet
		
		//*****Header end*****
		
		//*****Body*****
		
		/*
		 * Here we build the body protobuf object (discovery). This type has two values:
		 * 	1. An optional sequence number (how many times this packet has been resent). There really isn't any reason to set this.
		 * 	2. An (unsigned) long containing a client ID. This can be repeated 0 or more times.
		 * 	Lastly, we build the object.
		 */
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastDiscovery message = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastDiscovery.newBuilder().
			build();
		
		byte[] messageBytes = message.toByteArray();	//Turn the body into a byte array
		
		dos.writeInt(Integer.reverseBytes(messageBytes.length));	//Write the body's length in little endian format
		
		dos.write(messageBytes);	//Write the body's data to the packet
		
		//*****Body end*****
		
		byte[] buf = bos.toByteArray();	//Turn the ByteOutputStream object into a byte array

		DatagramPacket packet = new DatagramPacket(buf, buf.length, new InetSocketAddress("255.255.255.255", DISCOVERY_PORT));	//Create a new UDP packet from the data, set it to transmit to the multicast address on port 27036
		
		discoverySocket.send(packet);	//Send the packet through the socket's object.
	}
	
	private void sendStatusPacket(InetAddress address) throws IOException {
		//Create a new set of objects for constructing packet
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(bos);
		
		dos.write(PACKET_PREHEADER);	//Constructing the packet using the standard discovery packet format
		
		//*****Preheader*****
		dos.write(PACKET_PREHEADER);	//We start with the the packet preheader
		//*****Preheader end******
		
		//*****Header*****
		
		/*Then we build the header object, setting
		 * The client ID (this can be pretty much any number I think, don't remember the bounds. TODO Make this random so clients wont conflict
		 * The body type, set to a Status packet.
		 * Then we build the object.
		 */	
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader header = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastHeader.newBuilder().
			setClientId(clientID).
			setMsgType(SteammessagesRemoteclientDiscovery.ERemoteClientBroadcastMsg.k_ERemoteClientBroadcastMsgStatus).
			build();
		
		byte[] headerBytes = header.toByteArray();	//Turn the header object into a byte array
		dos.writeInt(Integer.reverseBytes(headerBytes.length));	//Write the header's length in reverse endian formt to the packet
		dos.write(headerBytes);	//Write the header to the packet
		
		//*****Header end*****
		
		//*****Body*****
		
		/*
		 * Here we build the User protobuf. This object conveys information about the user when put into a status packet. It contains two fields:
		 * 	1. The user's Steam ID
		 * 	2. The user's streaming authorization key. As far as I know streaming is the only place this key is used, however, since this is broadcasted 
		 * 		to everybody who sends a discovery packet, it may be possible to impersonate another user by captureing this packet and setting your (client's)
		 * 		ID and AUTH key to another person's values.
		 */
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus.User user = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus.User.newBuilder().
				setSteamid(steamID).
				setAuthKeyId(authID).
				build();
		
		/*
		 * This is the message's body protobuf, of Status type. It contains a ton of values, I don't know what all of them do.
		 * 1. An array of User protobuf objects. See above. Whats really interesting about this is that it allows for multiple user objects,
		 * 	implying that maybe one computer can be used with multiple accounts with shared libraries.
		 * 2. The (max) version of the streaming protocol we will be using. Not sure what it currently is, I will test an update this later.
		 * 3. The minimum version of the streaming protocol this client supports.
		 * 4. The TCP port to use for the control protocol
		 * 5. The client's hostname. I think this is what shows up if you open Steam and go to Steam (menu)>Settings>In-home streaming and look at the table.
		 * 6. What services are available. There are 3 options: none, remote control, and game streaming
		 * 7. The OS type, defaults to 0. Not sure what other values do. TODO Look for a reference to this in other proto files
		 * 8. The true if the OS is 64 bit, false otherwise
		 * 9. This has serveral possible values: Invalid (0), Public (1), Beta (2), Internal (3), Dev (4), Max (5)
		 * 10. Timestamp in Epoch time
		 * 11. T/F if the screen is locked
		 * 12. T/F if a game is running
		 * 13. A list of mac addresses
		 */
		SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus message = SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus.newBuilder().
			addUsers(user).
			setVersion(10).
			setMinVersion(6).
			setConnectPort(DISCOVERY_PORT).
			setHostname(InetAddress.getLocalHost().getHostName()).
			setEnabledServices(ERemoteClientService.k_ERemoteClientServiceGameStreaming_VALUE).
			setOstype(0).
			setIs64Bit(true).
			setEuniverse(1).
			setTimestamp((int) (System.currentTimeMillis() / 1000)).
			//The following is new since version 6 (I think, can't find my old files on this)
			setScreenLocked(false).
			setGamesRunning(false).
			//addMacAddresses("").	//Not sure how this will effect (affect?) streaming, so commenting it out.
			build();
				
		byte[] messageBytes = message.toByteArray();	//Turn the body into a byte array
		
		dos.writeInt(Integer.reverseBytes(messageBytes.length));	//Write the body's length in little endian format
		
		dos.write(messageBytes);	//Write the body's data to the packet
		
		//*****Body end*****
		
		byte[] buf = bos.toByteArray();	//Turn the ByteOutputStream object into a byte array
		
		DatagramPacket packet = new DatagramPacket(buf, buf.length, new InetSocketAddress(address, 27036));	//Creates the packet, marks it to be sent to the discovery packet sender
		
		discoverySocket.send(packet);	//Send the packet through the socket's object.	
	}
	
	public HashMap<InetAddress, CMsgRemoteClientBroadcastStatus> getServerTable(){
		return serverTable;
	}

}
