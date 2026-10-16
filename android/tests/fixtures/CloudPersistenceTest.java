package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.cert.Certificate;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Current cloud contract: actual microphone capture, synthetic text, intercepted HTTPS only. */
public final class CloudPersistenceTest extends Instrumentation {
    private UiAutomation ui;
    private Context app;
    private final String sentinel="CloudPersistence "+java.util.UUID.randomUUID().toString();
    private final java.util.Set<String> ownedClips=new java.util.HashSet<>();
    private final String beforeOrdinary=sentinel+" clipboard before recording",beforePrivate=sentinel+" clipboard before private recording";
    private volatile String answer=sentinel+" complete dictation after returning";
    private long archiveFloor;
    private boolean archiveTracked;
    private volatile int uploads;
    private volatile boolean privateCapture;
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle report=new Bundle();app=getTargetContext();
        android.content.SharedPreferences prefs=Store.prefs(app);Map<String,?> saved=prefs.getAll();String key=Vault.get(app),draft=MainActivity.currentDraft;
        try{
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url){return new Mock(url);}}:null);
            require(!Dictation.isWorking()&&!VoiceTake.running()&&!Notes.updateBusy(),"Another capture or note worker is active");
            require(FlowAccessibility.instance!=null,"Enable VonoTation Floating microphone before this fixture");
            // Exact unique text plus an ID boundary prevents cleanup touching an
            // existing user copy, even if a previous fixture run was interrupted.
            try(android.database.Cursor row=ClipboardArchive.get(app).getReadableDatabase().rawQuery("SELECT MAX(_id) FROM copies",null)){archiveFloor=row.moveToFirst()&&!row.isNull(0)?row.getLong(0):0;archiveTracked=true;}
            ownedClips.add(beforeOrdinary);ownedClips.add(beforePrivate);ownedClips.add(answer);
            Vault.set(app,"fixture-only-key");prefs.edit().putString("api_base",CloudApi.DEFAULT_BASE).putString("transcribe_model","whisper-large-v3").putString("dictionary","").putBoolean("ai_polish",false).putBoolean("daily_summaries",false).putBoolean("save_history",true).putBoolean("quick_thoughts",true).putBoolean("record_calls",false).putString("history","[]").putString("notes","[]").remove("history_summaries").commit();
            require(Store.engine(app).equals("cloud"),"Fixture must exercise the production cloud engine");
            runOnMainSync(()->PrivatePending.discard());
            ordinaryRoundTrip();privateRoundTrip();
            report.putString("result","PASS: "+checks+" current-cloud assertions; keyboard/input/app departure and return keep recording active until manual Finish; no upload or clipboard fallback occurs before Finish; the complete ordinary result reaches dictation History/clipboard without foreign insertion or Thoughts; private departure keeps the full final in memory until explicit sensitive copy and never saves it to History or Thoughts. Actual microphone files use intercepted HTTPS, not a real provider.");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{
            try{runOnMainSync(()->{if(FlowKeyboard.instance!=null&&FlowKeyboard.instance.isRecording())FlowKeyboard.instance.cancelDictation();if(FlowAccessibility.instance!=null&&FlowAccessibility.instance.isRecording())try{java.lang.reflect.Method cancel=FlowAccessibility.class.getDeclaredMethod("cancelProcessing");cancel.setAccessible(true);cancel.invoke(FlowAccessibility.instance);}catch(Exception ignored){}PrivatePending.discard();});}catch(Exception ignored){}
            try{cleanupOwnedClips();}catch(Throwable cleanup){report.putString("cleanup_failure",android.util.Log.getStackTraceString(cleanup));if(!report.containsKey("failure"))report.putString("failure","Could not remove this fixture's unique clipboard sentinel entries");}
            try{Vault.set(app,key);}catch(Exception ignored){}MainActivity.currentDraft=draft;
            android.content.SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();if(value instanceof String)edit.putString(entry.getKey(),(String)value);else if(value instanceof Boolean)edit.putBoolean(entry.getKey(),(Boolean)value);else if(value instanceof Integer)edit.putInt(entry.getKey(),(Integer)value);else if(value instanceof Long)edit.putLong(entry.getKey(),(Long)value);else if(value instanceof Float)edit.putFloat(entry.getKey(),(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(entry.getKey(),new java.util.HashSet<>((java.util.Set<String>)value));}edit.commit();
            report.putInt("uploads",uploads);
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void ordinaryRoundTrip()throws Exception{
        launchEditor();action("Primary input",AccessibilityNodeInfo.ACTION_FOCUS,null);setText("Primary input","Before after");
        waitFor(()->FlowKeyboard.instance!=null&&FlowKeyboard.instance.hasActiveEditor(),"Enable and select the VonoTation keyboard");
        setClipboard(beforeOrdinary);String notes=Store.get(app,"notes","[]");
        click("Floating keyboard microphone");waitFor(this::capturing,"Cloud microphone did not start");Thread.sleep(1700);
        runOnMainSync(()->FlowKeyboard.instance.requestHideSelf(0));assertCapturing("Keyboard hide");
        action("Second input",AccessibilityNodeInfo.ACTION_FOCUS,null);setText("Second input","Keep this");assertCapturing("Changing field");
        app.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ui.waitForIdle(300,10000);waitNode("Finish keyboard dictation").recycle();assertCapturing("Changing app");
        app.startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));ui.waitForIdle(300,10000);action("Primary input",AccessibilityNodeInfo.ACTION_FOCUS,null);assertCapturing("Returning to original field");
        require(text("Primary input").equals("Before after")&&text("Second input").equals("Keep this"),"Cloud recording modified a field before Finish");
        require(uploads==0,"Leaving the input uploaded/completed dictation before Finish");
        click("Finish keyboard dictation");waitFor(()->!Dictation.isWorking(),"Manual Finish did not settle");
        require(uploads==1,"Manual Finish did not upload exactly once");
        require(clipboard().equals(answer),"Full ordinary dictation did not reach clipboard recovery");
        require(Store.history(app).length()==1&&Store.history(app).getJSONObject(0).optString("text").equals(answer),"Ordinary result did not remain dictation History");
        require(text("Primary input").equals("Before after")&&text("Second input").equals("Keep this"),"An unverifiable returned editor received the final transcript");
        require(notes.equals(Store.get(app,"notes","[]")),"An input-started session became a Thought after focus loss");
    }
    private void privateRoundTrip()throws Exception{
        launchEditor();action("Password input",AccessibilityNodeInfo.ACTION_FOCUS,null);Thread.sleep(500);
        setClipboard(beforePrivate);String history=Store.history(app).toString(),notes=Store.get(app,"notes","[]");int before=uploads;
        answer="  um "+sentinel+" private raw words  ";ownedClips.add(answer);privateCapture=true;
        String start=waitAny("Floating keyboard microphone","VonoTation: start dictation");click(start);waitFor(this::capturing,"Private cloud microphone did not start");Thread.sleep(1700);
        // The app itself is another destination. The active Finish control must
        // remain available while its original private editor is unavailable.
        app.startActivity(new Intent().setClassName("app.voiceflow","app.voiceflow.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        ui.waitForIdle(300,10000);
        String finish=waitAny("Finish keyboard dictation","Finish dictation");assertCapturing("Private app switch");
        require(uploads==before,"Private dictation uploaded before explicit Finish");require(clipboard().equals(beforePrivate),"Private capture copied before Finish");
        click(finish);waitFor(()->!Dictation.isWorking(),"Private manual Finish did not settle");
        require(uploads==before+1,"Private manual Finish did not upload exactly once");require(PrivatePending.available(),"Private final words were not retained in memory");
        require(clipboard().equals(beforePrivate),"Private recovery copied automatically");
        require(history.equals(Store.history(app).toString())&&notes.equals(Store.get(app,"notes","[]")),"Private dictation entered History or Thoughts");
        runOnMainSync(()->{if(!PrivatePending.copy(app))throw new AssertionError("Explicit private copy failed");});
        require(clipboard().equals(answer),"Explicit private copy changed the original raw words");
        boolean[] marked={false};runOnMainSync(()->{ClipData clip=((ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();marked[0]=clip!=null&&clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false);});require(marked[0],"Explicit private copy lacked its sensitive flag");
        privateCapture=false;
    }
    private void launchEditor()throws Exception{app.startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));ui.waitForIdle(300,10000);waitNode("Primary input").recycle();}
    private boolean capturing(){if(FlowKeyboard.instance!=null&&FlowKeyboard.instance.isRecording())return !FlowKeyboard.instance.isFinishing()&&FlowKeyboard.instance.floatingStatus().startsWith("Listening");if(FlowAccessibility.instance==null||!FlowAccessibility.instance.isRecording())return false;try{java.lang.reflect.Field owner=FlowAccessibility.class.getDeclaredField("dictation");owner.setAccessible(true);Dictation recording=(Dictation)owner.get(FlowAccessibility.instance);java.lang.reflect.Field ready=Dictation.class.getDeclaredField("recorderStarted");ready.setAccessible(true);return recording!=null&&!recording.isFinishing()&&ready.getBoolean(recording);}catch(Exception unavailable){return false;}}
    private void assertCapturing(String transition)throws Exception{Thread.sleep(350);boolean[] active={false};runOnMainSync(()->active[0]=capturing());require(active[0],transition+" ended dictation before manual Finish");require(Store.get(app,"notes","[]").equals("[]"),transition+" converted input dictation into a Thought");}
    private void setClipboard(String value){runOnMainSync(()->((ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("fixture",value)));}
    private void cleanupOwnedClips(){
        if(!archiveTracked)return;
        runOnMainSync(()->{
            ClipboardManager manager=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);ClipData current=manager.getPrimaryClip();
            String text=current!=null&&current.getItemCount()>0&&current.getItemAt(0).getText()!=null?current.getItemAt(0).getText().toString():null;
            // Stop a delayed clipboard callback from re-adding a test value.
            // A clip changed by somebody else is deliberately left untouched.
            if(ownedClips.contains(text)){if(android.os.Build.VERSION.SDK_INT>=28)manager.clearPrimaryClip();else manager.setPrimaryClip(ClipData.newPlainText("fixture cleanup",""));}
            ClipboardArchive archive=ClipboardArchive.get(app);java.util.List<Long> owned=new java.util.ArrayList<>();
            try(android.database.Cursor rows=archive.getReadableDatabase().query("copies",new String[]{"_id"},"_id>?",new String[]{Long.toString(archiveFloor)},null,null,null)){
                long now=System.currentTimeMillis();while(rows.moveToNext()){long id=rows.getLong(0);if(ownedClips.contains(archive.text(id,now)))owned.add(id);}
            }
            for(long id:owned)ClipList.remove(app,id);
        });
    }
    private String clipboard(){String[] value={""};runOnMainSync(()->{ClipData clip=((ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();if(clip!=null&&clip.getItemCount()>0)value[0]=clip.getItemAt(0).coerceToText(app).toString();});return value[0];}
    private String text(String name)throws Exception{AccessibilityNodeInfo node=waitNode(name);try{return node.getText()==null?"":node.getText().toString();}finally{node.recycle();}}
    private void setText(String name,String text)throws Exception{Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);action(name,AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
    private void click(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_CLICK,null);}
    private void action(String name,int action,Bundle args)throws Exception{AccessibilityNodeInfo node=waitNode(name);try{require(node.performAction(action,args),"Action rejected: "+name);}finally{node.recycle();}}
    private AccessibilityNodeInfo waitNode(String name)throws Exception{long until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo node=find(name);if(node!=null)return node;Thread.sleep(100);}throw new Exception("Missing control: "+name);}
    private String waitAny(String first,String second)throws Exception{long until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){for(String name:new String[]{first,second}){AccessibilityNodeInfo node=find(name);if(node!=null){node.recycle();return name;}}Thread.sleep(100);}throw new Exception("No capture control: "+first+" / "+second);}
    private AccessibilityNodeInfo find(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),name);window.recycle();if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())||name.contentEquals(node.getText()==null?"":node.getText()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),name);if(found!=null){node.recycle();return found;}}node.recycle();return null;}
    interface Probe {boolean ready();}
    private void waitFor(Probe probe,String message)throws Exception{long until=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<until){boolean[] ready={false};runOnMainSync(()->ready[0]=probe.ready());if(ready[0])return;Thread.sleep(100);}throw new Exception(message);}
    private void require(boolean pass,String message)throws Exception{checks++;if(!pass)throw new Exception(message);}
    private final class Mock extends HttpsURLConnection {
        private final ByteArrayOutputStream body=new ByteArrayOutputStream();
        Mock(URL url){super(url);}
        public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
        public OutputStream getOutputStream(){return body;}
        public int getResponseCode(){return 200;}
        public InputStream getInputStream()throws IOException{
            String encoded=body.toString("UTF-8");if(!getURL().getPath().endsWith("/transcriptions")||body.size()<1000||!encoded.contains("whisper-large-v3"))throw new IOException("Unexpected provider request or missing microphone audio/model");
            if(privateCapture&&(encoded.contains("name=\"prompt\"")||encoded.contains("name=\"keywords[]\"")))throw new IOException("Private transcription included dictionary context");
            uploads++;try{return new ByteArrayInputStream(new org.json.JSONObject().put("text",answer).toString().getBytes("UTF-8"));}catch(org.json.JSONException invalid){throw new IOException(invalid);}
        }
    }
}
