package app.voiceflow;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One microphone take for a note or a translation. Notes record straight to storage and keep going without a network. */
final class VoiceTake {
    interface Listener {
        void listening();
        void finished(File file);
        void failed(String message);
        /** Sealed source segments. The live segment is not included; final completion reports failures. */
        default void partReady(java.util.List<File> files) {}
        /** Every segment, including the last. Translation uses the single-file callback. */
        default void finishedAll(java.util.List<File> files) { if(files!=null && !files.isEmpty()) finished(files.get(0)); }
        /** False means a recorder or segment failed; surviving source files still belong to the note. */
        default void finishedAll(java.util.List<File> files,boolean captureComplete) { finishedAll(files); }
    }
    private static volatile VoiceTake current;
    private static final java.util.concurrent.atomic.AtomicInteger pendingWork=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean countedWork=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean finalizing;
    /** Includes setup, capture, recorder finalization and the completed-file save callback. */
    static boolean updateBusy(){return pendingWork.get()>0;}
    private void beginWork(){if(countedWork.compareAndSet(false,true))pendingWork.incrementAndGet();}
    private void completeWork(){if(countedWork.compareAndSet(true,false)){pendingWork.decrementAndGet();captureLease.close();executor.shutdown();handler.post(()->{if(FlowAccessibility.instance!=null)FlowAccessibility.instance.updateBubble();});}}
    private final Context context;
    private final Listener listener;
    private final CaptureGate.Lease captureLease;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile MediaRecorder recorder;
    private File file;
    private volatile boolean active;
    private boolean started,untilQuiet,note,call,thought,heardSpeech;
    private volatile boolean dropRequested;
    private volatile boolean discardedNote;
    private int source=-1;
    private long startedAt;
    private int index;
    private File folder;
    private long noteId;
    private final CaptureSegments segments=new CaptureSegments();
    private final java.util.ArrayList<Integer> levels=new java.util.ArrayList<>();
    private static int[] lastBars=new int[0];
    private android.os.PowerManager.WakeLock wake;
    private static final long SEGMENT=3*60*1000L;

    private VoiceTake(Context context,Listener listener,CaptureGate.Lease lease){this.context=context.getApplicationContext();this.listener=listener;captureLease=lease;}

    static boolean running(){VoiceTake take=current;return take!=null && take.active;}
    static boolean noteRunning(){VoiceTake take=current;return take!=null && take.note && !take.call && !take.thought && take.active;}
    static boolean thoughtRunning(){VoiceTake take=current;return take!=null && take.thought && take.active;}
    static boolean callRunning(){VoiceTake take=current;return take!=null && take.call && take.active;}
    static long callId(){VoiceTake take=current;return take!=null && take.call?take.noteId:-1;}
    /** True only when the phone actually gave us the call audio, not just the microphone. */
    static boolean bothSides(){VoiceTake take=current;return take!=null && take.source==MediaRecorder.AudioSource.VOICE_CALL;}
    static long elapsed(){VoiceTake take=current;return take==null?0:android.os.SystemClock.elapsedRealtime()-take.startedAt;}
    /** Bars for the whole take, 0 to 9. Safe to call from the finish callback. */
    static int[] bars(){return lastBars;}
    /** The latest bars while a note is still recording. */
    static int[] liveBars(int count){
        VoiceTake take=current;if(take==null)return new int[0];
        java.util.ArrayList<Integer> src=take.levels;
        int n=Math.min(count,src.size());
        int[] out=new int[n];
        for(int i=0;i<n;i++)out[i]=src.get(src.size()-n+i);
        return out;
    }

    static void begin(Context context,Listener listener) { begin(context,listener,false); }

    /** untilQuiet stops after a short pause once speech has started. Used by conversation mode. */
    static void begin(Context context,Listener listener,boolean untilQuiet) { open(context,listener,untilQuiet,true,null,0,false,false); }

    /** A note. No API key and no network. Segments land in folder as they finish so a long meeting is kept. */
    static void beginNote(Context context,Listener listener,File folder,long id) { open(context,listener,false,false,folder,id,false,false); }

    /** An inline thought has a separate owner from the voice-notes recording controls. */
    static void beginThought(Context context,Listener listener,File folder,long id) { open(context,listener,false,false,folder,id,false,true); }

    /** A phone call. Tries the call audio first, then the microphone, and keeps going without a network. */
    static void beginCall(Context context,Listener listener,File folder,long id) { open(context,listener,false,false,folder,id,true,false); }

