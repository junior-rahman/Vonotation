package app.voiceflow;

/** The bubble skips node queries only for VoiceFlow's own screens. */
public final class OwnWindowTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String own = "app.voiceflow";
        check(OwnWindow.isOwnScreen(own, "app.voiceflow", "app.voiceflow.MainActivity"), "main activity");
        check(OwnWindow.isOwnScreen(own, "app.voiceflow", "app.voiceflow.CaptureActivity"), "capture activity");
        check(OwnWindow.isOwnScreen(own, "app.voiceflow", "android.app.AlertDialog"), "own dialog");
        check(!OwnWindow.isOwnScreen(own, "app.voiceflow", "android.widget.LinearLayout"), "bubble overlay is not a screen");
        check(!OwnWindow.isOwnScreen(own, "com.android.quicksearchbox", "com.android.quicksearchbox.SearchActivity"), "other app");
        check(!OwnWindow.isOwnScreen(own, null, null), "null event");
        System.out.println("PASS: 6 own-window checks.");
    }
}
