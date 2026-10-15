package app.voiceflow;

/** Android-free: is a window-state event from one of VonoTation's own screens (not the bubble overlay)? */
final class OwnWindow {
    private OwnWindow() {}
    static boolean isOwnScreen(String ownPackage, CharSequence eventPackage, CharSequence className) {
        if (eventPackage == null || !ownPackage.contentEquals(eventPackage)) return false;
        String name = className == null ? "" : className.toString();
        // ClipPeek only borrows focus long enough to read the clipboard. It is not a VonoTation screen.
        if(name.endsWith("ClipPeek"))return false;
        return name.startsWith("app.voiceflow.") && name.endsWith("Activity") || name.startsWith("android.app.") && name.endsWith("Dialog");
    }
}
