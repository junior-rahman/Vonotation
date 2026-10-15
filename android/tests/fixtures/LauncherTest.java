package app.voiceflow.fixture;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;

/** Changes and restores only the isolated emulator's default home app to reproduce Finder focus. */
public final class LauncherTest extends Instrumentation {
    private UiAutomation ui;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();String previous="";try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        String[] lines=shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").trim().split("\\n");previous=lines[lines.length-1].trim();check(previous.matches("[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+"),"Could not retain the emulator home app");
        check(shell("cmd package set-home-activity app.voiceflow.fixture/.HomeFinder").contains("Success"),"Could not select fixture home app");
        shell("am start -W -n app.voiceflow.fixture/.HomeFinder -f 268468224");
        check(waitNode("Launcher finder").isFocused(),"Finder is not focused");waitHidden();Thread.sleep(1200);check(hidden(),"Focused Finder kept the voice controls visible");
        click("Launcher finder");waitMic();waitTyping(false);
        clickMic();waitText("Hello, world");waitFinish();clickFinish();waitNode("Floating Backspace");
        AccessibilityNodeInfo finder=waitNode("Launcher finder");
        check(finder.getText()!=null && finder.getText().toString().contains("Hello, world"),"Finder did not receive the transcript");
        click("Close finder");waitHidden();Thread.sleep(1000);check(waitNode("Launcher finder").isFocused() && hidden(),"Returning Home with stale focus kept controls visible");
        click("Launcher finder");waitMic();waitTyping(false);
        holdMic();waitTyping(true);waitNode("VoiceFlow: start dictation");waitKeyboardState("Keyboard visible");check(node("Floating Switch keyboard")==null && node("Hide voice controls")==null,"Finder hold exposed a redundant keyboard menu");
        click("Close finder");waitHidden();click("Launcher finder");waitMic();waitTyping(true);
        clickMic();waitFinish();clickFinish();waitNode("Floating Backspace");
        holdMic();waitTyping(false);waitNode("Floating keyboard microphone");waitKeyboardState("Keyboard hidden");
        click("Close finder");waitHidden();Thread.sleep(1000);check(hidden(),"Home retained the headless Finder controls");
        check(shell("cmd package set-home-activity "+previous).contains("Success"),"Could not restore the emulator home app");
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitNode("Floating keyboard microphone");waitTyping(false);
        result.putString("result","PASS: idle Home hides stale focused input; opening Finder shows controls and inserts transcripts; closing and reopening Finder works with typing keys both hidden and shown; saved keyboard choice persists; normal app dictation returns");
    }catch(Throwable error){result.putString("failure",android.util.Log.getStackTraceString(error));}finally{if(!previous.isEmpty() && previous.matches("[a-zA-Z0-9_.$]+/[a-zA-Z0-9_.$]+"))try{shell("cmd package set-home-activity "+previous);}catch(Exception ignored){}}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private String shell(String command)throws Exception{try(ParcelFileDescriptor.AutoCloseInputStream in=new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[1024];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toString("UTF-8");}}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    private boolean hidden(){return node("Floating keyboard microphone")==null && node("VoiceFlow: start dictation")==null && node("VoiceFlow floating keyboard")==null;}
    private void waitHidden()throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(hidden())return;Thread.sleep(150);}throw new Exception("Launcher did not hide voice controls");}
    private AccessibilityNodeInfo waitNode(String description)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo current=node(description);if(current!=null)return current;Thread.sleep(150);}throw new Exception("Missing "+description);}
    private String mic(){return node("Floating keyboard microphone")!=null?"Floating keyboard microphone":"VoiceFlow: start dictation";}
    private String finish(){return node("Finish keyboard dictation")!=null?"Finish keyboard dictation":"Finish dictation";}
    private void waitMic()throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(node(mic())!=null)return;Thread.sleep(150);}throw new Exception("Finder did not show dictation controls");}
    private void waitFinish()throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(node(finish())!=null)return;Thread.sleep(150);}throw new Exception("Finder did not start recording");}
    private void waitText(String text)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo field=node("Launcher finder");if(field!=null && field.getText()!=null && field.getText().toString().contains(text))return;Thread.sleep(150);}throw new Exception("Finder direct insertion failed");}
    private void click(String description)throws Exception{action(AccessibilityNodeInfo.ACTION_CLICK,description);}
    private void clickMic()throws Exception{action(AccessibilityNodeInfo.ACTION_CLICK,"Floating keyboard microphone","VoiceFlow: start dictation");}
    private void clickFinish()throws Exception{action(AccessibilityNodeInfo.ACTION_CLICK,"Finish keyboard dictation","Finish dictation");}
    private void holdMic()throws Exception{action(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Floating keyboard microphone","VoiceFlow: start dictation");}
    private void action(int action,String... descriptions)throws Exception{
        long end=SystemClock.elapsedRealtime()+45000;
        while(SystemClock.elapsedRealtime()<end){
            for(String description:descriptions){AccessibilityNodeInfo current=node(description);if(current!=null && current.performAction(action)){Thread.sleep(150);return;}}
            // Window trees can disappear between frames while the IME/overlay
            // changes. Retry a fresh node; do not turn that race into a null click.
            Thread.sleep(150);
        }
        throw new Exception("Could not perform "+action+" on "+java.util.Arrays.toString(descriptions));
    }
    private void waitTyping(boolean expected)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){boolean visible=false;for(AccessibilityWindowInfo window:ui.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect rect=new Rect();window.getBoundsInScreen(rect);if(rect.height()>80)visible=true;}if(visible==expected)return;Thread.sleep(150);}throw new Exception("Typing keyboard visibility expected "+expected);}
    private void waitKeyboardState(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo control=node(mic());if(control!=null && expected.contentEquals(control.getStateDescription()==null?"":control.getStateDescription()))return;Thread.sleep(150);}throw new Exception("Keyboard state is not accessible: "+expected);}
    private AccessibilityNodeInfo node(String description){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),description);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String description){if(node==null)return null;if(description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),description);if(found!=null)return found;}return null;}
}
