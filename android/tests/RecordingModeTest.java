package app.voiceflow;

/** Notifications denied or startForeground refused must never end a recording. */
public final class RecordingModeTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        check(RecordingMode.of(true, true).equals(RecordingMode.VISIBLE), "allowed shows notice");
        check(RecordingMode.of(true, false).equals(RecordingMode.HIDDEN), "denied keeps mic-type foreground");
        check(RecordingMode.of(false, false).equals(RecordingMode.BUBBLE_ONLY), "refused falls back to bubble");
        check(RecordingMode.of(false, true).equals(RecordingMode.BUBBLE_ONLY), "refused even when allowed");
        for (boolean a : new boolean[]{true, false}) for (boolean b : new boolean[]{true, false})
            check(RecordingMode.recordingContinues(RecordingMode.of(a, b)), "never ends session " + a + b);
        System.out.println("PASS: 8 recording-mode checks.");
    }
}
