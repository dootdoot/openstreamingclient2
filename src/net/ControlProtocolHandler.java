package net;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.HashMap;

import javax.xml.bind.DatatypeConverter;

import org.bouncycastle.crypto.tls.AlertLevel;
import org.bouncycastle.crypto.tls.CipherSuite;
import org.bouncycastle.crypto.tls.PSKTlsClient;
import org.bouncycastle.crypto.tls.ServerOnlyTlsAuthentication;
import org.bouncycastle.crypto.tls.TlsAuthentication;
import org.bouncycastle.crypto.tls.TlsClientProtocol;
import org.bouncycastle.crypto.tls.TlsPSKIdentity;

import com.google.protobuf.GeneratedMessage;

import net.protobufs.SteammessagesRemoteclient.*;
import net.protobufs.SteammessagesRemoteclientDiscovery.CMsgRemoteClientBroadcastStatus;

public class ControlProtocolHandler implements Runnable{
	private final String authData;	//Get this by running the AuthKeyFinder on a computer while logged into Steam
	private final String CLIENT_NAME = "3rd party client";
	private static final byte[] magicBytes = "VT01".getBytes(Charset.forName("UTF-8"));
	
	private InetAddress serverIP;	//The streaming server's IP address
	private CMsgRemoteClientBroadcastStatus serverStatus;	//This contains additional information for making the connection. The object can be created byt the DiscoveryProtocolHandler class.
	private boolean connectionRunning = false;	//This is used to safely indicate when the connection is ready to stop
	private DataOutputStream output = null;
	
	/*
	 * This contains a list of all the currently running streams that have been launched via this class.
	 */
	private HashMap<InetAddress, CMsgRemoteClientStartStreamResponse> streamTable;
	
	public ControlProtocolHandler(InetAddress serverIP, CMsgRemoteClientBroadcastStatus serverStatus, String authData){
		this.serverIP = serverIP;
		this.serverStatus = serverStatus;
		this.authData = authData;
		this.streamTable = new HashMap<InetAddress, CMsgRemoteClientStartStreamResponse>();
	}

