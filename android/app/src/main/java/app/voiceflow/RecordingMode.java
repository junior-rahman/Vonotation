package app.voiceflow;

/** Android-free: how a bubble/keyboard recording runs. See RecordingNotice. */
final class RecordingMode {
    static final String NONE = "none";
    static final String VISIBLE = "foreground+notice";
    static final String HIDDEN = "foreground, notice hidden (notifications denied)";
    static final String BUBBLE_ONLY = "bubble only (foreground refused)";
    private RecordingMode() {}
    /** A refused foreground notice never ends the session; it only changes where status shows. */
    static String of(boolean foregroundStarted, boolean notificationsAllowed) {
        if (!foregroundStarted) return BUBBLE_ONLY;
        return notificationsAllowed ? VISIBLE : HIDDEN;
    }
    static boolean recordingContinues(String mode) { return !NONE.equals(mode); }
}
