package net;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class AuthKeyFinder {
	public static void main(String args[]){
		File userdata = null;	//This will contain the file object for Steam's userdata folder
		
		//Try and find the userdata folder based on OS
		try{
	        String os = System.getProperty("os.name").toLowerCase();

	        if (os.contains("win")){
	            userdata = new File("C:\\Program Files (x86)\\Steam\\userdata\\");
	        }
	        else if(os.contains("mac")){
	        	userdata = new File("~/Library/Application Support/Steam/userdata");
	        }
	        else if(os.contains("nix")){
	        	userdata = new File("~/.local/share/Steam/userdata");
	        }
	        else{
	        	System.out.println("Unknown OS: " + os + ", could not detect Steam directory");
	        	System.exit(1);
	        }
	    }
	    catch (final Exception e){
	        //  Handle any exceptions.
	    	e.printStackTrace();
	    }
		 
        if(userdata == null){	//Sanity check
        	System.err.println("Steam install location not set, exiting");
        	System.exit(1);
        }
        
        String accountIDFolders[] = userdata.list();	//A list of all the accounts that have been logged into this computer
        
        if(accountIDFolders.length == 0){
        	System.out.println("No accounts found. Are you currently running and logged into Steam?");
        	System.exit(1);
        }
        
        for(String accountID : accountIDFolders){	//Loop through each folder
        	File localconfig = new File(userdata, accountID + "\\config\\localconfig.vdf");	//The file object for the localconfig file
        	List<String> list = null;	//Holds a list of all the lines in the file
			try {
				list = Files.readAllLines(localconfig.toPath(), StandardCharsets.UTF_8);	//Read each one into the list
			} catch (IOException e) {
				System.err.println("Error when parsing file: " + localconfig.toString());
				e.printStackTrace();
			}
			
			int authLocation = list.indexOf("	\"SharedAuth\"");	//Find the location in the list of '	"SharedAuth"'
			
			if(authLocation != -1){	//If it found an entry
				System.out.println(list.get(authLocation + 2).trim());	//Print the line 2 after SharedAuth (the streaming ID)
				System.out.println(list.get(authLocation + 3).trim());	//Print the line 3 after SharedAuth (the AuthData key)
			}
        }
	}
}
