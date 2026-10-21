package app.voiceflow;

/** Session ownership for the notes-only microphone foreground service. */
final class NoteCaptureSession {
    private static final int IDLE=0, STARTING=1, RECORDING=2, FINALIZING=3;
    private long owner=-1;
    private int state=IDLE;

    synchronized boolean begin(long id) {
        if(id<=0 || state!=IDLE)return false;
        owner=id;state=STARTING;return true;
    }
    synchronized boolean ready(long id) {
        if(owner!=id || state!=STARTING)return false;
        state=RECORDING;return true;
    }
    synchronized boolean finalizing(long id) {
        if(owner!=id || state==IDLE)return false;
        state=FINALIZING;return true;
    }
    synchronized boolean release(long id) {
        if(owner!=id || state==IDLE)return false;
        owner=-1;state=IDLE;return true;
    }
    synchronized boolean owns(long id) { return id>0 && owner==id && state!=IDLE; }
    synchronized boolean busy() { return state!=IDLE; }
}
