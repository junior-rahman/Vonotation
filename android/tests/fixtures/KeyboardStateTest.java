package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Exercises the keyboard indicator against real IME visibility, not a mock flag. */
public final class KeyboardStateTest extends Instrumentation {
    private UiAutomation ui;
    private long backSentAt;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle report=new Bundle();try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        try(android.os.ParcelFileDescriptor.AutoCloseInputStream command=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"));java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){byte[] buffer=new byte[256];int count;while((count=command.read(buffer))!=-1)out.write(buffer,0,count);check(!out.toString("UTF-8").contains("app.voiceflow.fixture/"),"KeyboardStateTest requires an ordinary launcher as HOME; the HomeFinder fixture shares EditorActivity's package and intentionally hides voice controls when its IME closes. Restore the ordinary launcher before running this test.");}
        launch();waitNode("Floating keyboard microphone");waitTyping(false);
        waitState(false);toggle();waitTyping(true);waitNode("VoiceFlow: start dictation");waitState(true);
        check(node("Floating Backspace")==null && node("Floating Enter")==null && node("Floating Switch keyboard")==null && node("Hide voice controls")==null,"Idle toggle exposed an unnecessary options menu");

        // The system Back button hides an ordinary IME while the saved choice
        // remains enabled. The state must change, and the same action must reopen it.
        // UiAutomation rejects source-less KeyEvent objects on newer Android.
        // The system input command supplies a complete keyboard event; consume
        // its output to wait for completion before checking the real IME window.
        backSentAt=SystemClock.elapsedRealtime();try(android.os.ParcelFileDescriptor.AutoCloseInputStream command=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("input keyevent 4"))){byte[] buffer=new byte[256];while(command.read(buffer)!=-1){}}
        waitTyping(false);waitState(false);toggle();waitTyping(true);waitNode("VoiceFlow: start dictation");waitState(true);

        action(AccessibilityNodeInfo.ACTION_FOCUS,"Second input");waitTyping(true);waitNode("VoiceFlow: start dictation");waitState(true);
        launch();waitTyping(true);waitNode("VoiceFlow: start dictation");waitState(true);
        toggle();waitTyping(false);waitNode("Floating keyboard microphone");waitState(false);
        launch();waitTyping(false);waitNode("Floating keyboard microphone");waitState(false);
        report.putString("result","PASS: keyboard-visible and keyboard-hidden bubble states track real IME windows; long hold toggles directly without an options menu; Back updates the indicator and another hold reopens typing; enabled and hidden choices survive field changes and activity restart");
    }catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));report.putString("ui_before_finish",diagnostics());}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void launch(){getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private void toggle()throws Exception{
        action(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Floating keyboard microphone","VoiceFlow: start dictation");
    }
    private void action(int action,String... names)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){for(String name:names){AccessibilityNodeInfo current=node(name);if(current!=null){boolean accepted=current.performAction(action);current.recycle();if(accepted)return;}}Thread.sleep(150);}throw new Exception("Action "+action+" rejected: "+java.util.Arrays.toString(names));}
    private void waitState(boolean shown)throws Exception{
        long end=SystemClock.elapsedRealtime()+45000;
        String state=shown?"Keyboard visible":"Keyboard hidden";
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo button=node("Floating keyboard microphone");if(button==null)button=node("VoiceFlow: start dictation");if(button!=null){boolean refreshed=button.refresh();boolean matches=refreshed && state.contentEquals(button.getStateDescription()==null?"":button.getStateDescription());boolean action=false;for(AccessibilityNodeInfo.AccessibilityAction item:button.getActionList())if(item.getId()==AccessibilityNodeInfo.ACTION_LONG_CLICK && "Hold to toggle keyboard".contentEquals(item.getLabel()==null?"":item.getLabel()))action=true;button.recycle();if(matches && action)return;}Thread.sleep(150);}
        throw new Exception("Keyboard indicator did not report "+state);
    }
    private void waitTyping(boolean expected)throws Exception{
        long end=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<end){boolean visible=false;for(AccessibilityWindowInfo window:ui.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect bounds=new Rect();window.getBoundsInScreen(bounds);if(bounds.height()>40)visible=true;}if(visible==expected)return;Thread.sleep(150);}
        throw new Exception("Typing keyboard visibility expected "+expected);
    }
    private String diagnostics(){StringBuilder out=new StringBuilder();out.append("msSinceBack=").append(backSentAt==0?-1:SystemClock.elapsedRealtime()-backSentAt).append('\n');try{runOnMainSync(()->{EditorActivity activity=EditorActivity.current;out.append("activity=").append(activity!=null).append(" finishing=").append(activity!=null && activity.isFinishing());if(activity!=null){android.view.View field=activity.findViewById(12345);out.append(" fieldFocused=").append(field!=null && field.isFocused());android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();out.append(" imeInsetsVisible=").append(insets!=null && insets.isVisible(android.view.WindowInsets.Type.ime()));}out.append('\n');});for(AccessibilityWindowInfo window:ui.getWindows()){Rect bounds=new Rect();window.getBoundsInScreen(bounds);out.append("window type=").append(window.getType()).append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused()).append(" bounds=").append(bounds).append('\n');debugNode(window.getRoot(),out);}}catch(Throwable error){out.append("diagnostics failed: ").append(error);}return out.toString();}
    private void debugNode(AccessibilityNodeInfo node,StringBuilder out){if(node==null)return;node.refresh();CharSequence desc=node.getContentDescription();if(desc!=null && (desc.toString().contains("VoiceFlow") || desc.toString().contains("Floating") || desc.toString().contains("Primary input"))){Rect bounds=new Rect();node.getBoundsInScreen(bounds);out.append(" node ").append(desc).append(" state=").append(node.getStateDescription()).append(" focused=").append(node.isFocused()).append(" visible=").append(node.isVisibleToUser()).append(" bounds=").append(bounds).append('\n');}for(int i=0;i<node.getChildCount();i++)debugNode(node.getChild(i),out);node.recycle();}
    private void waitNode(String name)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo found=node(name);if(found!=null){found.recycle();return;}Thread.sleep(150);}throw new Exception("Missing "+name);}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),name);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),name);if(found!=null){node.recycle();return found;}}node.recycle();return null;}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
}
