package app.voiceflow.fixture;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;
import android.widget.EditText;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Legacy synthetic-speech contract. Current cloud capture uses CloudPersistenceTest. */
public final class InputRecoveryTest extends Instrumentation {
    private UiAutomation ui;
    private boolean accessibilityDisabled;
    public void onCreate(Bundle args){super.onCreate(args);legacySpeech=args!=null && args.getBoolean("legacy_speech",false);start();}
    public void onStart(){Bundle result=new Bundle();try{
        // The released app is cloud-only. A saved engine=system preference never
        // selects FakeSpeech, so live-partial expectations here are dormant.
        if(!legacySpeech){
            result.putString("not_executed","Legacy FakeSpeech input contract is retired for cloud-only production. Run app.voiceflow.CloudPersistenceTest.");
            finish(Activity.RESULT_CANCELED,result);return;
        }
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitNode("Floating keyboard microphone");setPrimary("Before after",7);clearClip();click("Floating keyboard microphone");waitWords(12345);
        focus("Second input");setSecond("Keep this");requireStillRecording("Finish keyboard dictation");check(text(12346).equals("Keep this"),"Voice keyboard wrote into the next input");check(text(12345).contains("Hello, world"),"Original partial words disappeared");click("Finish keyboard dictation");waitCopied();
        checkKeyboardAppRoundTrip();
        focus("Primary input");waitNode("Floating keyboard microphone");setPrimary("Before after",7);clearClip();click("Floating keyboard microphone");waitWords(12345);
        setPrimary("My manual edit",14);click("Finish keyboard dictation");waitCopied();check(text(12345).equals("My manual edit"),"Final transcript overwrote a manual edit");
        clickLong("Floating keyboard microphone");waitNode("VoiceFlow: start dictation");
        setPrimary("Before after",7);clearClip();click("VoiceFlow: start dictation");waitWords(12345);click("Finish dictation");waitIdleMainMic();check(text(12345).contains("Hello, world"),"Typing-IME dictation did not insert directly");check(clipText().equals("before recording"),"Successful direct insertion unnecessarily copied the transcript");
        setPrimary("Before after",7);clearClip();click("VoiceFlow: start dictation");waitWords(12345);focus("Second input");setSecond("Keep this");requireStillRecording("Finish dictation");check(text(12346).equals("Keep this"),"Floating dictation wrote into the next input");click("Finish dictation");waitCopied();
        waitNode("VoiceFlow: start dictation");clickLong("VoiceFlow: start dictation");waitNode("Floating keyboard microphone");
        checkPendingTeardown(false);checkPendingTeardown(true);
        focus("Primary input");waitNode("VoiceFlow: start dictation");clickLong("VoiceFlow: start dictation");waitNode("Floating keyboard microphone");
        result.putString("result","PASS: voice-IME and typing-IME field changes keep dictation recording until manual Finish without writing into a different input; app switching and returning preserves the Finish control and recovers the full voice-IME result without ambiguous rejoining; direct insertion and manual-edit preservation hold; service teardown during withheld final editor acknowledgement recovers ordinary words once and private words only after explicit Copy privately");
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}
    finally{FakeSpeech.credentialText=null;try{if(accessibilityDisabled)enableAccessibility();}catch(Exception ignored){}}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private boolean legacySpeech;
    private void checkKeyboardAppRoundTrip()throws Exception{
        focus("Primary input");waitNode("Floating keyboard microphone");setPrimary("Before after",7);clearClip();click("Floating keyboard microphone");waitWords(12345);
        getTargetContext().startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        requireStillRecording("Finish keyboard dictation");
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        focus("Primary input");requireStillRecording("Finish keyboard dictation");
        FakeSpeech.credentialText="um final words after returning";click("Finish keyboard dictation");waitClip("Final words after returning");FakeSpeech.credentialText=null;
        check(!text(12346).contains("Final words after returning"),"App return redirected dictation into another input");
    }
    private void requireStillRecording(String finish)throws Exception{
        waitNode(finish);Thread.sleep(1000);waitNode(finish);
        check(clipText().equals("before recording"),"Leaving the input completed/copied dictation before manual Finish");
    }
    private void checkPendingTeardown(boolean sensitive)throws Exception{
        if(!sensitive){clickLong("Floating keyboard microphone");waitNode("VoiceFlow: start dictation");}
        DeferredEditor[] value={null};runOnMainSync(()->{
            EditorActivity activity=EditorActivity.current;int id=sensitive?12346:12345;EditText old=(EditText)activity.findViewById(id);ViewGroup parent=(ViewGroup)old.getParent();int index=parent.indexOfChild(old);ViewGroup.LayoutParams layout=old.getLayoutParams();parent.removeView(old);
            DeferredEditor field=new DeferredEditor(activity,sensitive);field.setId(id);field.setContentDescription(sensitive?"Private deferred input":"Primary input");field.setInputType(android.text.InputType.TYPE_CLASS_TEXT|(sensitive?android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD:android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE));parent.addView(field,index,layout);
            field.setText(sensitive?"Keep this secret":"Before after");field.requestFocus();field.setSelection(sensitive?4:7);value[0]=field;
            InputMethodManager manager=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);manager.restartInput(field);manager.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);
        });
        DeferredEditor field=value[0];waitNode("VoiceFlow: start dictation");Thread.sleep(350);clearClip();
        FakeSpeech.credentialText=sensitive?"spoken private final":null;click("VoiceFlow: start dictation");waitNode("Finish dictation");
        if(sensitive){Thread.sleep(850);check(text(12346).equals("Keep this secret"),"Private partial text changed the credential before Finish");}
        else waitWords(12345);
        FakeSpeech.credentialText=sensitive?"spoken private final":"um complete final transcript";click("Finish dictation");
        check(field.finalCommit.await(10000,TimeUnit.MILLISECONDS),"Deferred final did not reach the native editor");
        // Withheld metadata keeps the final in the service's acknowledgement retry.
        // Disable the real service before that bounded retry ends, not by changing fields.
        accessibilityDisabled=true;shell("settings delete secure enabled_accessibility_services");
        if(sensitive){
            // Recovery keeps secrets in memory. Nothing may reach the clipboard
            // until the owner explicitly selects the private recovery action.
            Thread.sleep(800);check(clipText().equals("before recording"),"Private teardown automatically copied a secret");
            getTargetContext().startActivity(new Intent().setClassName("app.voiceflow","app.voiceflow.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            waitNode("Copy privately");check(clipText().equals("before recording"),"Opening private recovery copied a secret without consent");click("Copy privately");waitClip("spoken private final");
        }else waitClip("Complete final transcript");
        int[] commits={0};runOnMainSync(()->commits[0]=field.finalCommits);check(commits[0]==1,"Teardown resent a pending final native commit: "+commits[0]);
        if(sensitive){check(text(12346).equals("Keep this secret"),"Rejected private final overwrote an existing credential");check(sensitiveClip(),"Private teardown fallback was not marked sensitive");}
        else check(text(12345).equals("Before Complete final transcript after"),"Pending ordinary final lost or duplicated its insertion");
        FakeSpeech.credentialText=null;enableAccessibility();getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));waitNode("VoiceFlow: start dictation");
    }
    private void enableAccessibility()throws Exception{shell("settings put secure enabled_accessibility_services app.voiceflow/.FlowAccessibility");shell("settings put secure accessibility_enabled 1");accessibilityDisabled=false;}
    private void shell(String command)throws Exception{try(ParcelFileDescriptor.AutoCloseInputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command))){byte[] buffer=new byte[1024];while(stream.read(buffer)!=-1){}}}
    private void waitClip(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){if(expected.equals(clipText()))return;Thread.sleep(100);}throw new Exception("Pending final disappeared during service teardown: "+expected);}
    private boolean sensitiveClip(){boolean[] sensitive={false};runOnMainSync(()->{ClipData clip=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();sensitive[0]=clip!=null && clip.getDescription().getExtras()!=null && clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE",false);});return sensitive[0];}
    private void setPrimary(String value,int cursor){runOnMainSync(()->{EditText field=(EditText)EditorActivity.current.findViewById(12345);field.setText(value);field.setSelection(cursor);});}
    private void setSecond(String value){runOnMainSync(()->((EditText)EditorActivity.current.findViewById(12346)).setText(value));}
    private String text(int id){String[] value={""};runOnMainSync(()->value[0]=((EditText)EditorActivity.current.findViewById(id)).getText().toString());return value[0];}
    private void clearClip(){runOnMainSync(()->((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("fixture","before recording")));}
    private String clipText(){String[] value={""};runOnMainSync(()->{ClipData clip=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();if(clip!=null)value[0]=clip.getItemAt(0).coerceToText(getTargetContext()).toString();});return value[0];}
    private void waitCopied()throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){if(clipText().equals("Hello, world"))return;Thread.sleep(150);}throw new Exception("Manually finished transcript did not reach clipboard");}
    private void waitIdleMainMic()throws Exception{
        waitNode("VoiceFlow: start dictation");
        for(String removed:new String[]{"Floating Backspace","Floating Enter","More voice controls","Floating Switch keyboard","Hide voice controls"}){
            AccessibilityNodeInfo found=node(removed);boolean absent=found==null;if(found!=null)found.recycle();
            check(absent,"Minimal idle bubble exposed removed control: "+removed);
        }
    }
    private void waitWords(int id)throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){if(text(id).contains("Hello, world"))return;Thread.sleep(150);}throw new Exception("Live words missing");}
    private void focus(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_FOCUS);Thread.sleep(200);}
    private void click(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_CLICK);}
    private void clickLong(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_LONG_CLICK);}
    private void action(String name,int action)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo current=node(name);if(current!=null && current.performAction(action))return;Thread.sleep(150);}throw new Exception("Action "+action+" rejected: "+name);}
    private void check(boolean okay,String message)throws Exception{if(!okay)throw new Exception(message);}
    private void waitNode(String name)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(node(name)!=null)return;Thread.sleep(150);}throw new Exception("Missing "+name);}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),name);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()) || name.contentEquals(node.getText()==null?"":node.getText()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),name);if(found!=null)return found;}return null;}
    private static final class DeferredEditor extends EditText {
        final boolean sensitive;final CountDownLatch finalCommit=new CountDownLatch(1);int finalCommits;boolean withhold;
        DeferredEditor(Activity activity,boolean sensitive){super(activity);this.sensitive=sensitive;}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){InputConnection base=super.onCreateInputConnection(info);return base==null?null:new InputConnectionWrapper(base,false){
            @Override public SurroundingText getSurroundingText(int before,int after,int flags){return sensitive || withhold?null:super.getSurroundingText(before,after,flags);}
            @Override public boolean commitText(CharSequence text,int position,TextAttribute attribute){
                String value=String.valueOf(text);boolean finalValue=sensitive?value.equals("spoken private final"):value.contains("Complete final transcript");
                if(!finalValue)return super.commitText(text,position,attribute);
                finalCommits++;boolean result=sensitive || super.commitText(text,position,attribute);withhold=true;finalCommit.countDown();return result;
            }
        };}
    }
}
