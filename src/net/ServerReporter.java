package net;

import java.net.InetAddress;

public class ServerReporter {

	public static void main(String[] args) {
		if(args.length != 4 && args.length != 3){
			System.out.println("Usage: ServerReporter <Steam ID> <Authentication ID> <Local IPv4 address> (Client ID)");
			System.exit(1);
		}
		
		long steamID = 0;
		int authID = 0;
		
		try{
			steamID = Long.parseLong(args[0]);
			authID = Integer.parseInt(args[1]);
		} catch(NumberFormatException e){
			System.err.println("Input was incorrectly formatted, exiting");
			System.exit(1);
		}
		
		//Creates a new DiscoveryProtocolHandler, creates a thread for it, and starts the thread
		DiscoveryProtocolHandler dph = new DiscoveryProtocolHandler(steamID, authID, args[2]);
		Thread dphThread = new Thread(dph);
		dphThread.start();
		
		while(true){
			clearConsole();
			
			//Get the list of Steam streaming servers and print it
			for(InetAddress address : dph.getServerTable().keySet()){
				System.out.println(address.toString() + ": ");
				System.out.println(dph.getServerTable().get(address).toString());
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public static void clearConsole(){
	    try{
	        String os = System.getProperty("os.name");

	        if (os.contains("Windows")){
	            Runtime.getRuntime().exec("cls");
	        }
	        else{
	            Runtime.getRuntime().exec("clear");
	        }
	    }
	    catch (final Exception e){
	        //  Handle any exceptions.
	    }
	}

}
