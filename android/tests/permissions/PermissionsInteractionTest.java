package app.voiceflow;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Physical first taps on production views; external Activity starts are intercepted, not completed. */
public final class PermissionsInteractionTest extends Instrumentation {
    private static final String REQUEST_PERMISSIONS="android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String PERMISSION_NAMES="android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    private static final String DISCLOSURE="Use the floating microphone";
    private volatile MainActivity main;
    private Context context;
    private UiAutomation automation;
    private SharedPreferences prefs,activityPrefs;
    private Map<String,?> seededPreferences;
    private Bundle arguments;
    private IntentTrap trap;
    private final AtomicInteger requests=new AtomicInteger();
    private int checks,taps;
    private volatile int resumes;
    private String phase="starting",microphoneStage="not run",keyboardStage="not run";
    private String initialIme,initialAccessibility;
    private Set<String> initialEnabledImes;
    private int initialMic;
    private boolean externalWindowOpen;
    private Set<Integer> pickerInitialWindows=new HashSet<>();
    private List<String> pickerExpectedLabels=new ArrayList<>();
    private String pickerLastObservation="Picker not inspected";

    @Override public void onCreate(Bundle args){arguments=args==null?new Bundle():args;super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){if(activity instanceof MainActivity)main=(MainActivity)activity;super.callActivityOnCreate(activity,state);}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MainActivity)resumes++;}
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();prefs=Store.prefs(context);
        activityPrefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
        Map<String,?> original=new HashMap<>(prefs.getAll()),originalActivity=new HashMap<>(activityPrefs.getAll());
        String originalDraft=MainActivity.currentDraft;
        boolean seeded=false;
        try{
            require("true".equals(arguments.getString("disposable")),"Run only on a disposable installation with -e disposable true");
            initialMic=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
            initialIme=secure(Settings.Secure.DEFAULT_INPUT_METHOD);
            initialEnabledImes=enabledImeIdentities();
            initialAccessibility=secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")||protocol.equals("http")?new URLStreamHandler(){
                @Override protected URLConnection openConnection(URL url)throws IOException{requests.incrementAndGet();throw new IOException("Synthetic Permissions fixture blocks HTTP/HTTPS");}
            }:null);
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info=automation.getServiceInfo();
            info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            automation.setServiceInfo(info);
            // Offline, empty history and no connected accounts: no real recordings, contacts or requests.
            seeded=true;
            require(prefs.edit().clear().putString("history","[]").putString("engine","offline")
                    .putString("transcribe_model","synthetic-permissions-transcription")
                    .putString("polish_model","synthetic-permissions-cleanup")
                    .putString("language","bn-BD").putString("dictionary","synthetic=Permissions fixture")
                    .putString("bubble_size","large").putBoolean("bubble",false)
                    .putBoolean("haptic_feedback",false).putBoolean("daily_summaries",false)
                    .putString("backup_frequency","off").commit(),"Could not seed synthetic local preferences");
            DraftState.set("Synthetic permissions fixture retained draft");
            setInTouchMode(true);
            main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();waitFocused();
            require(main.getPreferences(Context.MODE_PRIVATE).getAll().equals(activityPrefs.getAll()),"Unexpected Activity private-preference store");
            seededPreferences=new HashMap<>(prefs.getAll());
            trap=new IntentTrap();addMonitor(trap);
            navigate();
            assertCurrentStatuses();
            capture("permissions-initial.png");
            microphone();
            floating();
            keyboard();
            progress("Native recreation preserves the Permissions panel and unchanged synthetic settings");
            recreateMain();assertPanel();assertCurrentStatuses();unchanged("Activity recreation");
            capture("permissions-returned.png");
            singleTap(waitDescription("Back to Settings"));waitDescription("Back to Home");
            View row=waitDescription("Permissions");
            await(()->{boolean[] focus={false};runOnMainSync(()->focus[0]=row.isFocused());return focus[0];},"Settings did not restore focus to the Permissions row");
            unchanged("Returning to Settings");
            assertOsUnchanged();require(requests.get()==0,"Fixture attempted an HTTP/HTTPS request");
            report.putString("result","PASS: physical first taps on production Permissions controls; external Activity intents intercepted and canceled. Actual floating disclosure Cancel and focus return, native recreation, current-OS row statuses, and unchanged app/OS settings checked. No Android permission grant or Settings-page completion claimed.");
        }catch(Throwable failure){
            report.putString("failure",android.util.Log.getStackTraceString(failure));
            captureFailure(report);
            report.putString("phase",phase);report.putString("native_control_tree",diagnostics());
            report.putString("picker_initial_window_ids",pickerInitialWindows.toString());
            report.putString("picker_expected_ime_labels",pickerExpectedLabels.toString());
            report.putString("picker_last_observation",pickerLastObservation);
        }finally{
            try{
                if(externalWindowOpen){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();}
                if(trap!=null)removeMonitor(trap);
                if(main!=null&&!main.isDestroyed())runOnMainSync(()->main.finish());
                waitForIdleSync();
            }catch(Throwable failure){addCleanupFailure(report,"Activity/window cleanup",failure);}
            if(seeded)try{
                restore(prefs,original);restore(activityPrefs,originalActivity);DraftState.set(originalDraft);DriveBackup.schedule(context);
                if(!original.equals(prefs.getAll())||!originalActivity.equals(activityPrefs.getAll())||!java.util.Objects.equals(originalDraft,MainActivity.currentDraft))throw new IOException("Original local data did not restore exactly");
            }catch(Throwable failure){addCleanupFailure(report,"Local-data cleanup",failure);}
            report.putInt("executed_checks",checks);report.putInt("physical_taps",taps);
            report.putString("microphone_stage",microphoneStage);report.putString("keyboard_stage",keyboardStage);
            report.putInt("intercepted_activity_starts",trap==null?0:trap.count());report.putInt("http_https_attempts",requests.get());
            report.putString("coverage_limit","External Activity requests are intercepted by Instrumentation.ActivityMonitor and returned RESULT_CANCELED. No app-details page, runtime permission dialog, Android Accessibility setup, keyboard enabling, or permission grant is completed. Native focus/recreation checks do not simulate changed OS permission grants.");
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void navigate()throws Exception{
        progress("One physical first tap: Home Settings, then Permissions");
        singleTap(waitDescription("Settings"));waitDescription("Back to Home");
        View permissions=waitDescription("Permissions");assertFirstTapControl(permissions,"Permissions");
        singleTap(permissions);assertPanel();unchanged("Opening Permissions");
    }
    private void microphone()throws Exception{
        boolean granted=context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        boolean asked=activityPrefs.getBoolean("microphone_requested",false);
        boolean[] rationale={false};runOnMainSync(()->rationale[0]=main.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO));
        boolean details=granted||(asked&&!rationale[0]);
        progress("One physical first Microphone tap: intercepted "+(details?"app-details intent":"runtime permission request"));
        Map<String,?> before=new HashMap<>(activityPrefs.getAll());int previous=trap.count();
        View control=waitDescription("Microphone");assertFirstTapControl(control,"Microphone");singleTap(control);
        Intent outgoing=waitOneIntent(previous);
        if(details){
            require(Settings.ACTION_APPLICATION_DETAILS_SETTINGS.equals(outgoing.getAction()),"Microphone did not request app details for granted/permanently-denied state");
            require(("package:"+context.getPackageName()).equals(outgoing.getDataString()),"App-details intent does not target this installed app");
            require(before.equals(activityPrefs.getAll()),"App-details action changed microphone-request tracking");
            microphoneStage="PASS intercepted app-details intent; initial microphone "+(granted?"granted":"previously requested without rationale")+". Actual Android page not opened.";
        }else{
            require(REQUEST_PERMISSIONS.equals(outgoing.getAction()),"Microphone did not request the Android runtime-permission flow");
            String[] names=outgoing.getStringArrayExtra(PERMISSION_NAMES);
            require(names!=null&&names.length==1&&Manifest.permission.RECORD_AUDIO.equals(names[0]),"Permission request was not exactly RECORD_AUDIO");
            Map<String,Object> expected=new HashMap<>();expected.putAll(before);expected.put("microphone_requested",true);
            require(expected.equals(activityPrefs.getAll()),"Runtime permission request changed unexpected private app state");
            microphoneStage="PASS intercepted single RECORD_AUDIO runtime request; canceled synthetic result. Android permission dialog and grant not exercised.";
        }
        assertPanel();assertCurrentStatuses();unchanged("Microphone action");assertOsUnchanged();
    }
    private void floating()throws Exception{
        progress("One physical first Floating microphone tap opens real disclosure; physical Cancel preserves panel and refreshes rows");
        int previous=trap.count();View original=waitDescription("Floating microphone");assertFirstTapControl(original,"Floating microphone");
        singleTap(original);waitDisclosure();require(trap.count()==previous,"Opening disclosure unexpectedly launched an external Activity");
        capture("permissions-floating-disclosure.png");
        externalWindowOpen=true;
        singleTapDialogButton("android:id/button2");waitDisclosureGone();externalWindowOpen=false;waitFocused();
        await(()->findDescription("Floating microphone")!=original,"Disclosure dismissal did not refresh the native Permissions rows on focus return");
        assertPanel();assertCurrentStatuses();unchanged("Disclosure Cancel");require(trap.count()==previous,"Cancel launched Android Settings");

        progress("Floating disclosure can reopen; positive button emits one intercepted Accessibility Settings intent");
        singleTap(waitDescription("Floating microphone"));waitDisclosure();externalWindowOpen=true;
        singleTapDialogButton("android:id/button1");waitDisclosureGone();externalWindowOpen=false;waitFocused();
        Intent outgoing=waitOneIntent(previous);
        require(Settings.ACTION_ACCESSIBILITY_SETTINGS.equals(outgoing.getAction()),"Open settings did not emit Android Accessibility Settings intent");
        assertPanel();assertCurrentStatuses();unchanged("Disclosure Open settings (intercepted)");assertOsUnchanged();
    }
    private void keyboard()throws Exception{
        InputMethodManager manager=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);
        boolean enabled=keyboardEnabled();int previous=trap.count();
        progress("One physical first optional-keyboard tap: "+(enabled?"actual Android picker, dismissed without selection":"intercepted input-method Settings intent"));
        View original=waitDescription("VoiceFlow keyboard");assertFirstTapControl(original,"VoiceFlow keyboard");
        if(!enabled){
            singleTap(original);Intent outgoing=waitOneIntent(previous);
            require(Settings.ACTION_INPUT_METHOD_SETTINGS.equals(outgoing.getAction()),"Disabled keyboard did not request Android input-method Settings");
            keyboardStage="PASS intercepted input-method Settings intent for disabled VoiceFlow IME. Actual Android enable screen not opened.";
        }else{
            Set<Integer> existing=windowIds();List<String> labels=new ArrayList<>();
            for(InputMethodInfo method:manager.getEnabledInputMethodList())labels.add(method.loadLabel(context.getPackageManager()).toString());
            require(!labels.isEmpty(),"No enabled keyboard labels available for system-picker identification");
            pickerInitialWindows=new HashSet<>(existing);pickerExpectedLabels=new ArrayList<>(labels);
            singleTap(original);externalWindowOpen=true;
            int picker=waitPicker(existing,labels);
            require(trap.count()==previous,"Enabled keyboard unexpectedly launched an Activity instead of Android's picker");
            require(!hasFocus(),"System picker did not take focus from the app");
            capture("permissions-android-keyboard-picker.png");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
            await(()->!windowIds().contains(picker),"Back did not close the Android input-method picker");
            externalWindowOpen=false;waitFocused();
            await(()->findDescription("VoiceFlow keyboard")!=original,"Returning from the actual picker did not refresh the Permissions row");
            keyboardStage="PASS actual Android keyboard-picker window identified by dynamically loaded installed IME labels, dismissed with Back without selecting/changing IME. No keyboard enabled or selected.";
        }
        assertPanel();assertCurrentStatuses();unchanged("Optional keyboard return");assertOsUnchanged();
    }

    private final class IntentTrap extends ActivityMonitor{
        private final List<Intent> intents=new ArrayList<>();
        @Override public ActivityResult onStartActivity(Intent intent){synchronized(intents){intents.add(new Intent(intent));}return new ActivityResult(Activity.RESULT_CANCELED,new Intent());}
        int count(){synchronized(intents){return intents.size();}}
        Intent get(int index){synchronized(intents){return new Intent(intents.get(index));}}
    }
    private Intent waitOneIntent(int previous)throws Exception{
        await(()->trap.count()>previous,"First physical tap did not emit an external Activity request");
        waitForIdleSync();SystemClock.sleep(200);require(trap.count()==previous+1,"One physical tap emitted multiple external Activity requests");return trap.get(previous);
    }
    private void assertPanel()throws Exception{waitDescription("Back to Settings");require(findText("Permissions")!=null,"Permissions panel title missing");require(findDescription("Microphone")!=null&&findDescription("Floating microphone")!=null&&findDescription("VoiceFlow keyboard")!=null,"Permissions panel lost action rows");}
    private void assertCurrentStatuses()throws Exception{
        assertRowState("Microphone",context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"Allowed":"Not allowed");
        String enabled=secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);boolean floating=false;
        ComponentName own=new ComponentName(context,FlowAccessibility.class);
        if(enabled!=null)for(String entry:enabled.split(":"))if(own.equals(ComponentName.unflattenFromString(entry)))floating=true;
        assertRowState("Floating microphone",FlowAccessibility.instance!=null?"Connected":floating?"Not connected":"Not enabled");
        String selected=secure(Settings.Secure.DEFAULT_INPUT_METHOD);ComponentName component=selected==null?null:ComponentName.unflattenFromString(selected);
        assertRowState("VoiceFlow keyboard",component!=null&&component.getPackageName().equals(context.getPackageName())?"Selected":keyboardEnabled()?"Enabled":"Not enabled");
    }
    private void assertRowState(String label,String expected)throws Exception{
        View row=waitDescription(label);boolean[] okay={false};runOnMainSync(()->okay[0]=findView(row,expected,null)!=null&&(Build.VERSION.SDK_INT<30||expected.equals(String.valueOf(row.getStateDescription()))));
        require(okay[0],"Native "+label+" state does not match current OS state: "+expected);
    }
    private boolean keyboardEnabled(){for(InputMethodInfo method:((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).getEnabledInputMethodList())if(context.getPackageName().equals(method.getPackageName()))return true;return false;}
    /** Public API snapshot of enabled IME components; independent of labels, order and restricted secure settings. */
    private Set<String> enabledImeIdentities(){
        Set<String> identities=new java.util.TreeSet<>();
        for(InputMethodInfo method:((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).getEnabledInputMethodList())
            identities.add(new ComponentName(method.getPackageName(),method.getServiceName()).flattenToString());
        return identities;
    }
    private String secure(String key){return Settings.Secure.getString(context.getContentResolver(),key);}
    private void unchanged(String source)throws Exception{require(seededPreferences.equals(prefs.getAll()),source+" changed saved settings/history");require("Synthetic permissions fixture retained draft".equals(MainActivity.currentDraft),source+" changed the retained draft");}
    private void assertOsUnchanged()throws Exception{
        require(initialMic==context.checkSelfPermission(Manifest.permission.RECORD_AUDIO),"Fixture changed microphone permission");
        require(java.util.Objects.equals(initialIme,secure(Settings.Secure.DEFAULT_INPUT_METHOD)),"Fixture changed the selected keyboard");
        require(initialEnabledImes.equals(enabledImeIdentities()),"Fixture changed enabled keyboards");
        require(java.util.Objects.equals(initialAccessibility,secure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)),"Fixture changed enabled Accessibility services");
    }
    private void recreateMain()throws Exception{MainActivity previous=main;int before=resumes;runOnMainSync(previous::recreate);await(()->main!=previous&&resumes>before,"Android did not recreate and resume MainActivity");waitForIdleSync();waitFocused();require(main!=previous&&!main.isDestroyed(),"Recreated Activity is not live");}
    private void assertFirstTapControl(View control,String label)throws Exception{boolean[] okay={false};runOnMainSync(()->okay[0]=control.isEnabled()&&control.isClickable()&&!control.isFocusableInTouchMode());require(okay[0],label+" is disabled, non-clickable, or consumes first touch for focus");}
    /** Exactly one touch DOWN/UP. No retry, performClick, or accessibility click. */
    private void singleTap(View control)throws Exception{
        require(control!=null,"Missing physical-tap control");runOnMainSync(()->control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true));waitForIdleSync();
        Rect bounds=new Rect();boolean[] usable={false};runOnMainSync(()->{
            usable[0]=control.isShown()&&control.isEnabled()&&control.getLocalVisibleRect(bounds);
            // Local visible rect includes the control's own scroll offset.
            if(usable[0]){int[] xy=new int[2];control.getLocationOnScreen(xy);bounds.offset(xy[0]-control.getScrollX(),xy[1]-control.getScrollY());}
        });
        require(usable[0]&&!bounds.isEmpty(),"Control not physically tappable in "+phase);tap(bounds);
    }
    private void tap(Rect bounds)throws Exception{long down=SystemClock.uptimeMillis();inject(MotionEvent.ACTION_DOWN,down,bounds.centerX(),bounds.centerY());SystemClock.sleep(75);inject(MotionEvent.ACTION_UP,down,bounds.centerX(),bounds.centerY());taps++;waitForIdleSync();}
    private void inject(int action,long down,int x,int y)throws Exception{MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{require(automation.injectInputEvent(event,true),"Android rejected a physical touchscreen event");}finally{event.recycle();}}
    private View waitDescription(String label)throws Exception{await(()->findDescription(label)!=null,"Expected native control missing: "+label);return findDescription(label);}
    private View findDescription(String label){return find(null,label);}
    private View findText(String label){return find(label,null);}
    private View find(String text,String description){View[] found={null};runOnMainSync(()->{if(main!=null)found[0]=findView(main.getWindow().getDecorView(),text,description);});return found[0];}
    private View findView(View view,String text,String description){if(description!=null&&description.equals(String.valueOf(view.getContentDescription())))return view;if(text!=null&&view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findView(group.getChildAt(i),text,description);if(found!=null)return found;}}return null;}
    private boolean hasFocus(){boolean[] focus={false};runOnMainSync(()->focus[0]=main!=null&&main.hasWindowFocus());return focus[0];}
    private void waitFocused()throws Exception{await(this::hasFocus,"MainActivity did not regain window focus");waitForIdleSync();}
    private interface Condition{boolean okay()throws Exception;}
    private void await(Condition condition,String failure)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(condition.okay()){waitForIdleSync();return;}SystemClock.sleep(80);}throw new IOException(failure);}

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,String text,String id){
        if(node==null)return null;
        if(node.isVisibleToUser()&&((text!=null&&text.equals(String.valueOf(node.getText())))||(id!=null&&id.equals(node.getViewIdResourceName()))))return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;AccessibilityNodeInfo found=findNode(child,text,id);child.recycle();if(found!=null)return found;}return null;
    }
    private AccessibilityNodeInfo disclosureNode(String text,String id){
        for(AccessibilityWindowInfo window:automation.getWindows()){
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            AccessibilityNodeInfo title=findNode(root,DISCLOSURE,null);
            if(title!=null){title.recycle();AccessibilityNodeInfo found=findNode(root,text,id);root.recycle();if(found!=null)return found;}
            else root.recycle();
        }return null;
    }
    private boolean disclosureShown(){AccessibilityNodeInfo node=disclosureNode(DISCLOSURE,null);if(node==null)return false;node.recycle();return true;}
    private void waitDisclosure()throws Exception{await(this::disclosureShown,"One first physical Floating microphone tap did not open disclosure");require(!hasFocus(),"Disclosure did not take Activity window focus");}
    private void waitDisclosureGone()throws Exception{await(()->!disclosureShown(),"Actual floating disclosure did not dismiss");}
    private void singleTapDialogButton(String resourceId)throws Exception{AccessibilityNodeInfo node=disclosureNode(null,resourceId);require(node!=null,"Native disclosure button missing: "+resourceId);Rect bounds=new Rect();boolean usable=node.isEnabled()&&node.isClickable();node.getBoundsInScreen(bounds);node.recycle();require(usable&&!bounds.isEmpty(),"Disclosure button not physically tappable");tap(bounds);}
    private Set<Integer> windowIds(){Set<Integer> ids=new HashSet<>();for(AccessibilityWindowInfo window:automation.getWindows())ids.add(window.getId());return ids;}
    private int waitPicker(Set<Integer> old,List<String> labels)throws Exception{
        final int[] result={-1};
        await(()->{StringBuilder observed=new StringBuilder();for(AccessibilityWindowInfo window:automation.getWindows()){
            observed.append("window=").append(window.getId()).append(" old=").append(old.contains(window.getId()))
                    .append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused());
            if(old.contains(window.getId())||(!window.isActive()&&!window.isFocused())){observed.append(" skipped\n");continue;}
            AccessibilityNodeInfo root=window.getRoot();if(root==null){observed.append(" root=null\n");continue;}
            boolean external=!context.getPackageName().equals(String.valueOf(root.getPackageName()));boolean labelFound=false;
            if(external)for(String label:labels){AccessibilityNodeInfo found=findNode(root,label,null);if(found!=null){labelFound=true;found.recycle();break;}}
            observed.append(" package=").append(root.getPackageName());
            boolean list=containsList(root);root.recycle();
            observed.append(" external=").append(external).append(" imeLabelFound=").append(labelFound).append(" selectionListFound=").append(list).append('\n');
            if(external&&labelFound&&list){result[0]=window.getId();pickerLastObservation=observed.toString();return true;}
        }pickerLastObservation=observed.toString();return false;},"First keyboard tap did not show a new system picker window with an installed IME label and selection list");
        require(result[0]>=0,"System input-method picker was not identified");return result[0];
    }
    private boolean containsList(AccessibilityNodeInfo node){if(node==null)return false;CharSequence type=node.getClassName();if(type!=null&&(type.toString().contains("ListView")||type.toString().contains("RecyclerView")))return true;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;boolean found=containsList(child);child.recycle();if(found)return true;}return false;}
    private void capture(String filename)throws Exception{waitForIdleSync();Bitmap bitmap=automation.takeScreenshot();require(bitmap!=null,"Native display capture failed");File[] directories=context.getExternalMediaDirs();require(directories!=null&&directories.length>0&&directories[0]!=null,"Screenshot media directory unavailable");File directory=directories[0];require(directory.isDirectory()||directory.mkdirs(),"Could not create screenshot directory");File output=new File(directory,filename);try(FileOutputStream stream=new FileOutputStream(output)){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream),"Screenshot export failed");}finally{bitmap.recycle();}require(output.length()>0,"Native screenshot was empty");}
    private void progress(String value){phase=value;Bundle status=new Bundle();status.putString("phase",value);sendStatus(0,status);}
    private void require(boolean okay,String message)throws Exception{checks++;if(!okay)throw new IOException(message);}
    /** Failure capture is best-effort and does not add assertions or interact with the visible window. */
    private void captureFailure(Bundle report){
        Bitmap bitmap=null;
        try{
            if(automation==null)throw new IOException("UiAutomation was not initialized");
            bitmap=automation.takeScreenshot();if(bitmap==null)throw new IOException("Failure screenshot unavailable");
            File[] locations=context.getExternalMediaDirs();
            if(locations==null||locations.length==0||locations[0]==null)throw new IOException("Failure screenshot directory unavailable");
            File directory=locations[0];if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create failure screenshot directory");
            File output=new File(directory,"permissions-failure-"+SystemClock.elapsedRealtime()+".png");
            try(FileOutputStream stream=new FileOutputStream(output)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("Cannot save failure screenshot");}
            report.putString("failure_screenshot",output.getAbsolutePath());
        }catch(Throwable failure){report.putString("failure_screenshot_error",failure.toString());}
        finally{if(bitmap!=null)bitmap.recycle();}
    }
    private String diagnostics(){StringBuilder result=new StringBuilder();try{
        runOnMainSync(()->{if(main!=null){result.append("mainWindowFocused=").append(main.hasWindowFocus()).append('\n');dump(main.getWindow().getDecorView(),result);}});
        int[] remaining={250};
        if(automation!=null)for(AccessibilityWindowInfo window:automation.getWindows()){
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);
            result.append("window id=").append(window.getId()).append(" type=").append(window.getType()).append(" layer=").append(window.getLayer())
                    .append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused())
                    .append(" accessibilityFocused=").append(window.isAccessibilityFocused()).append(" initialPickerId=").append(pickerInitialWindows.contains(window.getId()))
                    .append(" title=").append(window.getTitle()).append(" bounds=").append(bounds.toShortString()).append('\n');
            AccessibilityNodeInfo root=window.getRoot();
            if(root==null)result.append("  root=null\n");
            else try{dumpAccessibility(root,result,0,remaining);}finally{root.recycle();}
        }
    }catch(Throwable failure){result.append("diagnostic_error=").append(failure);}return result.toString();}
    private void dumpAccessibility(AccessibilityNodeInfo node,StringBuilder result,int depth,int[] remaining){
        if(remaining[0]--<=0||depth>24||result.length()>48000){result.append("  [node diagnostics truncated]\n");return;}
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        result.append("  node depth=").append(depth).append(" package=").append(node.getPackageName()).append(" class=").append(node.getClassName())
                .append(" id=").append(node.getViewIdResourceName()).append(" visible=").append(node.isVisibleToUser())
                .append(" enabled=").append(node.isEnabled()).append(" clickable=").append(node.isClickable()).append(" selected=").append(node.isSelected())
                .append(" checked=").append(node.isChecked()).append(" bounds=").append(bounds.toShortString())
                .append(" text=").append(diagnosticText(node.getText())).append(" description=").append(diagnosticText(node.getContentDescription()))
                .append(" children=").append(node.getChildCount()).append('\n');
        for(int i=0;i<node.getChildCount();i++){
            if(remaining[0]<=0||result.length()>48000){result.append("  [remaining nodes truncated]\n");break;}
            AccessibilityNodeInfo child=node.getChild(i);if(child==null){result.append("  child=").append(i).append(" null\n");continue;}
            try{dumpAccessibility(child,result,depth+1,remaining);}finally{child.recycle();}
        }
    }
    private String diagnosticText(CharSequence value){if(value==null)return "null";String text=value.toString().replace('\n',' ');return text.length()>240?text.substring(0,240)+"…":text;}
    private void dump(View view,StringBuilder result){if(view.getContentDescription()!=null||view instanceof TextView){result.append(view.getClass().getSimpleName()).append(" description=").append(view.getContentDescription()).append(" shown=").append(view.isShown()).append(" focused=").append(view.isFocused());if(view instanceof TextView)result.append(" text=").append(((TextView)view).getText());result.append('\n');}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)dump(group.getChildAt(i),result);}}
    private void addCleanupFailure(Bundle report,String action,Throwable failure){report.putString("failure",report.getString("failure","")+"\n"+action+": "+android.util.Log.getStackTraceString(failure));}
    @SuppressWarnings("unchecked") private void restore(SharedPreferences target,Map<String,?> values)throws Exception{SharedPreferences.Editor edit=target.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));else throw new IOException("Unsupported stored preference type");}if(!edit.commit())throw new IOException("Preference cleanup failed");}
}
