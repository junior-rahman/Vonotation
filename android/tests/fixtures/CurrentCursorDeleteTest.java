package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

/** Device-only regression: actual overlay Delete with a nonterminal cursor and native editor connection. */
public final class CurrentCursorDeleteTest extends Instrumentation {
    private UiAutomation ui;private TrackingEditor field;private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle report=new Bundle();try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long ready=SystemClock.elapsedRealtime()+15000;while(EditorActivity.current==null && SystemClock.elapsedRealtime()<ready)Thread.sleep(100);
        runOnMainSync(()->{
            EditorActivity activity=EditorActivity.current;EditText old=(EditText)activity.findViewById(12345);ViewGroup parent=(ViewGroup)old.getParent();int at=parent.indexOfChild(old);ViewGroup.LayoutParams layout=old.getLayoutParams();parent.removeView(old);
            field=new TrackingEditor(activity);field.setId(12345);field.setContentDescription("Primary input");field.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);parent.addView(field,at,layout);field.requestFocus();
            ((InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE)).restartInput(field);
        });
        set("one two three",7,7);openDelete();tapDelete();expect("one  three",4,4);
        set("prefix chosen suffix",7,13);tapDelete();expect("prefix  suffix",7,7);
        set("prefix chosen suffix",13,7);tapDelete();expect("prefix  suffix",7,7);
        set("one 😀 tail",6,6);tapDelete();expect("one  tail",4,4);
        set("first\nsecond\nlast",12,12);tapDelete();expect("first\n\nlast",6,6);
        set("one two three four tail",18,18);Rect tile=bounds("Delete word");long down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,tile.centerX(),tile.centerY());
        waitText("one two three  tail");runOnMainSync(()->field.setSelection(3));Thread.sleep(750);expect("one two three  tail",3,3);touch(MotionEvent.ACTION_UP,down,tile.centerX(),tile.centerY());
        tapDelete();expect(" two three  tail",0,0);
        set("one two three tail",13,13);tile=bounds("Delete word");down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,down,tile.centerX(),tile.centerY());waitText("one two  tail");touch(MotionEvent.ACTION_MOVE,down,tile.left-70,tile.centerY());String stopped=text();Thread.sleep(750);require(stopped.equals(text()),"Slide-away did not stop held deletion");touch(MotionEvent.ACTION_UP,down,tile.left-70,tile.centerY());
        int[] nativeCalls={0,0};runOnMainSync(()->{nativeCalls[0]=field.nativeDeletes;nativeCalls[1]=field.fullRewrites;});
        require(nativeCalls[0]>0,"Expected native InputConnection word deletion on Android13+");require(nativeCalls[1]==0,"Native-capable editor was rewritten with ACTION_SET_TEXT");
        report.putString("result","PASS: "+checks+" actual cursor/selection checks; native deletion, reversed selection, Unicode, newline, held-delete manual movement and slide-away; no whole-field rewrite");
    }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);}
    private void set(String text,int a,int b)throws Exception{runOnMainSync(()->{field.setText(text);field.requestFocus();field.setSelection(a,b);});Thread.sleep(350);}
    private void openDelete()throws Exception{long until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){AccessibilityNodeInfo delete=node("Delete word");if(delete!=null){delete.recycle();return;}for(String name:new String[]{"Floating keyboard microphone","VonoTation: start dictation"}){AccessibilityNodeInfo mic=node(name);if(mic!=null){mic.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);mic.recycle();Thread.sleep(750);break;}}Thread.sleep(100);}throw new Exception("Delete fan did not open");}
    private void tapDelete()throws Exception{AccessibilityNodeInfo tile=node("Delete word");require(tile!=null,"Delete control missing");try{require(tile.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Delete click rejected");}finally{tile.recycle();}Thread.sleep(400);}
    private String text(){String[] value={""};runOnMainSync(()->value[0]=field.getText().toString());return value[0];}
    private void waitText(String expected)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(expected.equals(text()))return;Thread.sleep(80);}throw new Exception("Expected ["+expected+"] got ["+text()+"]");}
    private void expect(String expected,int a,int b)throws Exception{waitText(expected);int[] range={-1,-1};runOnMainSync(()->{range[0]=field.getSelectionStart();range[1]=field.getSelectionEnd();});require(range[0]==a && range[1]==b,"Cursor jumped: "+range[0]+","+range[1]+" expected "+a+","+b);}
    private Rect bounds(String name)throws Exception{AccessibilityNodeInfo n=node(name);require(n!=null,"Control missing: "+name);try{Rect r=new Rect();n.getBoundsInScreen(r);return r;}finally{n.recycle();}}
    private void touch(int action,long down,float x,float y)throws Exception{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(ui.injectInputEvent(event,true),"Touch rejected");}finally{event.recycle();}}
    private void require(boolean pass,String message)throws Exception{checks++;if(!pass)throw new Exception(message);}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo root=window.getRoot();try{AccessibilityNodeInfo result=find(root,name);if(result!=null)return result;}finally{if(root!=null)root.recycle();}}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String name){if(n==null)return null;if(name.contentEquals(n.getContentDescription()==null?"":n.getContentDescription()))return AccessibilityNodeInfo.obtain(n);for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);try{AccessibilityNodeInfo found=find(child,name);if(found!=null)return found;}finally{if(child!=null)child.recycle();}}return null;}
    private static final class TrackingEditor extends EditText {
        int nativeDeletes,fullRewrites;
        TrackingEditor(Activity activity){super(activity);setAccessibilityDelegate(new android.view.View.AccessibilityDelegate(){@Override public boolean performAccessibilityAction(android.view.View host,int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SET_TEXT)fullRewrites++;return super.performAccessibilityAction(host,action,args);}});}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){InputConnection base=super.onCreateInputConnection(info);return base==null?null:new InputConnectionWrapper(base,false){@Override public boolean deleteSurroundingText(int before,int after){nativeDeletes++;return super.deleteSurroundingText(before,after);}};}
    }
}
