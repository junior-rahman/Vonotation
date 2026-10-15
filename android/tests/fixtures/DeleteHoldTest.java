package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Physical touch regression checks for repeating Delete in the selected editor. */
public final class DeleteHoldTest extends Instrumentation {
    private UiAutomation ui;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitNode("Floating keyboard microphone");click("Floating keyboard microphone");waitNode("Finish keyboard dictation");Thread.sleep(900);click("Finish keyboard dictation");waitNode("Floating Backspace");
        setText("A\uD83D\uDE00");Rect delete=bounds("Floating Backspace");long down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,delete.centerX(),delete.centerY());Thread.sleep(80);touch(MotionEvent.ACTION_UP,down,delete.centerX(),delete.centerY());Thread.sleep(650);check(text().equals("A"),"Tap did not delete exactly one Unicode character");
        StringBuilder longText=new StringBuilder();for(int i=0;i<200;i++)longText.append('x');setText(longText.toString());
        delete=bounds("Floating Backspace");down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,delete.centerX(),delete.centerY());Thread.sleep(150);check(text().length()==199,"Delete did not act once immediately");
        Thread.sleep(android.view.ViewConfiguration.getLongPressTimeout());int before=text().length();Thread.sleep(700);int slow=before-text().length();Thread.sleep(1700);before=text().length();Thread.sleep(700);int fast=before-text().length();
        touch(MotionEvent.ACTION_UP,down,delete.centerX(),delete.centerY());Thread.sleep(200);int released=text().length();Thread.sleep(600);check(text().length()==released,"Delete continued after release");check(slow>=3 && fast>slow,"Held Delete did not accelerate: "+slow+" -> "+fast);
        setText(longText.toString());delete=bounds("Floating Backspace");down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,delete.centerX(),delete.centerY());Thread.sleep(750);touch(MotionEvent.ACTION_MOVE,down,delete.left-60,delete.centerY());Thread.sleep(150);int moved=text().length();Thread.sleep(650);check(text().length()==moved,"Sliding away did not stop Delete");touch(MotionEvent.ACTION_UP,down,delete.left-60,delete.centerY());
        setText(longText.toString());delete=bounds("Floating Backspace");down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,delete.centerX(),delete.centerY());Thread.sleep(750);action("Second input",AccessibilityNodeInfo.ACTION_FOCUS,null);Thread.sleep(200);String originalAfterFocus=text();Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Keep this");action("Second input",AccessibilityNodeInfo.ACTION_SET_TEXT,value);Thread.sleep(700);
        check("Keep this".equals(text(12346)),"Held Delete reached a different input");check(originalAfterFocus.equals(text()),"Held Delete continued in the old input after focus changed");check(node("Floating Backspace")==null,"Input change left the old Delete control attached");
        // Changing input removes the touched Delete view. InputDispatcher has
        // already cancelled that stream, so this final release is cleanup only.
        // Earlier real release and slide-away checks still require accepted events.
        finishCanceledGesture(down,delete.centerX(),delete.centerY());Thread.sleep(350);
        check("Keep this".equals(text(12346)) && originalAfterFocus.equals(text()),"Cancelled gesture cleanup changed an input");
        result.putString("result","PASS: one tap deletes one Unicode character; holding repeats and accelerates ("+slow+" -> "+fast+" per 700ms); release, slide-away, and input changes stop deletion");
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);}
    private void setText(String text)throws Exception{Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);action("Primary input",AccessibilityNodeInfo.ACTION_SET_TEXT,value);Thread.sleep(250);Bundle selection=new Bundle();selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,text.length());selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,text.length());AccessibilityNodeInfo field=waitNode("Primary input");if(field.getTextSelectionStart()!=text.length() || field.getTextSelectionEnd()!=text.length())action("Primary input",AccessibilityNodeInfo.ACTION_SET_SELECTION,selection);Thread.sleep(250);}
    private String text(){return text(12345);}
    private String text(int id){String[] value={""};runOnMainSync(()->value[0]=((android.widget.EditText)EditorActivity.current.findViewById(id)).getText().toString());return value[0];}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    private void click(String name)throws Exception{action(name,AccessibilityNodeInfo.ACTION_CLICK,null);}
    private void action(String name,int action,Bundle args)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo current=node(name);if(current!=null && (args==null?current.performAction(action):current.performAction(action,args)))return;Thread.sleep(150);}throw new Exception("Action "+action+" rejected: "+name);}
    private Rect bounds(String name)throws Exception{Rect value=new Rect();waitNode(name).getBoundsInScreen(value);return value;}
    private void touch(int action,long down,float x,float y)throws Exception{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(ui.injectInputEvent(event,true),"Touch rejected");event.recycle();}
    private void finishCanceledGesture(long down,float x,float y){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{ui.injectInputEvent(event,true);}finally{event.recycle();}}
    private AccessibilityNodeInfo waitNode(String name)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo current=node(name);if(current!=null)return current;Thread.sleep(150);}throw new Exception("Missing "+name);}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),name);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),name);if(found!=null)return found;}return null;}
}
