package app.voiceflow;

/**
 * Android-free shell commands for the wireless-debugging call recorder. The recorder must run from
 * the calling app's own APK: after the split, call recording ships only in Vocading, so a fixed
 * package name would find Voitation's APK (or nothing) instead.
 */
final class CallShellLaunch {
    private CallShellLaunch() {}
    static final String ENTRY="app.voiceflow.CallShell";

    static String pathCommand(String packageName){
        if(packageName==null || !packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))throw new IllegalArgumentException("Invalid package name.");
        return "pm path "+packageName;
    }
    /** The base APK from `pm path` output; split APKs follow it and never carry the recorder. */
    static String baseApk(String pmOutput){
        if(pmOutput==null)return null;
        String first=null;
        for(String line:pmOutput.split("\n")){
            String path=line.trim();if(path.startsWith("package:"))path=path.substring("package:".length()).trim();
            if(!path.startsWith("/") || !path.endsWith(".apk") || !path.matches("[A-Za-z0-9/._=~+-]+"))continue;
            if(path.endsWith("/base.apk"))return path;
            if(first==null)first=path;
        }
        return first;
    }
    static String launchCommand(String apk){
        if(apk==null || !apk.startsWith("/") || !apk.endsWith(".apk") || !apk.matches("[A-Za-z0-9/._=~+-]+"))throw new IllegalArgumentException("Invalid APK path.");
        return "CLASSPATH='"+apk+"' exec app_process /system/bin "+ENTRY;
    }
}