    private static synchronized void open(Context context,Listener listener,boolean untilQuiet,boolean needsKey,File folder,long id,boolean call,boolean thought) {
        if(Dictation.isWorking()){listener.failed("Finish your dictation first.");return;}
        if(current!=null && current.note && current.active){listener.failed("Finish the recording first.");return;}
        if(current!=null && current.note && current.finalizing){listener.failed("The recording is still being saved. Try again in a moment.");return;}
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){listener.failed("Allow microphone access first.");return;}
        if(needsKey && Vault.get(context).isEmpty()){listener.failed("Add your API key in Settings first.");return;}
        CaptureGate.Lease lease=CaptureBarrier.begin(context);
        if(lease==null){listener.failed("Finish or cancel the app update before recording.");return;}
        cancel();
        current=new VoiceTake(context,listener,lease);
        current.untilQuiet=untilQuiet;current.note=!needsKey;current.call=call;current.thought=thought;current.folder=folder;current.noteId=id;
        current.start();
    }

    static synchronized void finish(){if(current!=null)current.stop();}
    private static synchronized void forget(VoiceTake take){if(current==take)current=null;}

    static boolean finishNote(long id){VoiceTake take=current;if(take==null || !take.note || take.call || take.thought || take.noteId!=id || !take.active)return false;take.stop();return true;}
    static boolean finishThought(long id){VoiceTake take=current;if(take==null || !take.thought || take.noteId!=id || !take.active)return false;take.stop();return true;}
    static boolean finishCall(long id){VoiceTake take=current;if(take==null || !take.call || take.noteId!=id || !take.active)return false;take.stop();return true;}

    static synchronized void cancel(){if(current!=null && current.note && (current.active || current.finalizing))return;if(current!=null){current.drop();current=null;}}
    /** Explicit owner-only cancellation; an unrelated dictation cannot discard a saved note or call. */
    static synchronized boolean cancelNote(long id){
        VoiceTake take=current;
        if(take==null || !take.note || take.call || take.noteId!=id)return false;
        take.discardedNote=true;current=null;take.drop();return true;
    }

    private void start() {
        beginWork();active=true;
        if(note)holdWake();
        executor.execute(()->{
            String failure=null;
            try {
                if(!active)return;
                if(note && folder!=null && !folder.isDirectory() && !folder.mkdirs())throw new IllegalStateException("Could not create the recording folder.");
                file=nextFile();
                arm(file);
                started=true;startedAt=android.os.SystemClock.elapsedRealtime();
                handler.post(()->{if(active)listener.listening();if(active && untilQuiet)handler.postDelayed(quietPoll,200);if(active && note){handler.postDelayed(rotate,SEGMENT);handler.postDelayed(meter,200);}});
            } catch(Exception error) {
                failure=error.getMessage()==null?"Could not start the microphone.":error.getMessage();
            }
            if(failure!=null){segments.interrupted();final String message=failure;handler.post(()->stop(false,message));}
        });
    }

    private File nextFile() throws java.io.IOException {
        return note?new File(folder,noteId+"-"+(index++)+".m4a"):File.createTempFile("voiceflow-take-",".m4a",context.getCacheDir());
    }

    private void arm(File target) throws Exception {
        int[] sources=source>=0?new int[]{source}:call?new int[]{MediaRecorder.AudioSource.VOICE_CALL,MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.MIC}:new int[]{MediaRecorder.AudioSource.MIC};
        Exception last=null;
        for(int pick:sources){
            MediaRecorder next=null;
            try {
                next=Build.VERSION.SDK_INT>=31?new MediaRecorder(context):new MediaRecorder();
                next.setOnErrorListener((failed,what,extra)->{
                    if(recorder!=failed)return;
                    segments.interrupted();
                    handler.post(()->stop(false,"The recording was interrupted. Your source audio was kept."));
                });
                next.setAudioSource(pick);
                next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                next.setAudioSamplingRate(44100);
                next.setAudioEncodingBitRate(64000);
                next.setOutputFile(target.getAbsolutePath());
                next.prepare();
                next.start();
                recorder=next;source=pick;file=target;
                return;
            } catch(Exception error) {
                last=error;
                if(next!=null){try{next.reset();}catch(Exception ignored){}try{next.release();}catch(Exception ignored){}}
                // A failed setup can leave recoverable bytes. Never overwrite a note's source.
                if(note && target.length()>0){segments.sealed(target,false);file=null;target=nextFile();}
                else {if(target.exists())target.delete();file=null;}
            }
        }
        throw last==null?new IllegalStateException("Could not start the microphone."):last;
    }

    /** A finished segment is a real file. The next one starts immediately so a meeting is not one fragile recording. */
    private final Runnable rotate=new Runnable(){
        public void run(){
            if(!active || !note)return;
            executor.execute(()->{
                if(!active)return;
                if(!seal()){segments.interrupted();handler.post(()->stop(false,"The recording was interrupted. Your source audio was kept."));return;}
                if(!active)return;
                try {
                    arm(nextFile());
                    started=true;
                    java.util.List<File> ready=segments.files();
                    handler.post(()->{if(active && !discardedNote)listener.partReady(ready);});
                    handler.postDelayed(rotate,SEGMENT);
                } catch(Exception error) {
                    segments.interrupted();
                    handler.post(()->stop(false,"The recording was interrupted. Your source audio was kept."));
                }
            });
        }
    };

    private final Runnable meter=new Runnable(){
        public void run(){
            if(!active || !note)return;
            int amp=0;try{if(recorder!=null)amp=recorder.getMaxAmplitude();}catch(RuntimeException ignored){}
            levels.add(Math.min(9,amp/2800));
            handler.postDelayed(this,200);
        }
    };

    private static int[] pack(java.util.ArrayList<Integer> src) {
        if(src.isEmpty())return new int[0];
        int buckets=Math.min(72,src.size());
        int[] out=new int[buckets];
        for(int i=0;i<buckets;i++){
            int from=i*src.size()/buckets,to=Math.max(from+1,(i+1)*src.size()/buckets),peak=0;
            for(int j=from;j<to && j<src.size();j++)peak=Math.max(peak,src.get(j));
            out[i]=peak;
        }
        return out;
    }

    private final Runnable quietPoll=new Runnable(){
        int loud,quiet;
        public void run(){
            if(!active || !untilQuiet)return;
            int amp=0;try{if(recorder!=null)amp=recorder.getMaxAmplitude();}catch(RuntimeException ignored){}
            if(amp>800){loud++;quiet=0;if(loud>=3)heardSpeech=true;}else if(loud>=3)quiet++;
            long elapsed=android.os.SystemClock.elapsedRealtime()-startedAt;
            if((loud>=3 && quiet>=3) || elapsed>12000){stop();return;}
            handler.postDelayed(this,200);
        }
    };

    private void stop() { stop(true,null); }

    private synchronized void stop(boolean complete,String failure) {
        if(!complete)segments.interrupted();
        if(!active)return;
        finalizing=true;active=false;handler.removeCallbacks(quietPoll);handler.removeCallbacks(rotate);handler.removeCallbacks(meter);
        int[] bars=pack(new java.util.ArrayList<>(levels));
        executor.execute(()->{
            seal();
            releaseWake();
            lastBars=bars;
            java.util.List<File> ready=segments.files();
            boolean captureComplete=segments.complete();
            handler.post(()->{
                try{
                    forget(this);
                    if(discardedNote){for(File discarded:ready)discarded.delete();return;}
                    if(dropRequested)return;
                    if(untilQuiet && !heardSpeech){for(File silent:ready)silent.delete();listener.failed("Nothing was recorded.");return;}
                    if(ready.isEmpty())listener.failed(failure==null?"Nothing was recorded.":failure);else listener.finishedAll(ready,captureComplete);
                }finally{completeWork();}
            });
        });
    }

    /** Sealing retains bytes even when stop() fails; those bytes never count as a complete capture. */
    private boolean seal() {
        File done=file;file=null;
        MediaRecorder held=recorder;recorder=null;
        boolean finalized=started && held!=null;started=false;
        try { if(finalized) held.stop(); } catch(RuntimeException error) { finalized=false; }
        if(held!=null)try{held.release();}catch(Exception ignored){}
        if(done==null)return false;
        return segments.sealed(done,finalized);
    }

    private synchronized void drop() {
        if(dropRequested)return;
        dropRequested=true;active=false;handler.removeCallbacks(quietPoll);handler.removeCallbacks(rotate);handler.removeCallbacks(meter);
        if(!countedWork.get())return;
        executor.execute(()->{
            try{seal();if(!note || discardedNote)for(File discarded:segments.files())discarded.delete();segments.clear();releaseWake();}
            finally{if(!finalizing)completeWork();} // A queued finishedAll callback still owns finalization.
        });
    }

    private void holdWake() {
        try {
            android.os.PowerManager power=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
            if(power==null)return;
            wake=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"voiceflow:note");
            wake.setReferenceCounted(false);
            wake.acquire(4*60*60*1000L);
        } catch(RuntimeException ignored) {}
    }

    private void releaseWake(){android.os.PowerManager.WakeLock held=wake;wake=null;if(held!=null && held.isHeld())try{held.release();}catch(RuntimeException ignored){}}

}
