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
import android.widget.EditText;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.TextAttribute;

/** Native checks for automatic credential typing and explicit, read-free dictation. */
public final class AuthFieldTest extends Instrumentation {
    private UiAutomation ui;
    private int lastRequestedFocusId=-1;
    private boolean lastFocusRequestAccepted,lastTypingExpected,lastTypingObserved;
    private long lastFocusRequestUptime;
    private String lastTypingWindows="No keyboard visibility wait yet";
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle report=new Bundle();
        report.putString("retired_subcases","OBSOLETE, NOT RUN: blind native Delete through Floating Backspace on a mismatched credential editor. The minimal bubble intentionally has no floating editing controls; credential mismatch-refusal/privacy checks remain active.");
        try{
        ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
        getTargetContext().startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.EditorActivity").putExtra("auth_fields",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitNode("Floating keyboard microphone");waitTyping(false);

        focus(12350);waitTyping(true);waitNode("VoiceFlow: start dictation");waitState("Keyboard visible");
        click("VoiceFlow: start dictation",AccessibilityNodeInfo.ACTION_CLICK);waitNode("Finish dictation");waitWords(12350);click("Finish dictation",AccessibilityNodeInfo.ACTION_CLICK);waitIdleMainMic();
        check(node("Cancel dictation")==null,"Recording exposed an unnecessary Cancel button");
        focus(12345);waitNode("Floating keyboard microphone");waitTyping(false);check(text(12350).contains("Hello, world"),"Email dictation did not remain in its selected field");

        focus(12350);waitTyping(true);waitNode("VoiceFlow: start dictation");toggle();waitTyping(false);waitNode("Floating keyboard microphone");Thread.sleep(3500);waitTyping(false);waitState("Keyboard hidden");
        focus(12345);waitNode("Floating keyboard microphone");focus(12350);waitTyping(true);waitNode("VoiceFlow: start dictation");
        focus(12345);waitTyping(false);waitNode("Floating keyboard microphone");
        focus(12351);waitTyping(true);waitNode("VoiceFlow: start dictation");
        focus(12345);waitTyping(false);waitNode("Floating keyboard microphone");

        TrackingSecret password=replaceSecret(12352,"Password input",android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        focus(12352);waitTyping(true);waitNode("VoiceFlow: start dictation");assertNotRecording();
        prepareSecret(password,"prefix OLD suffix",7,10);FakeSpeech.credentialText="MiXeD p@ss";recordSecret(password,"prefix OLD suffix","prefix MiXeD p@ss suffix");
        focus(12345);waitTyping(false);waitNode("Floating keyboard microphone");

        TrackingSecret pin=replaceSecret(12353,"PIN input",android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        focus(12353);waitTyping(true);waitNode("VoiceFlow: start dictation");assertNotRecording();
        prepareSecret(pin,"88",1,1);FakeSpeech.credentialText="0 1 2 3";recordSecret(pin,"88","801238");
        prepareSecret(pin,"88",1,1);FakeSpeech.credentialText="zero one two three";recordSecret(pin,"88","801238");
        FakeSpeech.credentialText=null;focus(12354);waitTyping(false);waitNode("Floating keyboard microphone");

        // Some custom editors expose a password node with ordinary EditorInfo.
        // Dictation must refuse this mismatch and preserve the preexisting credential.
        // The old blind floating-Delete subcase is obsolete; it is not counted as passing.
        focus(12352);prepareSecret(password,"ab",2,2);FakeSpeech.credentialText="X";recordSecret(password,"ab","abX");
        runOnMainSync(()->{password.mismatchedInfo=true;((InputMethodManager)EditorActivity.current.getSystemService(Activity.INPUT_METHOD_SERVICE)).restartInput(password);});
        waitIdleMainMic();Thread.sleep(500);runOnMainSync(password::reset);
        check(text(12352).equals("abX"),"Mismatched credential setup changed the preexisting secret");
        Thread.sleep(350);int[] beforeReads={0};runOnMainSync(()->beforeReads[0]=password.contextReads);
        FakeSpeech.credentialText="must not insert";click("VoiceFlow: start dictation",AccessibilityNodeInfo.ACTION_CLICK);Thread.sleep(600);assertNotRecording();
        int[] afterReads={0},privateInfo={0};runOnMainSync(()->{afterReads[0]=password.contextReads;privateInfo[0]=password.reportedInputType;});
        check(privateInfo[0]==android.text.InputType.TYPE_CLASS_TEXT,"Fixture did not expose ordinary EditorInfo");
        check(afterReads[0]==beforeReads[0] && text(12352).equals("abX"),"Mismatched credential recording read or changed private context");
        assertSecretWrites(password,0);
        runOnMainSync(()->{password.mismatchedInfo=false;((InputMethodManager)EditorActivity.current.getSystemService(Activity.INPUT_METHOD_SERVICE)).restartInput(password);});

        // A changed secret selection must never be overwritten by a final result.
        focus(12352);waitTyping(true);waitNode("VoiceFlow: start dictation");prepareSecret(password,"Keep this secret",4,4);FakeSpeech.credentialText="spoken secret";
        click("VoiceFlow: start dictation",AccessibilityNodeInfo.ACTION_CLICK);waitNode("Finish dictation");Thread.sleep(400);
        runOnMainSync(()->password.setSelection(0));Thread.sleep(250);click("Finish dictation",AccessibilityNodeInfo.ACTION_CLICK);waitIdleMainMic();
        check(text(12352).equals("Keep this secret"),"A changed credential selection was overwritten");assertSecretWrites(password,0);waitSensitiveClip("spoken secret");
        FakeSpeech.credentialText=null;focus(12345);waitTyping(false);waitNode("Floating keyboard microphone");

        // A manual show choice still persists through protected fields.
        focus(12345);waitNode("Floating keyboard microphone");toggle();waitTyping(true);waitNode("VoiceFlow: start dictation");
        focus(12352);waitTyping(true);waitNode("VoiceFlow: start dictation");assertNotRecording();focus(12345);waitTyping(true);waitNode("VoiceFlow: start dictation");toggle();waitTyping(false);waitNode("Floating keyboard microphone");
        report.putString("result","PASS: email/password/PIN auto-open typing with a side mic and no automatic recording; email speech inserts directly; private password replacement preserves prefix/suffix and raw case; PIN digits and digit names preserve leading zero; credential dictation commits once on Finish without surrounding reads or whole-field replacement; mismatched private node/ordinary EditorInfo safely refuses recording without context reads, edits or changing the preexisting secret; selection changes preserve the secret and recover spoken text to sensitive clipboard; Finish returns the idle main mic without floating editing/menu controls; temporary defaults preserve the normal keyboard preference. Removed floating-Delete subcase is obsolete and was not run.");
    }catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));captureFailure(report);report.putString("failure_diagnostics",failureDiagnostics());}finally{FakeSpeech.credentialText=null;}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void focus(int id)throws Exception{runOnMainSync(()->{lastRequestedFocusId=id;lastFocusRequestUptime=SystemClock.uptimeMillis();EditText field=(EditText)EditorActivity.current.findViewById(id);field.requestRectangleOnScreen(new Rect(0,0,field.getWidth(),field.getHeight()),true);lastFocusRequestAccepted=field.requestFocus();});Thread.sleep(200);waitVisibleFocusedField(id);}
    /** Keyboard expectations apply only after the actual input is exposed to Accessibility. */
    private void waitVisibleFocusedField(int id)throws Exception{
        String[] description={null};runOnMainSync(()->description[0]=String.valueOf(EditorActivity.current.findViewById(id).getContentDescription()));
        long end=SystemClock.elapsedRealtime()+10000;String last="node missing";
        while(SystemClock.elapsedRealtime()<end){
            AccessibilityNodeInfo field=node(description[0]);
            if(field!=null)try{
                Rect bounds=new Rect();field.getBoundsInScreen(bounds);
                last="focused="+field.isFocused()+", visible="+field.isVisibleToUser()+", bounds="+bounds.toShortString();
                if(field.isFocused() && field.isVisibleToUser() && field.isEditable() && !bounds.isEmpty())return;
            }finally{field.recycle();}
            Thread.sleep(150);
        }
        throw new Exception("Fixture field "+id+" did not become accessibility-visible and focused before keyboard expectation: "+last);
    }
    private TrackingSecret replaceSecret(int id,String description,int inputType){TrackingSecret[] value={null};runOnMainSync(()->{EditText old=(EditText)EditorActivity.current.findViewById(id);ViewGroup parent=(ViewGroup)old.getParent();int index=parent.indexOfChild(old);android.view.ViewGroup.LayoutParams layout=old.getLayoutParams();parent.removeView(old);TrackingSecret field=new TrackingSecret(EditorActivity.current);field.setId(id);field.setContentDescription(description);field.setHint(description);field.setInputType(inputType);parent.addView(field,index,layout);value[0]=field;});return value[0];}
    private void prepareSecret(TrackingSecret field,String value,int start,int end)throws Exception{runOnMainSync(()->{field.setText(value);field.requestFocus();field.setSelection(start,end);InputMethodManager ime=(InputMethodManager)EditorActivity.current.getSystemService(Activity.INPUT_METHOD_SERVICE);ime.restartInput(field);ime.showSoftInput(field,InputMethodManager.SHOW_IMPLICIT);});waitTyping(true);waitNode("VoiceFlow: start dictation");Thread.sleep(450);runOnMainSync(field::reset);}
    private void recordSecret(TrackingSecret field,String before,String expected)throws Exception{click("VoiceFlow: start dictation",AccessibilityNodeInfo.ACTION_CLICK);waitNode("Finish dictation");Thread.sleep(850);check(text(field.getId()).equals(before),"Private partial speech changed the credential before Finish");click("Finish dictation",AccessibilityNodeInfo.ACTION_CLICK);long end=SystemClock.elapsedRealtime()+45000;while(!text(field.getId()).equals(expected) && SystemClock.elapsedRealtime()<end)Thread.sleep(150);check(text(field.getId()).equals(expected),"Native credential insertion did not preserve the existing prefix/selection/suffix");waitIdleMainMic();assertSecretWrites(field,1);}
    private void waitIdleMainMic()throws Exception{
        waitNode("VoiceFlow: start dictation");
        for(String removed:new String[]{"Floating Backspace","Floating Enter","More voice controls","Floating Switch keyboard","Hide voice controls"})
            check(!nodeExists(removed),"Minimal idle bubble exposed removed control: "+removed);
    }
    private void assertSecretWrites(TrackingSecret field,int expected)throws Exception{int[] counts={0,0,0,0};runOnMainSync(()->{counts[0]=field.commits;counts[1]=field.wholeFieldSets;counts[2]=field.selectionWrites;counts[3]=field.deletions;});check(counts[0]==expected && counts[1]==0 && counts[2]==0 && counts[3]==0,"Credential editing bypassed a single native commit: "+java.util.Arrays.toString(counts));}
    private void assertNotRecording()throws Exception{Thread.sleep(300);check(!nodeExists("Finish dictation") && !nodeExists("Finish keyboard dictation"),"Credential focus automatically started recording");}
    private void waitSensitiveClip(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getTargetContext().getSystemService(Activity.CLIPBOARD_SERVICE);android.content.ClipData clip=clipboard.getPrimaryClip();if(clip!=null && clip.getItemCount()>0 && expected.contentEquals(clip.getItemAt(0).coerceToText(getTargetContext())) && clip.getDescription().getExtras()!=null && clip.getDescription().getExtras().getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,false))return;Thread.sleep(150);}throw new Exception("Changed credential dictation was not recovered to a sensitive clipboard");}
    private String text(int id){String[] value={""};runOnMainSync(()->value[0]=((EditText)EditorActivity.current.findViewById(id)).getText().toString());return value[0];}
    private void waitWords(int id)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(text(id).contains("Hello, world"))return;Thread.sleep(150);}throw new Exception("Dictation did not reach email input");}
    private void toggle()throws Exception{String name=nodeExists("Floating keyboard microphone")?"Floating keyboard microphone":"VoiceFlow: start dictation";click(name,AccessibilityNodeInfo.ACTION_LONG_CLICK);}
    private void click(String name,int action)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo button=node(name);if(button!=null){boolean acted=button.performAction(action);button.recycle();if(acted)return;}Thread.sleep(150);}throw new Exception("Could not activate "+name);}
    private void waitState(String expected)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo button=node("Floating keyboard microphone");if(button==null)button=node("VoiceFlow: start dictation");if(button!=null){boolean matches=expected.contentEquals(button.getStateDescription()==null?"":button.getStateDescription());button.recycle();if(matches)return;}Thread.sleep(150);}throw new Exception("Keyboard indicator expected "+expected);}
    private void waitTyping(boolean expected)throws Exception{lastTypingExpected=expected;long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){boolean visible=false;StringBuilder windows=new StringBuilder();for(AccessibilityWindowInfo window:ui.getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect bounds=new Rect();window.getBoundsInScreen(bounds);if(bounds.height()>40)visible=true;if(windows.length()<2000)windows.append("id=").append(window.getId()).append(" bounds=").append(bounds.toShortString()).append(" focused=").append(window.isFocused()).append(" active=").append(window.isActive()).append(';');}lastTypingObserved=visible;lastTypingWindows=windows.toString();if(visible==expected)return;Thread.sleep(150);}throw new Exception("Typing keyboard visibility expected "+expected);}
    private void waitNode(String name)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){if(nodeExists(name))return;Thread.sleep(150);}throw new Exception("Missing "+name);}
    private boolean nodeExists(String name){AccessibilityNodeInfo found=node(name);if(found==null)return false;found.recycle();return true;}
    private AccessibilityNodeInfo node(String name){for(AccessibilityWindowInfo window:ui.getWindows()){AccessibilityNodeInfo found=find(window.getRoot(),name);if(found!=null)return found;}return null;}
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String name){if(node==null)return null;if(name.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),name);if(found!=null){node.recycle();return found;}}node.recycle();return null;}
    /** Diagnostic-only capture of this disposable synthetic fixture, before cleanup changes its state. */
    private void captureFailure(Bundle report){
        android.graphics.Bitmap bitmap=null;
        try{
            if(ui==null)throw new java.io.IOException("UiAutomation unavailable");
            bitmap=ui.takeScreenshot();if(bitmap==null)throw new java.io.IOException("Failure screenshot unavailable");
            java.io.File[] dirs=getTargetContext().getExternalMediaDirs();
            if(dirs==null||dirs.length==0||dirs[0]==null)throw new java.io.IOException("Failure screenshot directory unavailable");
            if(!dirs[0].isDirectory()&&!dirs[0].mkdirs())throw new java.io.IOException("Cannot create failure screenshot directory");
            java.io.File output=new java.io.File(dirs[0],"auth-fields-failure-"+SystemClock.elapsedRealtime()+".png");
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)){if(!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))throw new java.io.IOException("Cannot save failure screenshot");}
            report.putString("failure_screenshot",output.getAbsolutePath());
        }catch(Throwable failure){report.putString("failure_screenshot_error",failure.toString());}
        finally{if(bitmap!=null)bitmap.recycle();}
    }
    private String failureDiagnostics(){
        StringBuilder out=new StringBuilder();long until=SystemClock.elapsedRealtime()+5000;
        out.append("lastRequestedFocusId=").append(lastRequestedFocusId).append(" requestFocusAccepted=").append(lastFocusRequestAccepted)
                .append(" focusRequestAgeMs=").append(SystemClock.uptimeMillis()-lastFocusRequestUptime)
                .append(" typingExpected=").append(lastTypingExpected).append(" typingObserved=").append(lastTypingObserved)
                .append(" lastImeWindows=").append(lastTypingWindows).append('\n');
        try{
            out.append("selectedIme=").append(android.provider.Settings.Secure.getString(getTargetContext().getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)).append('\n');
            InputMethodManager manager=(InputMethodManager)getTargetContext().getSystemService(Activity.INPUT_METHOD_SERVICE);
            int imes=0;for(android.view.inputmethod.InputMethodInfo method:manager.getEnabledInputMethodList()){
                if(imes++>=20){out.append("[enabled IME identities truncated]\n");break;}
                out.append("enabledIme=").append(method.getId()).append('\n');
            }
        }catch(Throwable failure){out.append("imeDiagnosticError=").append(failure).append('\n');}
        try{runOnMainSync(()->{
            EditorActivity activity=EditorActivity.current;
            if(activity==null){out.append("EditorActivity.current=null\n");return;}
            android.view.View focused=activity.getCurrentFocus();
            out.append("activityWindowFocus=").append(activity.hasWindowFocus()).append(" activityDestroyed=").append(activity.isDestroyed())
                    .append(" currentFocusId=").append(focused==null?-1:focused.getId()).append('\n');
            for(int id:new int[]{12345,12350,12351,12352,12353,12354}){
                android.view.View view=activity.findViewById(id);
                if(view==null){out.append("field id=").append(id).append(" missing\n");continue;}
                Rect visible=new Rect();boolean visibleRect=view.getLocalVisibleRect(visible);int[] xy=new int[2];view.getLocationOnScreen(xy);
                if(visibleRect)visible.offset(xy[0]-view.getScrollX(),xy[1]-view.getScrollY());
                out.append("field id=").append(id).append(" description=").append(shortDiagnostic(view.getContentDescription()))
                        .append(" focused=").append(view.isFocused()).append(" shown=").append(view.isShown()).append(" enabled=").append(view.isEnabled())
                        .append(" attached=").append(view.isAttachedToWindow()).append(" visibleRect=").append(visibleRect?visible.toShortString():"none");
                if(view instanceof EditText){EditText field=(EditText)view;out.append(" inputType=0x").append(Integer.toHexString(field.getInputType()))
                        .append(" inputClass=").append(field.getInputType()&android.text.InputType.TYPE_MASK_CLASS)
                        .append(" inputVariation=0x").append(Integer.toHexString(field.getInputType()&android.text.InputType.TYPE_MASK_VARIATION))
                        .append(" selection=").append(field.getSelectionStart()).append(':').append(field.getSelectionEnd());}
                if(view instanceof TrackingSecret){TrackingSecret field=(TrackingSecret)view;out.append(" reportedEditorInfo=0x").append(Integer.toHexString(field.reportedInputType))
                        .append(" commits=").append(field.commits).append(" contextReads=").append(field.contextReads)
                        .append(" selectionWrites=").append(field.selectionWrites).append(" wholeFieldSets=").append(field.wholeFieldSets).append(" deletions=").append(field.deletions);}
                out.append('\n');
            }
        });}catch(Throwable failure){out.append("fieldDiagnosticError=").append(failure).append('\n');}
        try{
            int[] remaining={160};int windows=0;
            if(ui!=null)for(AccessibilityWindowInfo window:ui.getWindows()){
                if(windows++>=10||SystemClock.elapsedRealtime()>until||out.length()>32000){out.append("[window diagnostics truncated]\n");break;}
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);
                out.append("window id=").append(window.getId()).append(" type=").append(window.getType()).append(" focused=").append(window.isFocused())
                        .append(" active=").append(window.isActive()).append(" bounds=").append(bounds.toShortString()).append(" title=").append(shortDiagnostic(window.getTitle())).append('\n');
                AccessibilityNodeInfo root=window.getRoot();
                if(root==null){out.append("root=null\n");continue;}
                try{
                    AccessibilityNodeInfo focus=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                    if(focus==null)out.append("windowInputFocus=null\n");else try{out.append("windowInputFocus ");appendNodeMetadata(focus,out);}finally{focus.recycle();}
                    dumpDiagnosticNodes(root,out,0,remaining,until);
                }finally{root.recycle();}
            }
        }catch(Throwable failure){out.append("windowDiagnosticError=").append(failure).append('\n');}
        return out.toString();
    }
    private void appendNodeMetadata(AccessibilityNodeInfo node,StringBuilder out){
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        out.append("package=").append(node.getPackageName()).append(" class=").append(node.getClassName()).append(" id=").append(node.getViewIdResourceName())
                .append(" description=").append(shortDiagnostic(node.getContentDescription())).append(" inputType=0x").append(Integer.toHexString(node.getInputType()))
                .append(" editable=").append(node.isEditable()).append(" password=").append(node.isPassword()).append(" focused=").append(node.isFocused())
                .append(" visible=").append(node.isVisibleToUser()).append(" enabled=").append(node.isEnabled()).append(" bounds=").append(bounds.toShortString())
                .append(" state=").append(shortDiagnostic(node.getStateDescription())).append('\n');
    }
    private void dumpDiagnosticNodes(AccessibilityNodeInfo node,StringBuilder out,int depth,int[] remaining,long until){
        if(remaining[0]--<=0||depth>18||out.length()>32000||SystemClock.elapsedRealtime()>until){out.append("[node diagnostics truncated]\n");return;}
        out.append("node depth=").append(depth).append(' ');appendNodeMetadata(node,out);
        for(int i=0;i<node.getChildCount();i++){
            if(remaining[0]<=0||out.length()>32000||SystemClock.elapsedRealtime()>until){out.append("[remaining nodes truncated]\n");break;}
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{dumpDiagnosticNodes(child,out,depth+1,remaining,until);}finally{child.recycle();}
        }
    }
    private String shortDiagnostic(CharSequence value){if(value==null)return "null";String text=value.toString().replace('\n',' ');return text.length()>160?text.substring(0,160)+"…":text;}
    private void check(boolean okay,String message)throws Exception{if(!okay)throw new Exception(message);}
    private static final class TrackingSecret extends EditText {
        int commits,wholeFieldSets,selectionWrites,deletions,keyEvents,contextReads,reportedInputType;boolean mismatchedInfo;
        TrackingSecret(Activity activity){super(activity);}
        void reset(){commits=0;wholeFieldSets=0;selectionWrites=0;deletions=0;keyEvents=0;contextReads=0;}
        @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SET_TEXT){wholeFieldSets++;return false;}return super.performAccessibilityAction(action,args);}
        @Override public InputConnection onCreateInputConnection(EditorInfo info){InputConnection base=super.onCreateInputConnection(info);if(mismatchedInfo)info.inputType=android.text.InputType.TYPE_CLASS_TEXT;reportedInputType=info.inputType;return base==null?null:new InputConnectionWrapper(base,false){
            @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){contextReads++;return null;}
            @Override public CharSequence getTextBeforeCursor(int count,int flags){contextReads++;return null;}
            @Override public CharSequence getTextAfterCursor(int count,int flags){contextReads++;return null;}
            @Override public CharSequence getSelectedText(int flags){contextReads++;return null;}
            @Override public android.view.inputmethod.ExtractedText getExtractedText(android.view.inputmethod.ExtractedTextRequest request,int flags){contextReads++;return null;}
            @Override public boolean sendKeyEvent(android.view.KeyEvent event){keyEvents++;return super.sendKeyEvent(event);}
            @Override public boolean setSelection(int start,int end){selectionWrites++;return super.setSelection(start,end);}
            @Override public boolean deleteSurroundingText(int before,int after){deletions++;return super.deleteSurroundingText(before,after);}
            @Override public boolean commitText(CharSequence text,int position){commits++;return super.commitText(text,position);}
            @Override public boolean commitText(CharSequence text,int position,TextAttribute attribute){commits++;return super.commitText(text,position,attribute);}
        };}
    }
}
