package app.voiceflow;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Uses actual microphone files and intercepted HTTPS to check interrupted cloud recovery. */
public final class RecoveryTest extends Instrumentation {
    private static volatile String responseText;
    private static volatile int uploads;
    private static volatile int transcriptionAttempts;
    private static volatile int cleanupRequests;
    private static volatile int recapRequests,dictationCleanupRequests,otherChatRequests;
    private static volatile boolean privateUpload;
    private Activity recorder;
    private Dictation privateRecorder;
    private volatile boolean watchingMain;
    private volatile long lastMainBeat;
    private volatile String currentPhase="Preparing recovery checks";
    private volatile String mainRecorderStack="";
    private Thread mainWatchdog;
    private final Object mockLock=new Object();
    private final org.json.JSONArray mockEvents=new org.json.JSONArray();
    private final org.json.JSONObject phaseResponseCategories=new org.json.JSONObject();
    private int nextMockId,mockEventsDropped;
    private final Handler watchdogHandler=new Handler(Looper.getMainLooper());
    private final Runnable heartbeat=new Runnable(){public void run(){if(watchingMain){lastMainBeat=SystemClock.elapsedRealtime();watchdogHandler.postDelayed(this,200);}}};
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle report=new Bundle();Context context=getTargetContext();android.content.SharedPreferences prefs=Store.prefs(context);Map<String,?> saved=prefs.getAll();String oldKey=Vault.get(context),oldDraft=MainActivity.currentDraft;uploads=0;transcriptionAttempts=0;cleanupRequests=0;recapRequests=0;dictationCleanupRequests=0;otherChatRequests=0;privateUpload=false;try{
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Mock(url);}}:null);
        // clipboard() opens MainActivity; its default-on recaps must not contaminate recording request counts.
        Vault.set(context,"fixture-only-key");prefs.edit().putString("engine","cloud").putString("api_base",CloudApi.DEFAULT_BASE).putString("transcribe_model","whisper-large-v3").putString("dictionary","").putString("history","[]").remove("history_summaries").putBoolean("daily_summaries",false).putBoolean("ai_polish",false).putBoolean("save_history",true).commit();
        check(!Store.bool(context,"daily_summaries",true),"Could not isolate RecoveryTest from automatic daily recap requests");
        startMainWatchdog();progress("Starting close recovery");responseText="Recovered after closing";startRecorder();progress("Recording before close");Thread.sleep(2000);progress("Calling Close on cloud recorder");runOnMainSync(()->{View button=find(recorder.getWindow().getDecorView(),"Close");checkUnchecked(button!=null,"Close button missing");button.performClick();});progress("Close returned; waiting for detached transcript");waitSaved(responseText);progress("Close recovered");check(clipboard().equals(responseText),"Closing canceled transcription instead of copying it");
        // This legacy Activity deliberately finalizes when its UI backgrounds;
        // active input dictation is owned by the IME/accessibility service instead.
        progress("Starting legacy Activity background recovery");responseText="Recovered after Activity backgrounds";startRecorder();Thread.sleep(2000);shell("input keyevent 26");waitSaved(responseText);shell("input keyevent 224");shell("wm dismiss-keyguard");Thread.sleep(500);check(clipboard().equals(responseText),"Legacy Activity background finalization lost transcription");runOnMainSync(()->recorder.finish());
        check(uploads==2,"Interrupted recordings did not each transcribe exactly once: "+mockCounts());
        // Retired: Store.engine is cloud-only. System/FakeSpeech timeout and
        // empty-result cases cannot be selected through production settings.
        progress("Checking private detached dictation");checkPrivateRecovery(context,prefs);
        progress("Checking cloud worker lifecycle");checkCloudLifecycle(context,prefs);
        check(mainRecorderStack.isEmpty(),"Recorder hardware ran on the main thread: "+mainRecorderStack);
        report.putString("result","PASS: closing the legacy recorder Activity or backgrounding its UI finalizes actual M4A recordings through mock Whisper HTTPS exactly once; private detached dictation omits dictionary/AI cleanup, keeps raw words only in memory until explicit private copy, and leaves History/draft sentinels unchanged; cloud hardware stays off main, immediate Finish and startup cancellation return promptly, deliver once or cancel safely, and delete their audio files. Active-input app-switch persistence is covered separately by InputRecoveryTest");
    }catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));}finally{
        stopMainWatchdog();
        try{shell("input keyevent 224");shell("wm dismiss-keyguard");}catch(Exception ignored){}if(privateRecorder!=null)runOnMainSync(()->privateRecorder.close());if(recorder!=null)runOnMainSync(()->recorder.finish());privateUpload=false;MainActivity.currentDraft=oldDraft;try{Vault.set(context,oldKey);}catch(Exception ignored){}
        android.content.SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();if(value instanceof String)edit.putString(entry.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Integer)edit.putInt(entry.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(entry.getKey(),(Float)value);}edit.commit();
        report.putString("phase",currentPhase);report.putString("mock_counts",mockCounts());
        synchronized(mockLock){report.putString("mock_request_events",mockEvents.toString());report.putInt("mock_events_dropped",mockEventsDropped);report.putString("mock_response_categories_by_phase",phaseResponseCategories.toString());}
    }finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);}
    private void startRecorder()throws Exception{boolean cloud=Store.get(getTargetContext(),"engine","system").equals("cloud");recorder=startActivitySync(new Intent().setClassName("app.voiceflow","app.voiceflow.CaptureActivity").putExtra("destination","app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();if(cloud){long end=SystemClock.elapsedRealtime()+30000;boolean[] listening={false};while(SystemClock.elapsedRealtime()<end){runOnMainSync(()->listening[0]=findLabel(recorder.getWindow().getDecorView(),"Listening…")!=null);if(listening[0])break;Thread.sleep(100);}check(listening[0],"Recorder never finished microphone setup");}else Thread.sleep(400);boolean[] reviewed={false};if(!cloud)runOnMainSync(()->reviewed[0]=find(recorder.getWindow().getDecorView(),"Keep text")!=null);check(Dictation.isWorking() || reviewed[0],"Recording session is missing from the restore guard");}
    private void checkPrivateRecovery(Context context,android.content.SharedPreferences prefs)throws Exception{
        String beforeClip=clipboard();PrivatePending.discard();String history=Store.history(context).toString();String draft="Private draft sentinel";MainActivity.currentDraft=draft;
        check(!Store.bool(context,"daily_summaries",true),"Daily recaps unexpectedly enabled before private recovery");
        int priorUploads=uploads,priorAttempts=transcriptionAttempts,priorChat=cleanupRequests;
        prefs.edit().putString("engine","cloud").putString("language","en-US").putString("dictionary","heard=Private Dictionary Hint").putBoolean("clean",true).putBoolean("ai_polish",true).putBoolean("save_history",true).commit();
        responseText="  um spoken secret  ";privateUpload=true;String[] error={null};CountDownLatch ready=new CountDownLatch(1);
        runOnMainSync(()->{privateRecorder=new Dictation(context,new Dictation.Listener(){public void state(String value){if(value.equals("Listening…"))ready.countDown();}public void partial(String value){}public void level(float value){}public void result(String raw,String text,String warning){}public void error(String value){error[0]=value;ready.countDown();}},true);privateRecorder.start();});
        check(ready.await(30,TimeUnit.SECONDS) && error[0]==null,"Private microphone setup failed: "+error[0]);check(Dictation.isWorking(),"Private recording did not hold the restore guard");Thread.sleep(2000);runOnMainSync(()->privateRecorder.finishDetached(2000));
        long end=SystemClock.elapsedRealtime()+20000;while(Dictation.isWorking() && SystemClock.elapsedRealtime()<end)Thread.sleep(100);
        check(!Dictation.isWorking() && error[0]==null,"Private recording did not finish: "+error[0]);check(uploads==3 && transcriptionAttempts==3 && cleanupRequests==0,"Private recording made duplicate transcription or AI cleanup requests: "+mockCounts()+"; baseline uploads="+priorUploads+", attempts="+priorAttempts+", chat="+priorChat);
        check(uploads-priorUploads==1 && transcriptionAttempts-priorAttempts==1 && cleanupRequests-priorChat==0,"Private phase must make exactly one transcription and zero cleanup requests: "+mockCounts());
        check(PrivatePending.available(),"Private detached words were not held in memory for explicit recovery");
        check(clipboard().equals(beforeClip),"Private dictation was copied before an explicit user action");
        check(history.equals(Store.history(context).toString()) && draft.equals(MainActivity.currentDraft),"Private recovery changed History or the app draft");
        runOnMainSync(()->checkUnchecked(PrivatePending.copy(context),"Explicit private recovery failed"));
        check(clipboard().equals(responseText),"Explicit private recovery cleaned or lost the raw spoken words");
        boolean[] sensitive={false};runOnMainSync(()->{ClipData clip=((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();sensitive[0]=clip!=null && clip.getDescription().getExtras()!=null && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false);Recovery.keep(context,"inserted private sentinel","inserted private sentinel",1,true,true);});
        check(sensitive[0],"Explicit private clipboard lacks the sensitive flag");check(!PrivatePending.available(),"Successful private insertion unexpectedly created a private recovery item");check(clipboard().equals(responseText) && history.equals(Store.history(context).toString()) && draft.equals(MainActivity.currentDraft),"Successful private insertion changed clipboard, History or draft");privateUpload=false;
    }
    private void checkCloudLifecycle(Context context,android.content.SharedPreferences prefs)throws Exception{
        prefs.edit().putString("engine","cloud").putBoolean("ai_polish",false).putBoolean("save_history",false).putString("dictionary","").commit();responseText="Worker lifecycle sentinel";
        int beforeAttempts=transcriptionAttempts,beforeAudio=audioFiles(context);int[] terminals={0};boolean[] callbacksMain={true};long[] callTime={0};
        runOnMainSync(()->{privateRecorder=new Dictation(context,new Dictation.Listener(){private void thread(){callbacksMain[0]&=Looper.myLooper()==Looper.getMainLooper();}public void state(String value){thread();}public void partial(String value){thread();}public void level(float value){thread();}public void result(String raw,String text,String warning){thread();terminals[0]++;privateRecorder.close();}public void error(String value){thread();terminals[0]++;privateRecorder.close();}});long start=SystemClock.elapsedRealtime();privateRecorder.start();checkUnchecked(!privateRecorder.isFinishing(),"Microphone setup disabled early Finish");privateRecorder.stop();checkUnchecked(privateRecorder.isFinishing(),"Finish did not enter the processing state");callTime[0]=SystemClock.elapsedRealtime()-start;});
        check(callTime[0]<1000,"Immediate cloud Start/Finish blocked main for "+callTime[0]+"ms");waitWorkerSettled();check(terminals[0]==1 && callbacksMain[0],"Early Finish did not deliver exactly one main-thread terminal callback");check(transcriptionAttempts-beforeAttempts<=1,"Early Finish uploaded the same recording more than once");check(audioFiles(context)==beforeAudio,"Early Finish left its temporary audio file");
        int canceledAttempts=transcriptionAttempts;terminals[0]=0;
        runOnMainSync(()->{privateRecorder=new Dictation(context,new Dictation.Listener(){public void state(String value){callbacksMain[0]&=Looper.myLooper()==Looper.getMainLooper();}public void partial(String value){}public void level(float value){}public void result(String raw,String text,String warning){terminals[0]++;}public void error(String value){terminals[0]++;}});long start=SystemClock.elapsedRealtime();privateRecorder.start();privateRecorder.close();callTime[0]=SystemClock.elapsedRealtime()-start;});
        check(callTime[0]<1000,"Cloud startup cancellation blocked main for "+callTime[0]+"ms");waitWorkerSettled();Thread.sleep(350);check(terminals[0]==0 && transcriptionAttempts==canceledAttempts,"Canceled startup delivered late words or uploaded audio");check(audioFiles(context)==beforeAudio && callbacksMain[0],"Canceled startup leaked audio or called listeners from its worker");
    }
    private int audioFiles(Context context){File[] files=context.getCacheDir().listFiles();int count=0;if(files!=null)for(File file:files)if(file.getName().startsWith("voiceflow-") && file.getName().endsWith(".m4a"))count++;return count;}
    private void waitWorkerSettled()throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(Dictation.isWorking() && SystemClock.elapsedRealtime()<end)Thread.sleep(100);check(!Dictation.isWorking(),"Canceled or finished cloud worker kept the restore guard active");}
    private void progress(String value){currentPhase=value;Bundle state=new Bundle();state.putString("phase",value);state.putString("mock_counts",mockCounts());sendStatus(0,state);}
    private String mockCounts(){synchronized(mockLock){return "uploads="+uploads+", transcriptionAttempts="+transcriptionAttempts+", chatEndpointResponses="+cleanupRequests+", dailyRecapRequests="+recapRequests+", dictationCleanupRequests="+dictationCleanupRequests+", otherChatRequests="+otherChatRequests;}}
    private void recordMock(Mock request,String stage,String category){synchronized(mockLock){try{
        if(mockEvents.length()<200)mockEvents.put(new org.json.JSONObject().put("request_id",request.id).put("stage",stage).put("category",category)
                .put("opened_phase",request.openedPhase).put("observed_phase",currentPhase).put("private_phase_at_open",request.privateAtOpen)
                .put("path",request.getURL().getPath()).put("body_bytes",request.out.size()).put("uptime_ms",SystemClock.uptimeMillis()));
        else mockEventsDropped++;
        if(stage.equals("response_code")){
            org.json.JSONObject categories=phaseResponseCategories.optJSONObject(request.openedPhase);
            if(categories==null){categories=new org.json.JSONObject();phaseResponseCategories.put(request.openedPhase,categories);}
            categories.put(category,categories.optInt(category)+1);
        }
    }catch(org.json.JSONException failure){throw new IllegalStateException("Could not record mock request diagnostics",failure);}}}
    private void startMainWatchdog(){watchingMain=true;lastMainBeat=SystemClock.elapsedRealtime();watchdogHandler.post(heartbeat);mainWatchdog=new Thread(()->{long previousBeat=-1;int reports=0;while(watchingMain){try{Thread.sleep(250);}catch(InterruptedException stopped){return;}Thread main=Looper.getMainLooper().getThread();StackTraceElement[] frames=main.getStackTrace();for(StackTraceElement frame:frames)if(frame.getClassName().equals("android.media.MediaRecorder"))mainRecorderStack=frame+" phase="+currentPhase;long beat=lastMainBeat,idle=SystemClock.elapsedRealtime()-beat;if(idle<1500 || beat==previousBeat || reports>=12)continue;previousBeat=beat;reports++;StringBuilder stack=new StringBuilder("Main thread state=").append(main.getState()).append(" heartbeat stalled=").append(idle).append("ms phase=").append(currentPhase).append('\n');for(StackTraceElement frame:frames)stack.append(" at ").append(frame).append('\n');Bundle state=new Bundle();state.putString("ui_blocked_stack",stack.toString());sendStatus(0,state);}},"Recovery main-thread watchdog");mainWatchdog.setDaemon(true);mainWatchdog.start();}
    private void stopMainWatchdog(){watchingMain=false;watchdogHandler.removeCallbacks(heartbeat);if(mainWatchdog!=null)mainWatchdog.interrupt();}
    private void waitReview()throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(find(recorder.getWindow().getDecorView(),"Keep text")!=null)return;Thread.sleep(100);}throw new Exception("Speech did not finish into a useful review");}
    private void waitSaved(String text)throws Exception{long end=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<end){org.json.JSONArray history=Store.history(getTargetContext());for(int i=0;i<history.length();i++)if(text.equals(history.getJSONObject(i).optString("text")) && !Dictation.isWorking())return;Thread.sleep(100);}throw new Exception("Interrupted transcript was not recovered and finished: "+text);}
    private String clipboard(){startActivitySync(new Intent().setClassName("app.voiceflow","app.voiceflow.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();String[] text={""};runOnMainSync(()->{ClipboardManager manager=(ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);ClipData clip=manager.getPrimaryClip();if(clip!=null && clip.getItemCount()>0)text[0]=clip.getItemAt(0).coerceToText(getTargetContext()).toString();});return text[0];}
    private void shell(String command)throws Exception{try(ParcelFileDescriptor.AutoCloseInputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command))){byte[] buffer=new byte[1024];while(stream.read(buffer)!=-1){}}}
    private View find(View view,String text){if(view instanceof Button && text.contentEquals(((Button)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private View findLabel(View view,String text){if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findLabel(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    private void checkUnchecked(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private final class Mock extends HttpsURLConnection {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();final int id;final String openedPhase;final boolean privateAtOpen;
        Mock(URL url){super(url);openedPhase=currentPhase;privateAtOpen=privateUpload;synchronized(mockLock){id=++nextMockId;}recordMock(this,"open",category());}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
        private String category(){
            if(getURL().getPath().endsWith("transcriptions"))return privateAtOpen?"private_transcription":"transcription";
            if(getURL().getPath().endsWith("chat/completions"))try{
                String system=new org.json.JSONObject(out.toString("UTF-8")).getJSONArray("messages").getJSONObject(0).optString("content","");
                if(system.startsWith("Summarize the user's saved dictations"))return "daily_recap";
                if(system.startsWith("You edit dictated text."))return "dictation_cleanup";
            }catch(Exception ignored){}
            return getURL().getPath().endsWith("chat/completions")?"other_chat":"other_endpoint";
        }
        public OutputStream getOutputStream(){synchronized(mockLock){if(getURL().getPath().endsWith("transcriptions"))transcriptionAttempts++;recordMock(this,"output_stream",category());}return out;}
        public int getResponseCode(){String kind=category();synchronized(mockLock){if(getURL().getPath().endsWith("chat/completions")){cleanupRequests++;if(kind.equals("daily_recap"))recapRequests++;else if(kind.equals("dictation_cleanup"))dictationCleanupRequests++;else otherChatRequests++;}recordMock(this,"response_code",kind);}return 200;}
        public InputStream getInputStream()throws IOException{String payload=out.toString("UTF-8");recordMock(this,"input_stream",category());if(!getURL().getPath().endsWith("transcriptions") || out.size()<1000 || !payload.contains("whisper-large-v3"))throw new IOException("Recording upload/model missing");if(privateUpload && (payload.contains("name=\"prompt\"") || payload.contains("name=\"keywords[]\"") || payload.contains("Private Dictionary Hint")))throw new IOException("Private transcription exposed dictionary context");synchronized(mockLock){uploads++;recordMock(this,"validated_upload",category());}try{Thread.sleep(700);}catch(InterruptedException error){throw new IOException("Interrupted upload",error);}return new ByteArrayInputStream(("{\"text\":\""+responseText+"\"}").getBytes("UTF-8"));}
    }
}