	@Override
	public void run() {
		//Create a TCP connection to the server on the specified port, or default to 27036
		Socket socket = null;
		
		short portNumber = 27036;
		
		if(serverStatus.getConnectPort() != 0){
			portNumber = (short) serverStatus.getConnectPort();
		}
		
		try{
			socket = new Socket(serverIP, portNumber);
		} catch(IOException e){
			System.err.println("Exception thrown when creating TCP connction to port " + portNumber);
			e.printStackTrace();
		}
		
		//Sets up a TLS session over the newly created TCP connection
		TlsClientProtocol protocol = null;
		try {
			protocol = new TlsClientProtocol(socket.getInputStream(), socket.getOutputStream(), new SecureRandom());
		} catch (IOException e) {
			System.err.println("Exception thrown when setting up the TLS connection");
			e.printStackTrace();
		}
		
		//Attempts to connect the new TLS session
		//TODO Replace this with a try with resources
		try {
			protocol.connect(new StreamingPSKTlsClient(this.authData));
		} catch (IOException e) {
			System.err.println("Exception throw when connecting via TLS");
			e.printStackTrace();
		}
		
		output = new DataOutputStream(protocol.getOutputStream());	//This is the output stream for the connection. Write data to this.
		DataInputStream input = new DataInputStream(protocol.getInputStream());	//This is the input stream for the connection. Read data from this.
	
		connectionRunning = true;	//Mark the connection as active
		
		//Continuously handle incoming and outgoing packets. Everything received from here should be a protobuf packet, with some header info.
		while(connectionRunning){
			int length = 0;
			int emsg = 0;
			try{
				length = Integer.reverseBytes(input.readInt());	//Similarly to the Discovery protocol, the first byte is the length of the header
				@SuppressWarnings("unused")
				int magicBytes = input.readInt();	//This is Valve's "magic" number, although it doesn't do anything. Always VT01. (Might be in little endian, can't remember. TODO Test this
				emsg = input.readInt();	//This variable corresponds to the body's (protobuf) type. 
				@SuppressWarnings("unused")
				int blank = Integer.reverseBytes(input.readInt());	//This one is always 0.
			} catch(IOException e){
				System.err.println("Exception thrown when reading a packet in the Control stream.");
				e.printStackTrace();
				System.exit(1);
			}
			
			byte[] messageBytes = new byte[length - 8];	//The body of the packet, raw protobuf data. Don't remember for sure why -8, but I'm guessing if each of the above 4 values are 2 bytes each, that's where the number comes from.
			
			try{
				input.read(messageBytes);
			} catch(IOException e){
				System.err.println("Exception thrown when reading a packet in the Control stream.");
				e.printStackTrace();
				System.exit(1);
			}
			
			//Similarly to the DiscoveryProtocolHandler, here we use a switch statement to create a protobuf object from the read data.
			GeneratedMessage message = null;
			
			try{	//I'm lazy and don't want to write a try-catch statement for every possible exception thrower
				switch(emsg & 0x7fffffff){	//Don't remember why 0x7fffffff, but it has something to do with getting the emsg values to match up with a protobuf enum I think.
					case 9500:	//A request for authentication from the server
						message = CMsgRemoteClientAuth.parseFrom(messageBytes);
						
						//The rest of this block is setting up and sending an authentication request to the server.
						CMsgRemoteClientAuth messageCasted = (CMsgRemoteClientAuth) message;	//I'm just tired of typing ((CmsgRemoteClientAuth) message)
						CMsgRemoteClientBroadcastStatus messageStatus = messageCasted.getStatus();	//The embodied CMsgRemoteClientBroadcastStatus protobuf sent with the original authentication method.
						
						CMsgRemoteClientAuth authMessage = CMsgRemoteClientAuth.newBuilder(messageCasted).	//Create a new response. Pretty much the same as the old one, except the status object is rebuilt
								setStatus(CMsgRemoteClientBroadcastStatus.newBuilder(messageStatus).	//With the client's hostname.
										setHostname(CLIENT_NAME).
										build()).
								build();
						
						byte[] authMessageBytes = authMessage.toByteArray();	//Turn the protobuf into a byte array in preparation for being transmitted
						
						int authLength = authMessageBytes.length + 8;	//Set the length for the packet about to be sent
						output.writeInt(Integer.reverseBytes(authLength));	//Send the length of the coming packet
						
						output.write(magicBytes);	//Send the magic bytes. Might need to be little endian, can't remember. TODO Test this
						
						int authEmsg = Integer.reverseBytes(9500 | 0x80000000);	//Create the emsg. Don't remember why 0x80000000, but theres a good reason why.
						output.writeInt(authEmsg);	//Write the emsg to the stream
						
						output.writeInt(0);	//Write a 0 integer to the stream
						
						output.write(authMessageBytes);	//And finally write the protobuf out.
						
						break;
					case 9501:	//A response from an authentication request sent to the server
						message = CMsgRemoteClientAuthResponse.parseFrom(messageBytes);
						
						//TODO Check the received Eresult against a table of know values
						CMsgRemoteClientAuthResponse authResponseMessage = CMsgRemoteClientAuthResponse.newBuilder().
								setEresult(1).	//Lots of possible values for this, 1 means success. Here's a list of values: https://github.com/SteamRE/SteamKit/blob/master/Resources/SteamLanguage/eresult.steamd
								build();
						
						byte[] authResponseMessageBytes = authResponseMessage.toByteArray();
						
						int authResponseLength = authResponseMessageBytes.length + 8;	//Set the length for the packet about to be sent
						output.writeInt(Integer.reverseBytes(authResponseLength));	//Send the length of the coming packet
						
						output.write(magicBytes);	//Send the magic bytes. Might need to be little endian, can't remember. TODO Test this
						
						int authResponseEmsg = Integer.reverseBytes(9501 | 0x80000000);	//Create the emsg. 9501 is the code for an authentication response packet.
						output.writeInt(authResponseEmsg);	//Write the emsg to the stream
						
						output.writeInt(0);	//Write a 0 integer to the stream
						
						output.write(authResponseMessageBytes);	//Sends the body out.
						
						break;
					case 9502:	//Various information about a game, includes things like download time left, categories, and it's ID.
						message = CMsgRemoteClientAppStatus.parseFrom(messageBytes);
						
						break;
					case 9503:	//A request from the server to for the client to start a stream. We ignore these.
						message = CMsgRemoteClientStartStream.parseFrom(messageBytes);
						
						break;
					case 9504:	//A response from the server regarding or stream request.
						message = CMsgRemoteClientStartStreamResponse.parseFrom(messageBytes);
						
						if(((CMsgRemoteClientStartStreamResponse) message).getELaunchResult() == 1){	//If the launch was successful,
							streamTable.put(this.getServer(), (CMsgRemoteClientStartStreamResponse) message);	//Add the server InetAddress and StartStreamResponse protobuf the table
						} else{
							System.err.println("Stream from the server at " + this.getServer().getHostAddress() + " did not launch properly!");
						}
						
						break;
					case 9505:	//A ping message from the server. This is how the server know we're still there. I think. Why can't it just check to see if the connection's been closed instead?
						message = CMsgRemoteClientPing.parseFrom(messageBytes);
						
						CMsgRemoteClientPingResponse pingResponseMessage = CMsgRemoteClientPingResponse.newBuilder().	//Create the ping response protobuf to send
								build();	//Nothing to set here, move along..
						
						//Most of this stuff is the same as the other case blocks, so not going to retype comments here. TODO Write a function for this, this is bad OOP and hard to maintain
						byte[] pingResponseMessageBytes = pingResponseMessage.toByteArray();
						
						int pingResponseLength = pingResponseMessageBytes.length + 8;
						output.writeInt(Integer.reverseBytes(pingResponseLength));
						
						output.write(magicBytes);
						
						int pingResponseEmsg = Integer.reverseBytes(9506 | 0x80000000);
						output.writeInt(pingResponseEmsg);
						
						output.writeInt(0);
						
						output.write(pingResponseMessageBytes);
										
						break;
					case 9506:	//A ping response from the server (we get this if we ping the server)
						message = CMsgRemoteClientPingResponse.parseFrom(messageBytes);
						
						break;
					
				}
			} catch(IOException e){
				System.err.println("Expcetion thrown while handling a control packet");
				e.printStackTrace();
			}
			
		}
		
		//Close the TLS connection nicely
        try {
			protocol.close();
		} catch (IOException e) {
			System.err.println("Exception thrown when attempting to stop the TLS connction!");
			e.printStackTrace();
		}
        
        //Close the TCP connection nicely
        try {
			socket.close();
		} catch (IOException e) {
			System.err.println("Exception thrown when attempting to stop the TCP connection!");
			e.printStackTrace();
		}
		
	}
	
