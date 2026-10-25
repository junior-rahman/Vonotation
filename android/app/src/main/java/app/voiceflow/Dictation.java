package app.voiceflow;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Dictation {
    private static int workingSessions;
    /** Includes detached recording, transcription and cleanup until delivery or cancellation. */
    static synchronized boolean isWorking(){return workingSessions>0;}
    interface Listener {
        void state(String status);
        void partial(String text);
        void level(float level);
        void result(String raw,String text,String warning);
        void error(String message);
    }
    private final Context context;
    private final boolean sensitive;
    private final boolean thought;
    private Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private SpeechRecognizer recognizer;
    // Hardware and audio files belong only to executor. Main never stops,
    // releases or samples a recorder, including during activity teardown.
    private MediaRecorder recorder;
    private File file;
    private volatile File recoveryAudio;
    private boolean sizeLimited;
    static final long MAX_CAPTURE_BYTES=20_000_000L;
    private boolean recorderStarted,recorderFailed;
    private volatile CloudApi api;
    private volatile boolean closed;
    private boolean active, stopping, processing, countedWorking,cloudRecording;
    private CaptureGate.Lease captureLease;
    private final RecordingAwake awake;
    private String committed="", partial="";
    Dictation(Context c,Listener listener) { this(c,listener,false); }
    Dictation(Context c,Listener listener,boolean sensitive) { this(c,listener,sensitive,false); }
    Dictation(Context c,Listener listener,boolean sensitive,boolean thought) { context=c; this.listener=listener;this.sensitive=sensitive;this.thought=thought;awake=new RecordingAwake(c); }
    boolean isFinishing(){return stopping || processing;}
    boolean isCapturing(){return active&&!stopping&&!processing&&!closed;}
    void start() {
        if(active || closed) return;
        if(isWorking()){listener.error("Finish your current dictation first.");return;}
        if(VoiceTake.updateBusy()||Translate.going()||LineWire.updateBusy()){listener.error("Finish the other recording first.");return;}
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) { listener.error("Allow microphone access first."); return; }
        // Restore and a new recording must not cross between its busy check and
        // applying preferences. Keep UI and recorder work outside this gate.
        synchronized(Store.class){if(active || closed)return;captureLease=CaptureBarrier.begin(context);if(captureLease==null){listener.error("Finish or cancel the app update before recording.");return;}active=true;markWorking(true);}
        awake.start();
        cloudRecording=Store.engine(context).equals("cloud");listener.state(cloudRecording?"Starting microphone…":"Listening…");
        // Input focus, app visibility, silence and screen timeout do not finish a take.
        // Its originating UI owns Finish/Cancel; hardware/permission failures still recover normally.
        if(cloudRecording) startRecorder(); else startSpeech();
    }
    private void startRecorder() {
        executor.execute(()->{
            boolean ready=false;String failure=null;recorderFailed=false;
            try {
                if(closed)return;
                if(Vault.get(context).isEmpty())throw new Exception("Add your API key in Settings to use cloud transcription.");
                if(closed)return;
                file=File.createTempFile("voiceflow-",".m4a",context.getCacheDir());
                recorder=Build.VERSION.SDK_INT>=31?new MediaRecorder(context):new MediaRecorder();
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);recorder.setAudioSamplingRate(44100);recorder.setAudioEncodingBitRate(64000);recorder.setOutputFile(file.getAbsolutePath());recorder.setMaxFileSize(MAX_CAPTURE_BYTES);recorder.setOnInfoListener((source,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)handler.post(()->{sizeLimited=true;stop();});});recorder.prepare();
                if(closed)return;
                recorder.start();recorderStarted=true;
                if(closed)return;
                ready=true;handler.post(()->{if(!closed && active && !stopping){listener.state("Listening…");handler.post(amplitude);}});
            }catch(Exception error){recorderFailed=true;failure=error.getMessage()==null?"Could not start microphone recording.":error.getMessage();}
            finally{if(!ready)cleanRecorder();}
            if(failure!=null){final String message=failure;handler.post(()->fail(message));}
        });
    }
    /** One sample can be queued at a time; finishing removes the next sample. */
    private final Runnable amplitude=new Runnable(){public void run(){
        if(closed || !active || stopping || processing)return;
        executor.execute(()->{
            float value=0;try{if(!closed && recorderStarted && recorder!=null)value=recorder.getMaxAmplitude()/18000f;}catch(Exception ignored){}
            final float level=value;handler.post(()->{if(!closed && active && !stopping && !processing){listener.level(level);handler.postDelayed(this,70);}});
        });
    }};
    private void releaseRecorder(){MediaRecorder current=recorder;recorder=null;recorderStarted=false;if(current!=null)try{current.release();}catch(Exception ignored){}}
    private void cleanRecorder(){releaseRecorder();if(file!=null){file.delete();file=null;}}
    private void startSpeech() {
        boolean offline=Store.engine(context).equals("offline");
        if(offline && (Build.VERSION.SDK_INT<31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))) { fail("On-device speech is unavailable here. Choose Android speech in Settings."); return; }
        if(!offline && !SpeechRecognizer.isRecognitionAvailable(context)) { fail("No Android speech engine is installed. Install or enable Google Speech Services, or choose cloud transcription."); return; }
        try {
            recognizer=offline ? SpeechRecognizer.createOnDeviceSpeechRecognizer(context) : SpeechRecognizer.createSpeechRecognizer(context);
            recognizer.setRecognitionListener(new RecognitionListener() {
                public void onReadyForSpeech(Bundle p) { if(!closed) listener.state("Listening…"); }
                public void onBeginningOfSpeech() {}
                public void onRmsChanged(float rms) { if(!closed) listener.level((rms+2)/12f); }
                public void onBufferReceived(byte[] b) {}
                public void onEndOfSpeech() { if(stopping && !closed) listener.state("Finishing…"); }
                public void onError(int error) {
                    if(closed || !active || processing) return;
                    if(stopping) { complete(join(committed,partial)); return; }
                    if(error==SpeechRecognizer.ERROR_NO_MATCH || error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        // A recognizer can emit useful partial words and then no final
                        // result. Retain that segment before starting another session.
                        if(!partial.trim().isEmpty()){committed=join(committed,partial);partial="";listener.partial(committed);}
                        // A pause is a new recognizer segment, never the end of dictation.
                        handler.postDelayed(()->listen(),350);
                    } else {
                        String captured=join(committed,partial);
                        if(!captured.trim().isEmpty()) { if(recognizer!=null) { recognizer.destroy(); recognizer=null; } deliver(captured,sensitive?captured:Store.clean(context,captured),speechError(error)+" Your captured words were kept."); }
                        else fail(speechError(error));
                    }
                }
                public void onResults(Bundle results) {
                    if(closed || !active || processing) return;
                    // Some engines send an empty final after useful partial words.
                    // Commit the last useful segment before another listen starts.
                    String text=first(results); committed=join(committed,text.trim().isEmpty()?partial:text); partial=""; listener.partial(committed);
                    if(stopping) complete(committed); else handler.postDelayed(()->listen(),180);
                }
                public void onPartialResults(Bundle results) { if(!closed && active && !processing) { String text=first(results);if(!text.trim().isEmpty()){partial=text;listener.partial(join(committed,partial));} } }
                public void onEvent(int type,Bundle p) {}
            });
            listen();
        } catch(Exception e) { fail("Could not start the speech engine. Check your speech and microphone settings."); }
    }
    private void listen() {
        if(closed || !active || stopping || processing || recognizer==null) return;
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH); intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true); intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
        if(Build.VERSION.SDK_INT>=33)intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS,false);
        if(Build.VERSION.SDK_INT>=34) {intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,true);intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION);}
        if(Store.engine(context).equals("offline")) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        try { recognizer.startListening(intent); } catch(Exception e) { fail("The speech engine is busy. Try again."); }
    }
    void stop() {
        if(!active || stopping || processing) return; stopping=true;awake.close();
        if(cloudRecording) {
            processing=true;handler.removeCallbacks(amplitude);listener.state("Saving recording…");
            // An early Finish waits behind setup on the same worker, so it can
            // neither overtake startup nor lose the hardware/file owner.
            executor.execute(()->{
                String result=null,message=null;boolean finalized=false;
                try{
                    if(closed || recorderFailed)return;
                    if(recorder==null || !recorderStarted)throw new IllegalStateException();
                    try{recorder.stop();}catch(RuntimeException stoppedAtLimit){if(!sizeLimited||file==null||file.length()==0)throw stoppedAtLimit;}
                    releaseRecorder();finalized=true;
                    if(closed)return;
                    File source=file;
                    if(!sensitive){
                        File kept=RetryBuffer.keep(context,file,thought);
                        if(kept==null)throw new java.io.IOException("Could not save a recovery copy. Free some storage and try again.");
                        recoveryAudio=kept;RetryBuffer.pin(kept);file=null;source=kept;
                    }
                    CloudApi request=new CloudApi(context);api=request;
                    if(closed){request.cancel();return;}
                    stage("Uploading audio…");
                    result=request.transcribe(source,sensitive || Store.verbatim(context),()->stage("Transcribing…"));
                    if(!sensitive)RetryBuffer.saveTranscript(source,result);
                }catch(Exception error){
                    message=finalized?safe(error):"The recording was too short. Speak for a moment, then try again.";
                    // A cloud failure must not lose the recording. Keep a temporary private
                    // copy for Retry; private credential audio is never retained.
                    if(finalized && !closed && !sensitive && file!=null){
                        File kept=RetryBuffer.keep(context,file,thought);
                        if(kept!=null){file=null;recoveryAudio=kept;}
                    }
                    if(recoveryAudio!=null)message+=" Your audio is kept for 24 hours. Open Voitation to retry or export it.";
                }
                finally{cleanRecorder();}
                if(closed)return;
                if(message!=null){final String failure=message;handler.post(()->fail(failure));}
                else{final String raw=result;handler.post(()->{processing=false;complete(raw);});}
            });
        } else if(recognizer!=null) {
            listener.state("Finishing…"); recognizer.stopListening();
            handler.postDelayed(()->{ if(active && !processing && !closed) complete(join(committed,partial)); },4000);
        }
    }
    /** Posts a processing stage from the worker; ignored after cancel/close. */
    private void stage(String value){handler.post(()->{if(!closed && active)listener.state(value);});}
    /**
     * Retries a buffered cloud recording through the normal completion pipeline. The
     * buffered file is removed only after the destination acknowledges a durable save.
     */
    void retry(File audio){
        if(active || closed || audio==null)return;
        synchronized(Store.class){if(active || closed)return;captureLease=CaptureBarrier.begin(context);if(captureLease==null){listener.error("Finish or cancel the app update before retrying.");return;}active=true;markWorking(true);}
        recoveryAudio=audio;RetryBuffer.pin(audio);
        cloudRecording=true;stopping=true;processing=true;listener.state("Uploading audio…");
        executor.execute(()->{
            String result=null,message=null;
            try{
                if(closed)return;
                recoveryAudio=audio;
                result=RetryBuffer.transcript(audio);
                if(result.trim().isEmpty()){
                    CloudApi request=new CloudApi(context);api=request;
                    result=request.transcribe(audio,Store.verbatim(context),()->stage("Transcribing…"));
                    RetryBuffer.saveTranscript(audio,result);
                }
            }catch(Exception error){message=safe(error)+" The recording is still kept for another try.";}
            if(closed)return;
            if(message!=null){final String failure=message;handler.post(()->fail(failure));}
            else{final String raw=result;handler.post(()->{processing=false;complete(raw);});}
        });
    }
    /** A successful durable save or acknowledged insertion releases the recovery source. */
    void acknowledgeSaved(){File source=recoveryAudio;recoveryAudio=null;if(source!=null){RetryBuffer.discard(source);RetryBuffer.unpin(source);}}
    /** Cancel stops delivery; an already sealed recovery source remains available. */
    void cancel(){close();}
    private void complete(String raw) {
        if(closed || !active || processing) return;
        if(raw.trim().isEmpty()) { fail("No speech was captured. Please try again."); return; }
        processing=true; if(recognizer!=null) { recognizer.destroy(); recognizer=null; }
        if(sensitive || thought){deliver(raw,raw,"");return;}
        // True verbatim: no cleanup, punctuation commands, dictionary or AI processing.
        if(Store.verbatim(context)){deliver(raw,Cleaner.verbatim(raw),"");return;}
        String clean=Store.clean(context,raw);
        boolean cleanup=Store.bool(context,"ai_polish",false);
        boolean romanize=Store.romanize(context) && LanguagePolicy.needsRomanization(clean);
        if(cleanup || romanize) {
            if(Store.engine(context).equals("offline")) { deliver(raw,clean,"AI text processing is disabled in on-device mode. Your original words were kept."); return; }
            if(Vault.get(context).isEmpty()) { deliver(raw,clean,"Connect your AI provider to use English letters or AI cleanup. Your original words were kept."); return; }
            listener.state(romanize?"Writing in English letters…":"Polishing your words…");
            CloudApi request=new CloudApi(context);api=request;
            executor.execute(()->{ try { String text=request.transform(clean,cleanup,romanize); handler.post(()->deliver(raw,text,"")); } catch(Exception e) { handler.post(()->deliver(raw,clean,"Text processing failed. Your original words are safe. "+safe(e))); } });
        } else deliver(raw,clean,"");
    }
    private void deliver(String raw,String text,String warning) { if(closed) return; active=false; processing=false;awake.close();handler.removeCallbacksAndMessages(null);try{listener.result(raw,text,sizeLimited?"Recording finished at its safe size limit. "+warning:warning);}finally{RetryBuffer.unpin(recoveryAudio);markWorking(false);} }
    private void fail(String message) {
        if(closed) return; active=false; processing=false;awake.close();handler.removeCallbacksAndMessages(null);
        if(recognizer!=null) { recognizer.destroy(); recognizer=null; }
        try{listener.error(message==null ? "Dictation failed. Please try again." : message);}finally{RetryBuffer.unpin(recoveryAudio);markWorking(false);}
    }
    private static String first(Bundle results) { ArrayList<String> list=results==null?null:results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION); return list==null || list.isEmpty() || list.get(0)==null ? "" : list.get(0); }
    private static String join(String a,String b) { return a.isEmpty()?b:(b.isEmpty()?a:a+" "+b); }
    private static String safe(Exception e) { if(e instanceof java.io.IOException && e.getMessage()!=null) return e.getMessage(); return "The request failed. Check your connection and provider settings."; }
    private static String speechError(int code) {
        switch(code) {
            case 1: case 2: return "The speech engine needs a connection. Try again, or use on-device mode.";
            case 3: return "Microphone audio failed. Close other recording apps and try again.";
            case 6: case 7: return "No speech was heard. Please try again.";
            case 9: return "Microphone access was denied. Allow it in Android app settings.";
            case 12: case 13: return "The phone cannot recognize this language. Download its speech pack or choose Cloud transcription.";
            case 8: return "The speech engine is busy. Wait a moment and try again.";
            default: return "Speech recognition failed ("+code+"). Try another speech mode in Settings.";
        }
    }
    private void markWorking(boolean value){
        boolean changed=false;
        synchronized(Dictation.class){if(countedWorking!=value){countedWorking=value;workingSessions+=value?1:-1;changed=true;}}
        if(!value){CaptureGate.Lease lease=captureLease;captureLease=null;if(lease!=null)lease.close();}
        // Async so the callback runs after recorder/store locks are released, including cancel/close.
        if(changed)handler.post(()->{if(FlowAccessibility.instance!=null)FlowAccessibility.instance.updateBubble();});
    }
    /** Finish the existing audio/upload even after its owning UI or input service goes away. */
    /** Retain a frozen destination when an owning UI leaves the screen. */
    void finishDetached(Listener detached){if(closed)return;listener=detached;stop();}
    void finishDetached(long duration){
        if(closed)return;
        final Context app=context.getApplicationContext();
        listener=new Listener(){
            public void state(String value){}public void partial(String value){}public void level(float value){}
            public void result(String raw,String text,String warning){
                if(thought){if(saveThought(app,raw,duration))acknowledgeSaved();}else if(Recovery.keep(app,raw,text,duration,false,sensitive))acknowledgeSaved();
                close();
            }
            public void error(String message){
                String raw=join(committed,partial);
                if(!raw.trim().isEmpty()){if(thought)saveThought(app,raw,duration);else Recovery.keep(app,raw,sensitive?raw:Store.clean(app,raw),duration,false,sensitive);}
                else android.widget.Toast.makeText(app,message,android.widget.Toast.LENGTH_LONG).show();
                close();
            }
        };
        stop();
    }
    private static boolean saveThought(Context app,String raw,long duration){
        long id=Notes.keepThought(app,System.currentTimeMillis(),raw,duration);
        if(id>0)Notes.formatThought(app,id);
        else android.widget.Toast.makeText(app,"Could not save your quick thought. Check available phone storage.",android.widget.Toast.LENGTH_LONG).show();
        return id>0;
    }
    void close() { if(closed)return;closed=true;active=false;awake.close();handler.removeCallbacksAndMessages(null);if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}CloudApi request=api;if(request!=null)request.cancel();
        // Never drop queued setup/stop/cleanup. A native call may still be in
        // progress; its worker must release the microphone and delete the file.
        executor.execute(()->{cleanRecorder();RetryBuffer.unpin(recoveryAudio);markWorking(false);});executor.shutdown();
    }
}
