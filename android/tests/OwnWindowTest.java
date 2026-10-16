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
        check(OwnWindow.afterEvent(false,own,own,"app.voiceflow.MainActivity",false),"own activity becomes foreground");
        check(OwnWindow.afterEvent(true,own,own,"android.widget.LinearLayout",false),"bubble event preserves foreground own activity guard");
        check(OwnWindow.afterEvent(true,own,"com.android.inputmethod","InputMethod",true),"IME event preserves own activity guard");
        check(OwnWindow.afterEvent(true,own,"com.android.systemui","StatusBar",true),"system overlay preserves own activity guard");
        check(OwnWindow.afterEvent(true,own,null,null,false),"missing package cannot prove departure");
        check(!OwnWindow.afterEvent(true,own,"com.browser","BrowserActivity",false),"another application clears own activity guard");
        check(!OwnWindow.afterEvent(false,own,own,"android.widget.LinearLayout",false),"bubble alone cannot claim foreground activity");
        System.out.println("PASS: 13 own-window checks.");
    }
}
