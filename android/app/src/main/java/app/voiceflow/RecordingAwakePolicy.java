package app.voiceflow;

/** Timed recording resources, independent of whichever app currently owns the screen. */
final class RecordingAwakePolicy implements AutoCloseable {
    static final long LOCK_TIMEOUT_MS=10*60*1000L;
    static final long RENEW_MS=30*1000L;

    interface Clock { long now(); }
    interface Port {
        void acquireCpu(long timeoutMs);
        void acquireDisplay(long timeoutMs);
        void releaseCpu();
        void releaseDisplay();
    }

    private final Clock clock;
    private final Port port;
    private boolean active,closed,screenOff,displayRequested;
    private long renewedAt;

    RecordingAwakePolicy(Clock clock,Port port){this.clock=clock;this.port=port;}

    synchronized void start(boolean interactive,boolean unlocked){
        if(active||closed)return;
        active=true;
        renew(interactive,unlocked);
    }

    synchronized void refresh(boolean interactive,boolean unlocked){
        if(!active)return;
        // Release immediately when the screen locks, even before the renewal is due.
        if((!interactive||!unlocked)&&displayRequested)releaseDisplay();
        long now=clock.now();
        if(now<renewedAt||now-renewedAt>=RENEW_MS)renew(interactive,unlocked);
    }

    synchronized void screenOff(){
        if(!active)return;
        // SCREEN_OFF can arrive before isInteractive changes. Never undo a power-button press.
        screenOff=true;
        releaseDisplay();
    }

    synchronized void userPresent(boolean interactive,boolean unlocked){
        if(!active||!interactive||!unlocked)return;
        screenOff=false;
        renew(true,true);
    }

    private void renew(boolean interactive,boolean unlocked){
        port.acquireCpu(LOCK_TIMEOUT_MS);
        if(interactive&&unlocked&&!screenOff){
            port.acquireDisplay(LOCK_TIMEOUT_MS);
            displayRequested=true;
        }else if(displayRequested)releaseDisplay();
        renewedAt=clock.now();
    }

    private void releaseDisplay(){
        port.releaseDisplay();
        displayRequested=false;
    }

    @Override public synchronized void close(){
        if(closed)return;
        closed=true;
        active=false;
        // Also clean up a partially acquired resource after a platform error.
        releaseDisplay();
        port.releaseCpu();
    }
}
