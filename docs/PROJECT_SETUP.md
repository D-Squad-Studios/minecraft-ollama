## Setup Installations:
1) Download java (64-bit is required) here: https://www.oracle.com/java/technologies/downloads/#jdk21-windows
2) Download the minecraft launcher, sign in - you're all good here.
3) Download MultiMC here: https://multimc.org/ 
4) In MultiMC's configuration, it'll ask for a java version. Find the java folder in your computer, click the "Configure Java" program. Go to the "Java" submenu in the program that pops up, go to "View", and copy the "Path" of the single java version in there.
5) Paste the path into MultiMC's configuration asking for a java version.
6) Sign into your microsoft account associated with the minecraft licsence in the top right corner of the GUI.

## Installing Forge + Final MultiMC Setup:
1) Create a new Minecraft instance via the button at the top of the GUI (most recent minecraft version)
5) Right click it -> "Edit Instance" -> Right click the "Minecraft" with the green checkmark -> "Install Forge" (most recent version)

## Creating an unsigned .jar file manually (Assumed Java is installed) and installing into MultiMC:
1) Run "./gradelw build" in the project repository
2) After completion, .jar file is found under repository's /build/libs
3) In MultiMC's homepage the right side has a button "View Mods", click that and right click the empty space -> click "View Folder"
4) Copy and paste the .jar file in /build/libs (in our project) into that opened mods folder.

## Running the modded minecraft client:
1) Simply click "Launch" or double click the minecraft instance within MultiMC
  
  
#### Disclaimer: Tested on Windows and Mac as of 11/04/2025
