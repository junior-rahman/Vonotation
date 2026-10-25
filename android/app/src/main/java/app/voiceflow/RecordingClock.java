package app.voiceflow;

/** Monotonic active duration: pauses never become recorded time. */
final class RecordingClock {
    private long began, pausedAt, pausedTotal;
    private boolean started, paused;
    synchronized void start(long now){began=now;pausedAt=0;pausedTotal=0;started=true;paused=false;}
    synchronized void pause(long now){if(started&&!paused){paused=true;pausedAt=now;}}
    synchronized void resume(long now){if(started&&paused){pausedTotal+=Math.max(0,now-pausedAt);paused=false;}}
    synchronized long elapsed(long now){return !started?0:Math.max(0,(paused?pausedAt:now)-began-pausedTotal);}
}
