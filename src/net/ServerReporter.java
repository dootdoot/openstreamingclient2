package net;

import java.net.InetAddress;

public class ServerReporter {

	public static void main(String[] args) {
		//Creates a new DiscoveryProtocolHandler, creates a thread for it, and starts the thread
		DiscoveryProtocolHandler dph = new DiscoveryProtocolHandler();
		Thread dphThread = new Thread(dph);
		dphThread.start();
		
		while(true){
			clearConsole();
			
			//Get the list of Steam streaming servers and print it
			for(InetAddress address : dph.getServerTable().keySet()){
				System.out.println(address + ": ");
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