	public void startStream(int app_id, int maxXResolution, int maxYResolution) throws IOException{
		CMsgRemoteClientStartStream message = CMsgRemoteClientStartStream.newBuilder().
				setAppId(app_id).
				setGamepadCount(0).
				setLaunchOption(1).	//TODO Figure out WTF these values correspond to
				setLockParentalLock(false).
				setMaximumResolutionX(maxXResolution).
				setMaximumResolutionY(maxYResolution).
				build();
		
		byte[] message_bytes = message.toByteArray();
		
		int length = message_bytes.length + 8;
		output.writeInt(Integer.reverseBytes(length));
		
		output.write(magicBytes);
		
		int emsg = Integer.reverseBytes(9503 | 0x80000000);
		output.writeInt(emsg);
		
		output.writeInt(0);
		
		output.write(message_bytes);
	}	
	
	public HashMap<InetAddress, CMsgRemoteClientStartStreamResponse> getStreamTable(){
		return this.streamTable;
	}
	
	//This deals with the boring parts of the connection (TLS). To be honest I think I copy/pasted most of this from somewhere on the Internet
	static class StreamingPSKTlsClient extends PSKTlsClient{
		public StreamingPSKTlsClient(String authData){
			super(new Steam_PSKIdentity(authData));
		}
		
		@Override
		public int[] getCipherSuites(){
			return new int[]{
					CipherSuite.TLS_PSK_WITH_AES_128_CBC_SHA
			};
		}

	    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Exception cause){
        	PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
            out.println("TLS client raised alert (AlertLevel." + alertLevel + ", AlertDescription." + alertDescription + ")");
            if (message != null) {
                out.println(message);
            }
            if (cause != null) {
                cause.printStackTrace(out);
            }
        }

        public void notifyAlertReceived(short alertLevel, short alertDescription){
            @SuppressWarnings("resource")
			PrintStream out = (alertLevel == AlertLevel.fatal) ? System.err : System.out;
            out.println("TLS client received alert (AlertLevel." + alertLevel + ", AlertDescription." + alertDescription + ")");
        }

        public TlsAuthentication getAuthentication() throws IOException{
        	return new ServerOnlyTlsAuthentication(){
                public void notifyServerCertificate(org.bouncycastle.crypto.tls.Certificate serverCertificate) throws IOException{
                    System.out.println("in getAuthentication");
                }
            };
        }

        //This sets up the PSK information for the TLC session.
        static class Steam_PSKIdentity implements TlsPSKIdentity {
        	private final String authData;
        	public Steam_PSKIdentity(String authData){
        		this.authData = authData;
        	}
        	@Override
        	public void skipIdentityHint(){
  		    	//This doesn't need to be set
        	}
        	
        	@Override
        	public void notifyIdentityHint(byte[] PSK_identity_hint){
        		//This doesn't need to be set either
        	}
        	
        	@Override
        	public byte[] getPSKIdentity(){
        		return "steam".getBytes();	//In TLS, the PSK identity tells that server which PSK we're about to use. This is the identity for the AuthKey.
        	}
        	
        	@Override
        	public byte[] getPSK(){
        		return DatatypeConverter.parseHexBinary(authData);	//The AuthKey is the PSK.
        	}

        }
	}
	
	public InetAddress getServer(){
		return this.serverIP;
	}
	
}
