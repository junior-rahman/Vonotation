package app.voiceflow;

public final class CallShellLaunchTest {
    private static int checks;
    public static void main(String[] args){
        // Each product resolves its own APK; Vocading must never look up Voitation's package.
        equal("pm path app.vonotation.notes",CallShellLaunch.pathCommand("app.vonotation.notes"));
        equal("pm path app.voiceflow",CallShellLaunch.pathCommand("app.voiceflow"));
        for(String bad:new String[]{null,"","app","app.voiceflow; rm -rf /","app.voice flow","app.voiceflow\nid","$(id).app","app..voiceflow","1app.voiceflow"})reject(()->CallShellLaunch.pathCommand(bad));

        String base="/data/app/~~Zx9_aB-c==/app.vonotation.notes-Qr7_t==/base.apk";
        equal(base,CallShellLaunch.baseApk("package:"+base+"\n"));
        equal(base,CallShellLaunch.baseApk("package:/data/app/x/split_config.en.apk\npackage:"+base+"\n"));
        equal("/data/app/x/only.apk",CallShellLaunch.baseApk("package:/data/app/x/only.apk"));
        equal(null,CallShellLaunch.baseApk(""));equal(null,CallShellLaunch.baseApk(null));
        equal(null,CallShellLaunch.baseApk("Error: package not found"));
        equal(null,CallShellLaunch.baseApk("package:/data/app/x'; reboot; '/base.apk"));
        equal(null,CallShellLaunch.baseApk("package:relative/base.apk"));

        equal("CLASSPATH='"+base+"' exec app_process /system/bin app.voiceflow.CallShell",CallShellLaunch.launchCommand(base));
        for(String bad:new String[]{null,"","base.apk","/data/app/x/base.jar","/data/app/x'/base.apk","/data/app/x$(id)/base.apk","/data/app/x /base.apk"})reject(()->CallShellLaunch.launchCommand(bad));
        System.out.println("PASS: "+checks+" call recorder package and shell command checks.");
    }
    private static void equal(Object expected,Object actual){checks++;if(expected==null?actual!=null:!expected.equals(actual))throw new AssertionError("expected <"+expected+"> but was <"+actual+">");}
    private static void reject(Runnable action){checks++;try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("expected rejection");}
}
