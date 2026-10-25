package app.voiceflow;

/** Android-free: is a window-state event from one of Voitation's own screens (not the bubble overlay)? */
final class OwnWindow {
    private OwnWindow() {}
    static boolean afterEvent(boolean previous,String ownPackage,CharSequence eventPackage,CharSequence className,boolean inputMethodOrSystem){
        if(isOwnScreen(ownPackage,eventPackage,className))return true;
        if(eventPackage==null || ownPackage.contentEquals(eventPackage) || inputMethodOrSystem)return previous;
        return false;
    }
    static boolean isOwnScreen(String ownPackage, CharSequence eventPackage, CharSequence className) {
        if (eventPackage == null || !ownPackage.contentEquals(eventPackage)) return false;
        String name = className == null ? "" : className.toString();
        // ClipPeek only borrows focus long enough to read the clipboard. It is not a Voitation screen.
        if(name.endsWith("ClipPeek"))return false;
        return name.startsWith("app.voiceflow.") && name.endsWith("Activity") || name.startsWith("android.app.") && name.endsWith("Dialog");
    }
}
