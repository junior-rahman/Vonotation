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
        /** Completed note segments, already stopped cleanly. The live segment is not included. */
        default void partReady(java.util.List<File> files) {}
        /** Every segment, including the last. Translation uses the single-file callback. */
        default void finishedAll(java.util.List<File> files) { if(files!=null && !files.isEmpty()) finished(files.get(0)); }
    }
    private static VoiceTake current;
    private static final java.util.concurrent.atomic.AtomicInteger pendingWork=new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean countedWork=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean finalizing;
    /** Includes setup, capture, recorder finalization and the completed-file save callback. */
    static boolean updateBusy(){return pendingWork.get()>0;}
    private void beginWork(){if(countedWork.compareAndSet(false,true))pendingWork.incrementAndGet();}
    private void completeWork(){if(countedWork.compareAndSet(true,false))pendingWork.decrementAndGet();}
    private final Context context;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private MediaRecorder recorder;
    private File file;
    private boolean active,started,untilQuiet,note,call,heardSpeech;
    private int source=-1;
    private long startedAt;
    private int index;
    private File folder;
    private long noteId;
    private final java.util.ArrayList<File> parts=new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> levels=new java.util.ArrayList<>();
    private static int[] lastBars=new int[0];
    private android.os.PowerManager.WakeLock wake;
    private static final long SEGMENT=3*60*1000L;

    private VoiceTake(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;}

    static boolean running(){return current!=null && current.active;}
    static boolean noteRunning(){return current!=null && current.note && current.active;}
    static boolean callRunning(){return current!=null && current.call && current.active;}
    /** True only when the phone actually gave us the call audio, not just the microphone. */
    static boolean bothSides(){return current!=null && current.source==MediaRecorder.AudioSource.VOICE_CALL;}
    static long elapsed(){return current==null?0:android.os.SystemClock.elapsedRealtime()-current.startedAt;}
    /** Bars for the whole take, 0 to 9. Safe to call from the finish callback. */
    static int[] bars(){return lastBars;}
    /** The latest bars while a note is still recording. */
    static int[] liveBars(int count){
        if(current==null)return new int[0];
        java.util.ArrayList<Integer> src=current.levels;
        int n=Math.min(count,src.size());
        int[] out=new int[n];
        for(int i=0;i<n;i++)out[i]=src.get(src.size()-n+i);
        return out;
    }

    static void begin(Context context,Listener listener) { begin(context,listener,false); }

    /** untilQuiet stops after a short pause once speech has started. Used by conversation mode. */
    static void begin(Context context,Listener listener,boolean untilQuiet) { open(context,listener,untilQuiet,true,null,0,false); }

    /** A note. No API key and no network. Segments land in folder as they finish so a long meeting is kept. */
    static void beginNote(Context context,Listener listener,File folder,long id) { open(context,listener,false,false,folder,id,false); }

    /** A phone call. Tries the call audio first, then the microphone, and keeps going without a network. */
    static void beginCall(Context context,Listener listener,File folder,long id) { open(context,listener,false,false,folder,id,true); }

    private static void open(Context context,Listener listener,boolean untilQuiet,boolean needsKey,File folder,long id,boolean call) {
        if(current!=null && current.note && current.active){listener.failed("Finish the recording first.");return;}
        cancel();
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){listener.failed("Allow microphone access first.");return;}
        if(needsKey && Vault.get(context).isEmpty()){listener.failed("Add your API key in Settings first.");return;}
        current=new VoiceTake(context,listener);
        current.untilQuiet=untilQuiet;current.note=!needsKey;current.call=call;current.folder=folder;current.noteId=id;
        current.start();
    }

    static void finish(){if(current!=null)current.stop();}

    static void cancel(){if(current!=null && current.note && current.active)return;if(current!=null){current.drop();current=null;}}

    private void start() {
        beginWork();active=true;
        if(note)holdWake();
        executor.execute(()->{
            String failure=null;
            try {
                if(note){if(folder!=null && !folder.isDirectory())folder.mkdirs();file=new File(folder,noteId+"-"+(index++)+".m4a");}
                else file=File.createTempFile("voiceflow-take-",".m4a",context.getCacheDir());
                arm(file);
                started=true;startedAt=android.os.SystemClock.elapsedRealtime();
                handler.post(()->{if(active)listener.listening();if(active && untilQuiet)handler.postDelayed(quietPoll,200);if(active && note){handler.postDelayed(rotate,SEGMENT);handler.postDelayed(meter,200);}});
            } catch(Exception error) {
                failure=error.getMessage()==null?"Could not start the microphone.":error.getMessage();
            }
            if(failure!=null){final String message=failure;handler.post(()->{drop();if(current==this)current=null;listener.failed(message);});}
        });
    }

    private void arm(File target) throws Exception {
        int[] sources=source>=0?new int[]{source}:call?new int[]{MediaRecorder.AudioSource.VOICE_CALL,MediaRecorder.AudioSource.VOICE_COMMUNICATION,MediaRecorder.AudioSource.MIC}:new int[]{MediaRecorder.AudioSource.MIC};
        Exception last=null;
        for(int pick:sources){
            MediaRecorder next=null;
            try {
                next=Build.VERSION.SDK_INT>=31?new MediaRecorder(context):new MediaRecorder();
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
                if(target.exists())target.delete();
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
                File done=seal();
                if(done!=null)parts.add(done);
                if(!active)return;
                try {
                    arm(new File(folder,noteId+"-"+(index++)+".m4a"));
                    started=true;
                    java.util.List<File> ready=new java.util.ArrayList<>(parts);
                    handler.post(()->{if(active)listener.partReady(ready);});
                    handler.postDelayed(rotate,SEGMENT);
                } catch(Exception error) {
                    handler.post(VoiceTake.this::stop);
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

    private void stop() {
        if(!active)return;
        finalizing=true;active=false;handler.removeCallbacks(quietPoll);handler.removeCallbacks(rotate);handler.removeCallbacks(meter);
        int[] bars=pack(new java.util.ArrayList<>(levels));
        executor.execute(()->{
            File done=seal();
            if(done!=null)parts.add(done);
            releaseWake();
            lastBars=bars;
            java.util.List<File> ready=new java.util.ArrayList<>(parts);
            handler.post(()->{
                try{
                    if(current==this)current=null;
                    if(untilQuiet && !heardSpeech){for(File silent:ready)silent.delete();listener.failed("Nothing was recorded.");return;}
                    if(ready.isEmpty())listener.failed("Nothing was recorded.");else listener.finishedAll(ready);
                }finally{completeWork();}
            });
        });
    }

    private File seal() {
        File done=file;file=null;
        try { if(started && recorder!=null) recorder.stop(); } catch(RuntimeException error) { if(done!=null && done.length()==0) done=null; }
        MediaRecorder currentRecorder=recorder;recorder=null;started=false;
        if(currentRecorder!=null)try{currentRecorder.release();}catch(Exception ignored){}
        return done!=null && done.length()>0?done:null;
    }

    private void drop() {
        active=false;handler.removeCallbacks(quietPoll);handler.removeCallbacks(rotate);handler.removeCallbacks(meter);
        executor.execute(()->{
            try{try{if(started && recorder!=null)recorder.stop();}catch(RuntimeException ignored){}release();if(!note && file!=null)file.delete();file=null;parts.clear();releaseWake();}
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

    private void release(){MediaRecorder currentRecorder=recorder;recorder=null;started=false;if(currentRecorder!=null)try{currentRecorder.release();}catch(Exception ignored){}}
}
