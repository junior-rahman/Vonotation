package app.voiceflow;

/** Stateful resource tests: expiry, renewal and late callbacks, without Android or real time. */
public final class RecordingAwakeTest {
    private static int checks;
    public static void main(String[] args){
        Clock clock=new Clock();Resources resources=new Resources(clock);
        RecordingAwakePolicy awake=new RecordingAwakePolicy(clock,resources);
        awake.start(true,true);
        check(resources.cpu&&resources.display,"Active dictation holds CPU and display");
        check(resources.cpuUntil==600000&&resources.displayUntil==600000,"Every acquisition has a ten-minute expiry");
        awake.start(true,true);
        check(resources.cpuAcquires==1,"Repeated start cannot acquire twice");
        clock.now=29999;awake.refresh(true,true);
        check(resources.cpuAcquires==1,"Activity/window changes before renewal do not duplicate acquisition");
        clock.now=30000;awake.refresh(true,true);
        check(resources.cpuAcquires==2&&resources.displayAcquires==2,"A recording renews both resources at thirty seconds");
        check(resources.displayUntil==630000,"Renewal extends the bounded display lease");
        awake.screenOff();
        check(resources.cpu&&!resources.display,"Power-button screen-off releases display, retaining capture CPU");
        clock.now=60000;awake.refresh(true,true);
        check(resources.cpu&&!resources.display,"A stale interactive snapshot cannot undo explicit screen-off");
        awake.userPresent(true,false);
        check(!resources.display,"Screen-on while locked cannot acquire a display lock");
        awake.userPresent(false,true);
        check(!resources.display,"A non-interactive screen is never woken by the resource policy");
        awake.userPresent(true,true);
        check(resources.display,"Unlock during the same dictation restores timeout protection");
        clock.now=60001;awake.refresh(true,false);
        check(!resources.display&&resources.cpu,"A lock-state change releases display even before thirty seconds");
        awake.userPresent(true,true);
        int displayAcquires=resources.displayAcquires,cpuAcquires=resources.cpuAcquires;
        awake.close();int cpuReleases=resources.cpuReleases,displayReleases=resources.displayReleases;
        check(!resources.cpu&&!resources.display,"Finish/error/cancel releases both resources");
        clock.now=120000;awake.refresh(true,true);awake.userPresent(true,true);awake.screenOff();awake.start(true,true);awake.close();
        check(resources.displayAcquires==displayAcquires&&resources.cpuAcquires==cpuAcquires,"Late callbacks cannot reacquire after close");
        check(resources.cpuReleases==cpuReleases&&resources.displayReleases==displayReleases,"Close is idempotent");
        RecordingAwakePolicy neverStarted=new RecordingAwakePolicy(clock,resources);
        neverStarted.close();cpuAcquires=resources.cpuAcquires;neverStarted.start(true,true);
        check(resources.cpuAcquires==cpuAcquires,"Cancel before start permanently prevents later acquisition");
        Resources background=new Resources(clock);RecordingAwakePolicy off=new RecordingAwakePolicy(clock,background);
        off.start(false,false);
        check(background.cpu&&!background.display,"Starting without an interactive unlocked screen keeps CPU only");
        clock.now+=RecordingAwakePolicy.LOCK_TIMEOUT_MS;background.expire();
        check(!background.cpu&&!background.display,"If renewal never runs, all locks expire rather than leak forever");
        off.close();
        Resources longCapture=new Resources(clock);RecordingAwakePolicy ongoing=new RecordingAwakePolicy(clock,longCapture);
        ongoing.start(true,true);
        for(int minute=0;minute<20;minute++){
            clock.now+=60000;longCapture.expire();
            check(longCapture.cpu&&longCapture.display,"Long recordings retain both resources between heartbeat renewals");
            ongoing.refresh(true,true);
        }
        ongoing.close();
        check(!longCapture.cpu&&!longCapture.display,"A capture beyond the initial ten-minute timeout still releases cleanly");
        System.out.println("PASS: "+checks+" recording awake resource lifecycle checks");
    }
    private static void check(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static final class Clock implements RecordingAwakePolicy.Clock {long now;public long now(){return now;}}
    private static final class Resources implements RecordingAwakePolicy.Port {
        final Clock clock;boolean cpu,display;long cpuUntil,displayUntil;int cpuAcquires,displayAcquires,cpuReleases,displayReleases;
        Resources(Clock clock){this.clock=clock;}
        public void acquireCpu(long timeoutMs){check(timeoutMs>0&&timeoutMs<=600000,"CPU lease is bounded");cpu=true;cpuUntil=clock.now+timeoutMs;cpuAcquires++;}
        public void acquireDisplay(long timeoutMs){check(timeoutMs>0&&timeoutMs<=600000,"Display lease is bounded");display=true;displayUntil=clock.now+timeoutMs;displayAcquires++;}
        public void releaseCpu(){cpu=false;cpuReleases++;}
        public void releaseDisplay(){display=false;displayReleases++;}
        void expire(){if(clock.now>=cpuUntil)cpu=false;if(clock.now>=displayUntil)display=false;}
    }
}
