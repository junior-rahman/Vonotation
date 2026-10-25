package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.File;
import java.io.FileOutputStream;

/** Real accessibility overlay and external editor; synthetic transcript, no provider or microphone. */
public final class RecoveredClipboardTest extends Instrumentation {
    private UiAutomation ui;
    private static final String NORMAL="Recovery normal input",PASSWORD="Recovery password input",REJECT="Recovery rejecting input";
    private static final String COPY="Saved words ",PREVIEW="Paste copied dictation: Saved words";
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            require(!AppProduct.isNotes()&&Vault.get(getTargetContext()).isEmpty(),"Only disposable Voitation may run this test");
            ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
            Store.prefs(getTargetContext()).edit().putBoolean("typing_visible",false).putBoolean("quick_thoughts",false).commit();
            shell("settings put secure enabled_accessibility_services app.voiceflow/app.voiceflow.FlowAccessibility");
            shell("settings put secure accessibility_enabled 1");
            long until=SystemClock.uptimeMillis()+15000;
            while(FlowAccessibility.instance==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
            require(FlowAccessibility.instance!=null,"Accessibility service did not connect");
            getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.visual.fixture","app.voiceflow.fixture.ClipboardInputActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            focus(NORMAL);SystemClock.sleep(800);
            runOnMainSync(()->FlowAccessibility.instance.queueCopiedDictation(COPY));
            SystemClock.sleep(300);require(find(PREVIEW)==null,"Copy popped up before another input activation");
            focus(REJECT);waitNode(PREVIEW);
            // Previously a non-editor/keyboard focus event closed the entire fan.
            runOnMainSync(()->{
                AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED);
                event.setPackageName("com.android.inputmethod.latin");event.setClassName("android.widget.Button");
                FlowAccessibility.instance.onAccessibilityEvent(event);event.recycle();
            });
            SystemClock.sleep(13000);waitNode(PREVIEW);capture("persistent-after-timeout.png");
            touch(PREVIEW);SystemClock.sleep(EditorInsertion.ACK_MS+700);
            require("Keep".equals(text(REJECT)),"Refused paste changed the rejecting field");waitNode(PREVIEW);
            focus(PASSWORD);SystemClock.sleep(700);require(find(PREVIEW)==null,"Private input exposed copied text");
            focus(NORMAL);selection(NORMAL,7);waitNode(PREVIEW);
            touch("Recovery leave input");SystemClock.sleep(900);require(find(PREVIEW)==null,"Preview stayed over an inactive input");
            focus(NORMAL);selection(NORMAL,7);waitNode(PREVIEW);
            touch(PREVIEW);
            until=SystemClock.uptimeMillis()+5000;
            while(!"Before Saved words after".equals(text(NORMAL))&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
            require("Before Saved words after".equals(text(NORMAL)),"Recovered text did not paste at the current caret exactly once");
            SystemClock.sleep(600);require(find(PREVIEW)==null,"Acknowledged paste did not close the preview");
            focus(REJECT);focus(NORMAL);SystemClock.sleep(700);require(find(PREVIEW)==null,"Pasted transcript reopened on focus");
            runOnMainSync(()->FlowAccessibility.instance.queueCopiedDictation("Dismiss me"));
            focus(REJECT);waitNode("Paste copied dictation: Dismiss me");touch("Dismiss copied dictation");
            focus(NORMAL);SystemClock.sleep(700);require(find("Paste copied dictation: Dismiss me")==null,"Explicitly dismissed copy reopened");
            capture("paste-completed.png");
            result.putString("result","PASS: "+checks+" native assertions; recovered overlay survives 13-second idle and keyboard focus events, failed paste remains retryable, private/missing inputs hide without consuming, real tap pastes once at caret, and explicit dismissal does not reopen");
        }catch(Throwable failure){result.putString("failure",android.util.Log.getStackTraceString(failure));try{capture("failure.png");}catch(Exception ignored){}}
        finally{try{shell("settings delete secure enabled_accessibility_services");shell("settings put secure accessibility_enabled 0");}catch(Exception ignored){}}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private void require(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private void shell(String command)throws Exception{try(android.os.ParcelFileDescriptor fd=ui.executeShellCommand(command);java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] bytes=new byte[1024];while(in.read(bytes)!=-1){}}}
    private AccessibilityNodeInfo find(String description){
        for(AccessibilityWindowInfo window:ui.getWindows()){
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            AccessibilityNodeInfo found=search(root,description);root.recycle();if(found!=null)return found;
        }return null;
    }
    private AccessibilityNodeInfo search(AccessibilityNodeInfo node,String description){
        CharSequence value=node.getContentDescription();if(value!=null&&value.toString().trim().equals(description.trim()))return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;AccessibilityNodeInfo found=search(child,description);child.recycle();if(found!=null)return found;}return null;
    }
    private AccessibilityNodeInfo waitNode(String description){
        long until=SystemClock.uptimeMillis()+10000;AccessibilityNodeInfo node;
        while((node=find(description))==null&&SystemClock.uptimeMillis()<until)SystemClock.sleep(100);
        require(node!=null,"Missing control: "+description);return node;
    }
    private void focus(String description){AccessibilityNodeInfo node=waitNode(description);if(!node.isFocused())node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);node.recycle();SystemClock.sleep(400);node=waitNode(description);require(node.isFocused(),"Could not focus "+description);node.recycle();}
    private void selection(String description,int at){AccessibilityNodeInfo node=waitNode(description);Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,at);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,at);node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,args);node.recycle();SystemClock.sleep(200);node=waitNode(description);require(node.getTextSelectionStart()==at&&node.getTextSelectionEnd()==at,"Could not set caret");node.recycle();}
    private String text(String description){AccessibilityNodeInfo node=waitNode(description);String value=String.valueOf(node.getText());node.recycle();return value;}
    private void touch(String description){AccessibilityNodeInfo node=waitNode(description);Rect bounds=new Rect();node.getBoundsInScreen(bounds);node.recycle();long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);MotionEvent up=MotionEvent.obtain(now,now+80,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);down.setSource(InputDevice.SOURCE_TOUCHSCREEN);up.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(ui.injectInputEvent(down,true),"Touch down failed");require(ui.injectInputEvent(up,true),"Touch up failed");down.recycle();up.recycle();SystemClock.sleep(400);}
    private void capture(String name)throws Exception{File folder=getTargetContext().getExternalFilesDir("clipboard-proof");if(folder==null||(!folder.exists()&&!folder.mkdirs()))throw new IllegalStateException("Missing evidence folder");Bitmap image=ui.takeScreenshot();if(image==null)throw new IllegalStateException("Missing screenshot");try(FileOutputStream out=new FileOutputStream(new File(folder,name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
}
