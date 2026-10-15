package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.TextAttribute;
import android.widget.EditText;

/** Native composing-span dictation revision; historical class name retained for the fixture component. */
public final class ComposeDeleteTest extends Instrumentation {
    private UiAutomation ui;
    private TrackingEditor field;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();
        result.putString("retired_subcases","OBSOLETE, NOT RUN: Floating Backspace deletion of a composing word, a complete Unicode character, and a selected composing range. The minimal bubble intentionally removed floating editing controls; native dictation revision/composition assertions remain active.");
        try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo service=ui.getServiceInfo();service.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(service);
        FakeSpeech.composeMode=true;
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitNode("Floating keyboard microphone");clickLong("Floating keyboard microphone");waitNode("VoiceFlow: start dictation");
        runOnMainSync(()->{
            EditorActivity activity=EditorActivity.current;EditText old=(EditText)activity.findViewById(12345);ViewGroup parent=(ViewGroup)old.getParent();int index=parent.indexOfChild(old);ViewGroup.LayoutParams layout=old.getLayoutParams();parent.removeView(old);
            field=new TrackingEditor(activity);field.setId(12345);field.setContentDescription("Primary input");field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);parent.addView(field,index,layout);field.setText("Before after");field.requestFocus();field.setSelection(7);
            InputMethodManager manager=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);manager.restartInput(field);manager.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);
        });
        waitConnection();waitNode("VoiceFlow: start dictation");click("VoiceFlow: start dictation");waitText("Before Hello after");
        compose(7,12);click("Finish dictation");waitText("Before Hello, world after");waitIdleMainMic();assertNative("Dictation revision");
        result.putString("result","PASS: actual accessibility InputConnection ends the established composing span before revising dictation, preserving the surrounding prefix/suffix; Finish returns the idle main mic without floating editing/menu controls. Three removed floating-Delete subcases are obsolete and were not run.");
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}finally{
        FakeSpeech.composeMode=false;
        try{if(node("VoiceFlow: start dictation")!=null)clickLong("VoiceFlow: start dictation");}catch(Exception ignored){}
    }finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);}
    private void waitConnection()throws Exception{long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};runOnMainSync(()->ready[0]=field.connection!=null);if(ready[0])return;Thread.sleep(100);}throw new Exception("Native input connection missing");}
    private void compose(int start,int end)throws Exception{boolean[] composing={false};runOnMainSync(()->{field.connection.setComposingRegion(start,end);composing[0]=BaseInputConnection.getComposingSpanStart(field.getText())==start && BaseInputConnection.getComposingSpanEnd(field.getText())==end;field.reset();});check(composing[0],"Native composing span was not established");}
    private void assertNative(String phase)throws Exception{int[] counts={0,0};runOnMainSync(()->{counts[0]=field.finishCalls;counts[1]=field.deleteCalls;});check(counts[0]>0 && counts[1]>0,phase+" bypassed the accessibility InputConnection: finish="+counts[0]+", delete="+counts[1]);}
    private void waitText(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+15000;String[] actual={""};while(SystemClock.elapsedRealtime()<end){runOnMainSync(()->actual[0]=field.getText().toString());if(expected.equals(actual[0]))return;Thread.sleep(100);}throw new Exception("Editor expected ["+expected+"] but got ["+actual[0]+"]");}
    private void waitIdleMainMic()throws Exception{
        waitNode("VoiceFlow: start dictation");
        for(String removed:new String[]{"Floating Backspace","Floating Enter","More voice controls","Floating Switch keyboard","Hide voice controls"}){
            AccessibilityNodeInfo found=node(removed);boolean absent=found==null;if(found!=null)found.recycle();
            check(absent,"Minimal idle bubble exposed removed control: "+removed);
        }
    }
    private void click(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_CLICK);}
    private void clickLong(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_LONG_CLICK);}
    private void action(String name,int action)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo current=node(name);if(current!=null && current.performAction(action))return;Thread.sleep(100);}throw new Exception("Action "+action+" rejected: "+name);}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    private void waitNode(String name)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(node(name)!=null)return;Thread.sleep(100);}throw new Exception("Missing "+name);}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo match=find(window.getRoot(),name);if(match!=null)return match;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo match=find(node.getChild(i),name);if(match!=null)return match;}return null;}
    private static final class TrackingEditor extends EditText {
        InputConnection connection;int finishCalls,deleteCalls;
        TrackingEditor(Activity context){super(context);}
        void reset(){finishCalls=0;deleteCalls=0;}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){InputConnection base=super.onCreateInputConnection(info);connection=base==null?null:new InputConnectionWrapper(base,false){
            @Override public boolean finishComposingText(){finishCalls++;return super.finishComposingText();}
            @Override public boolean deleteSurroundingText(int before,int after){deleteCalls++;return super.deleteSurroundingText(before,after);}
            @Override public boolean commitText(CharSequence text,int position,TextAttribute attribute){return super.commitText(text,position,attribute);}
        };return connection;}
    }
}
