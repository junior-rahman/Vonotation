package app.voiceflow;

import android.Manifest;
import android.animation.ValueAnimator;
import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/** Non-focusable vertical dictation controls; dictation keeps the other app's input active. */
public final class FlowAccessibility extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener, Dictation.Listener {
    // Connected-service bridge only; onDestroy clears the reference and removes the bubble.
    @android.annotation.SuppressLint("StaticFieldLeak")
    static FlowAccessibility instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WindowManager manager;
    private QuickThoughtOverlay thoughtOverlay;
    private boolean thoughtScreenReceiverRegistered;
    private final android.content.BroadcastReceiver thoughtScreenReceiver=new android.content.BroadcastReceiver(){public void onReceive(android.content.Context c,Intent intent){updateBubble();}};
    private LinearLayout bubble;
    private WindowManager.LayoutParams layout;
    private TextView timer;
    private Glyph glyph;
    private Dictation dictation;
    private AccessibilityEditor editor;
    private Target target;
    private boolean busy,finishing,insertionLost;
    private long startedAt;
    private String lastPartial="";
    private String pendingRaw="",pendingText="",pendingWarning="";
    private boolean pendingWriteAccepted;
    private int displayedState=-1;
    private boolean dragging;
    private String focusedKey="";
    private String automaticTypingSuppressedKey="";
    private boolean typingRequested,keyboardSuppressed,keyboardOverride;
    private int previousKeyboardMode=SHOW_MODE_AUTO;
    private long keyboardTransitionUntil;
    private long keyboardIndicatorPendingUntil;
    private String homePackage="";
    private long homeCheckedAt;
    /** Incremented when a dictation starts or ends so late callbacks cannot insert into a newer session. */
    private int session;
    private String stageLabel="";
    /** Window of the launcher's open Finder. -1 while the home screen has no visible search field. */
    private int homeFinderWindow=-1;
    private int homeMisses;
    /** Visible dictation problem shown beside the mic (toasts are suppressed on Android 13+). */
    private DictationProblem problem;
    /** VonoTation's own activity is in front: skip node queries into our own (same-process) window. */
    private boolean ownAppActive;
    private final Runnable clearProblem=()->{problem=null;updateBubble();};

    private static final class Target {
        String packageName;
        int window;
        AccessibilityNodeInfo identity;
        LiveInsertion text;
        EditorInsertion nativeText;
        EditorInsertion.Port port;
        AccessibilityEditor.SensitiveTarget sensitiveTarget;
        boolean sensitive,numeric;
    }
    @Override protected void onServiceConnected() {Store.migrate(this);ClipList.watch(this);
        ClipList.onChanged=this::clipboardUiChanged;
        instance=this; manager=(WindowManager)getSystemService(WINDOW_SERVICE);typingRequested=Store.bool(this,"typing_visible",false);
        Store.prefs(this).registerOnSharedPreferenceChangeListener(this); updateBubble();
        android.content.IntentFilter screenFilter=new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);screenFilter.addAction(Intent.ACTION_USER_PRESENT);screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(thoughtScreenReceiver,screenFilter,android.content.Context.RECEIVER_NOT_EXPORTED);else registerReceiver(thoughtScreenReceiver,screenFilter);thoughtScreenReceiverRegistered=true;
        BubbleHealth.markConnected(this);
        android.util.Log.i("VonoTation","Bubble service connected");
        // After a restart the focused field may already exist with no new focus event; re-check so the bubble reappears.
        handler.postDelayed(refresh,1000); handler.postDelayed(refresh,4000);
    }
    // The framework invokes this editor callback only on Android 13 and later.
    @android.annotation.TargetApi(33)
    @Override public android.accessibilityservice.InputMethod onCreateInputMethod(){editor=new AccessibilityEditor(this);return editor;}
    void editorChanged(){handler.removeCallbacks(refresh);handler.postDelayed(refresh,70);}
    boolean isRecording(){return busy;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Observe focus/field metadata only. Read field text only during user-started dictation.
        int type=event.getEventType();
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)clipboardWindowChanged(event);
        if(fan!=null&&fan.isOpen()){
            if(type==AccessibilityEvent.TYPE_VIEW_FOCUSED||type==AccessibilityEvent.TYPE_VIEW_CLICKED||type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)handler.post(this::refreshClipboardField);
            boolean ours=getPackageName().contentEquals(event.getPackageName()==null?"":event.getPackageName());
            if(!ours&&(type==AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED||type==AccessibilityEvent.TYPE_VIEW_CLICKED||type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)&&event.getSource()!=null&&event.getSource().isEditable())fan.fieldTapped();
            else if(!ours&&type==AccessibilityEvent.TYPE_VIEW_FOCUSED&&(event.getSource()==null||!event.getSource().isEditable()))fan.outsideAction();
            else if(!ours&&type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&event.getPackageName()!=null&&!event.getPackageName().toString().equals(fanPackage)&&!isImePackage(event.getPackageName().toString()))fan.outsideAction();
        }
        if(type==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            homeCheckedAt=0;
            CharSequence pkg=event.getPackageName();
            // Our own overlay and the keyboard both change windows. Clearing Finder on those
            // events made the bubble add and remove itself, and Niagara opened and closed with it.
            if(pkg!=null && !isHomePackage(pkg) && !getPackageName().contentEquals(pkg) && !isImePackage(pkg.toString())){
                if(homeFinderWindow!=-1){homeFinderWindow=-1;homeMisses=0;}
            }
            handler.removeCallbacks(detectHomeInput);handler.postDelayed(detectHomeInput,300);
            ownAppActive=OwnWindow.isOwnScreen(getPackageName(),event.getPackageName(),event.getClassName());
        }
        // This service shares a process with VonoTation's own screens. Querying our own window from here waits on
        // the main thread it is running on (5 s per query, which caused input ANRs while typing in Settings), and
        // the bubble never serves VonoTation's own fields unless the VonoTation keyboard is active.
        if(ownAppActive && !keyboardMode() && !busy){handler.removeCallbacks(refresh);handler.post(()->{removeBubble();hideThought();});return;}
        if(type==AccessibilityEvent.TYPE_VIEW_FOCUSED && isHomePackage(event.getPackageName())){handler.removeCallbacks(detectHomeInput);handler.postDelayed(detectHomeInput,200);}
        if(type==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)return;
        boolean inputInteraction=false;
        if(type==AccessibilityEvent.TYPE_VIEW_CLICKED || type==AccessibilityEvent.TYPE_VIEW_FOCUSED || type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED || type==AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED){
            AccessibilityNodeInfo source=event.getSource();
            if(source!=null){inputInteraction=source.isEditable() && source.isFocused();if(inputInteraction && type!=AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && isHomePackage(source.getPackageName()))homeFinderWindow=source.getWindowId();if(type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && inputInteraction){if(busy && target!=null && target.sensitiveTarget!=null && target.sensitiveTarget.current())target.sensitiveTarget.invalidate();if(FlowKeyboard.instance!=null)FlowKeyboard.instance.sensitiveInputChanged();}if(inputInteraction && isHomePackage(source.getPackageName()))inputInteraction=homeInputOpen(source);source.recycle();}
        }
        if(type==AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && (!inputInteraction || recording()))return;
        handler.removeCallbacks(refresh); handler.postDelayed(refresh,70);
    }
    private final Runnable refresh=()->updateBubble();
    private boolean isHomePackage(CharSequence packageName){
        if(packageName==null)return false;
        long now=android.os.SystemClock.elapsedRealtime();
        if(homeCheckedAt==0 || now-homeCheckedAt>5000){
            android.content.pm.ResolveInfo resolved=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
            homePackage=resolved!=null && resolved.activityInfo!=null?resolved.activityInfo.packageName:"";homeCheckedAt=now;
        }
        return homePackage.contentEquals(packageName);
    }
    /** Finds a visible launcher search bar. Does not treat a hidden or off-screen field as open. */
    private final Runnable detectHomeInput=this::scanLauncherSearch;
    private void scanLauncherSearch(){
        AccessibilityNodeInfo focus=findLauncherSearch();
        if(focus==null){
            if(homeFinderWindow!=-1){
                homeMisses++;
                if(homeMisses>=2){homeFinderWindow=-1;homeMisses=0;updateBubble();}
                else handler.postDelayed(detectHomeInput,400);
            }
            return;
        }
        homeMisses=0;
        try{int window=focus.getWindowId();if(homeFinderWindow!=window){homeFinderWindow=window;updateBubble();}}
        finally{focus.recycle();}
        handler.postDelayed(detectHomeInput,800);
    }
    private AccessibilityNodeInfo findLauncherSearch(){
        AccessibilityNodeInfo focus=null;
        List<AccessibilityWindowInfo> windows=getWindows();
        for(AccessibilityWindowInfo w:windows){
            if(w.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
            AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
            if(isHomePackage(root.getPackageName())){
                AccessibilityNodeInfo found=root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if(found!=null && !openSearchBar(found)){if(found!=root)found.recycle();found=null;}
                if(found==null)found=findOpenSearch(root,0);
                if(found!=null)focus=found==root?AccessibilityNodeInfo.obtain(root):found;
            }
            root.recycle();if(focus!=null)break;
        }
        return focus;
    }
    private static boolean searchField(AccessibilityNodeInfo n){return n!=null && looksLikeInput(n) && n.isVisibleToUser() && n.isEnabled();}
    /** Niagara's open search is a wide bar at the top. A leftover field in the home tree is not. */
    private boolean openSearchBar(AccessibilityNodeInfo n){
        if(!searchField(n))return false;
        Rect b=new Rect();n.getBoundsInScreen(b);
        int minW=Ui.dp(this,160),minH=Ui.dp(this,28),maxH=Ui.dp(this,140);
        if(b.width()<minW || b.height()<minH || b.height()>maxH)return false;
        DisplayMetrics screen=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(screen);
        if(b.top<0 || b.left<-2 || b.right>screen.widthPixels+2)return false;
        return b.bottom<screen.heightPixels*0.55f;
    }
    private static boolean looksLikeInput(AccessibilityNodeInfo n){if(n.isEditable())return true;CharSequence c=n.getClassName();String k=c==null?"":c.toString();return k.endsWith("EditText")||k.endsWith("AutoCompleteTextView")||k.endsWith("SearchView$SearchAutoComplete");}
    private AccessibilityNodeInfo findOpenSearch(AccessibilityNodeInfo n,int depth){
        if(depth>25)return null;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c==null)continue;if(openSearchBar(c))return c;AccessibilityNodeInfo f=findOpenSearch(c,depth+1);c.recycle();if(f!=null)return f;}
        return null;
    }
    private boolean homeScreenActive(){
        for(AccessibilityWindowInfo window:getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused() || window.isActive())){
            AccessibilityNodeInfo root=window.getRoot();if(root!=null){boolean home=isHomePackage(root.getPackageName());root.recycle();if(home)return true;}
        }
        return false;
    }
    /** Launchers can retain input focus on Home. Require the search bar itself to be on screen. */
    private boolean homeInputOpen(AccessibilityNodeInfo field){
        return openSearchBar(field);
    }
    /** True when the launcher is the front app, even if the keyboard window is focused. */
    private boolean launcherInFront(){
        boolean launcher=false,other=false;
        for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            boolean home=isHomePackage(root.getPackageName());
            boolean front=window.isFocused()||window.isActive();
            if(home&&front)launcher=true;
            if(!home&&front)other=true;
            root.recycle();
        }
        return launcher&&!other;
    }
    @Override public void onInterrupt() { cancelRecording(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key) {
        if(key.equals("typing_visible")){boolean requested=prefs.getBoolean(key,false);if(typingRequested!=requested){typingRequested=requested;automaticTypingSuppressedKey="";keyboardIndicatorPendingUntil=0;displayedState=-1;updateBubble();}return;}
        if(key.equals("rail_anchor_x") || key.equals("rail_anchor_y")){positionBubble();return;}
        if(key.equals("haptic_feedback")){displayedState=-1;updateBubble();return;}
        if(key.equals("quick_thoughts")){updateBubble();return;}
        if(key.equals("bubble") || key.equals("bubble_size")) { if(key.equals("bubble_size"))displayedState=-1;updateBubble(); }
    }
    /** IME windows can be active; search the focused application window first. */
    private AccessibilityNodeInfo focusedField(){return focusedField(false);}
    private AccessibilityNodeInfo focusedField(boolean typingOnly) {
        List<AccessibilityWindowInfo> windows=getWindows();
        for(AccessibilityWindowInfo window:windows) {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION || (!window.isFocused() && !window.isActive())) continue;
            AccessibilityNodeInfo root=window.getRoot();
            AccessibilityNodeInfo focus=root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if(root!=null) root.recycle();
            if(typingOnly?typingField(focus):eligible(focus)) return focus;
            if(focus!=null) focus.recycle();
        }
        AccessibilityNodeInfo root=getRootInActiveWindow();
        AccessibilityNodeInfo focus=root==null?null:root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if(root!=null) root.recycle();
        if(typingOnly?typingField(focus):eligible(focus)) return focus;
        if(focus!=null) focus.recycle(); return null;
    }
    private boolean eligible(AccessibilityNodeInfo node) {
        return node!=null && node.isEditable() && node.isFocused() && node.isEnabled() && node.isVisibleToUser() && (!isHomePackage(node.getPackageName()) || homeInputOpen(node)) && (keyboardMode() || !getPackageName().contentEquals(node.getPackageName()==null?"":node.getPackageName()));
    }
    private boolean typingField(AccessibilityNodeInfo node){return node!=null && node.isEditable() && node.isFocused() && node.isEnabled() && node.isVisibleToUser() && (node.isPassword() || FlowKeyboard.requiresTyping(node.getInputType())) && (!isHomePackage(node.getPackageName()) || homeInputOpen(node));}
    boolean focusedInputIsSensitive(){AccessibilityNodeInfo field=focusedField();if(field==null)return false;try{return field.isPassword() || FlowKeyboard.isPasswordType(field.getInputType());}finally{field.recycle();}}
    private boolean recording(){return busy || (FlowKeyboard.instance!=null && FlowKeyboard.instance.isRecording());}
    private boolean working(){return busy?finishing:FlowKeyboard.instance!=null && FlowKeyboard.instance.isFinishing();}
    private boolean keyboardMode(){return FlowKeyboard.instance!=null && FlowKeyboard.instance.hasActiveEditor() && !busy;}
    void keyboardLevel(float level){if(glyph!=null)glyph.setLevel(level);}
    void updateBubble() {
        if(manager==null)return;
        android.app.KeyguardManager lock=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        boolean locked=lock!=null && lock.isKeyguardLocked(),interactive=power==null || power.isInteractive();
        if(locked || !interactive){removeBubble();hideThought();return;}
        // The explicit clipboard read briefly owns focus. Preserve the original fan only for this bounded handoff.
        if(clipboardPeekGrace())return;
        if(clipOpening)hideClipTray();
        if(ownAppActive && !keyboardMode() && !busy){removeBubble();hideThought();return;}
        boolean keyboardMode=keyboardMode();
        AccessibilityNodeInfo field=focusedField();boolean hasField=field!=null;
        String currentKey=field==null?"":field.getWindowId()+":"+field.hashCode();
        if(!currentKey.equals(focusedKey) && (hasField || android.os.SystemClock.elapsedRealtime()>keyboardTransitionUntil)){focusedKey=currentKey;automaticTypingSuppressedKey="";}
        boolean launcherField=field!=null && isHomePackage(field.getPackageName());
        boolean launcher=launcherField || homeFinderWindow>=0 || launcherInFront();
        // Sign-in fields temporarily use ordinary typing; keep the manual choice.
        AccessibilityNodeInfo authField=focusedField(true);boolean temporaryTyping=authField!=null;
        if(!temporaryTyping && FlowKeyboard.instance!=null && FlowKeyboard.instance.hasInputSession() && FlowKeyboard.requiresTyping(FlowKeyboard.instance.getCurrentInputEditorInfo()))temporaryTyping=true;
        if(temporaryTyping && focusedKey.equals(automaticTypingSuppressedKey))temporaryTyping=false;
        // Niagara closes its search when the keyboard is hidden and opens it when a field is clicked.
        // Leave that keyboard alone unless the user uses Niagara's own key.
        if(!launcher){
            if(temporaryTyping){if(!typingKeyboardVisible())showTypingKeyboard(authField);}
            else updateTypingKeyboard(hasField && (Store.bubble(this) || keyboardMode) && !CaptureActivity.visible);
        }
        if(authField!=null)authField.recycle();
        boolean hasSessionField=hasField && target!=null && (target.port!=null?target.port.current():sameField(field));if(field!=null)field.recycle();
        if(busy && !finishing && !hasSessionField && dictation!=null)dictation.stop();
        if(!hasField && FlowKeyboard.instance!=null && FlowKeyboard.instance.isRecording() && !FlowKeyboard.instance.isFinishing())FlowKeyboard.instance.floatingMic();
        boolean active=recording();
        if(QuickThoughtPolicy.visible(Store.quickThoughts(this),interactive,locked,ownAppActive,CaptureActivity.visible,hasField,active || Dictation.isWorking() || VoiceTake.running() || Translate.going() || LineWire.running())){
            removeBubble();if(thoughtOverlay==null)thoughtOverlay=new QuickThoughtOverlay(this,manager,this::updateBubble,this::thoughtCaptureAllowed);thoughtOverlay.show();return;
        }
        hideThought();
        boolean show=(!homeScreenActive() || hasField) && (Store.bubble(this) || keyboardMode) && (!CaptureActivity.visible || keyboardMode) && (hasField || active);
        if(!show){removeBubble();return;}
        boolean typingVisible=keyboardIndicatorVisible();
        int state=(keyboardMode?20:0)+(typingRequested?40:0)+(typingVisible?160:0)+(recording()?(working()?2:1):0)+(problem!=null && !recording()?320:0);
        if(bubble==null || state!=displayedState)buildControls(state,keyboardMode,typingVisible);
        if(timer!=null && !recording())timer.setText(keyboardMode?FlowKeyboard.instance.floatingStatus():"Ready");
        else if(timer!=null && working())timer.setText(currentStage());
        positionBubble();
        if(recording()){handler.removeCallbacks(tick);handler.post(tick);}
    }
    /** Recheck at the tap, since focus and lock state can change after drawing the dot. */
    private boolean thoughtCaptureAllowed(){
        android.app.KeyguardManager lock=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        if(!Store.quickThoughts(this) || (lock!=null && lock.isKeyguardLocked()) || (power!=null && !power.isInteractive()) || ownAppActive || CaptureActivity.visible || Dictation.isWorking() || recording() || VoiceTake.running() || Translate.going() || LineWire.running())return false;
        AccessibilityNodeInfo field=focusedField();boolean available=field==null;if(field!=null)field.recycle();return available;
    }
    private void hideThought(){if(thoughtOverlay!=null)thoughtOverlay.hide();}
    private void removeBubble() {
        if(fan!=null){fan.dismissNow();fan=null;}
        if(bubble!=null){settlePresentation(bubble);try{manager.removeView(bubble);}catch(Exception ignored){}}
        bubble=null;displayedState=-1;glyph=null;timer=null;dragging=false;
    }
    private void updateTypingKeyboard(boolean hasField){
        SoftKeyboardController controller=getSoftKeyboardController();
        String current=android.provider.Settings.Secure.getString(getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        boolean voiceKeyboard=current!=null && current.startsWith(getPackageName()+"/");
        if(hasField && current!=null && !voiceKeyboard && !current.equals(Store.get(this,"typing_keyboard","")))Store.prefs(this).edit().putString("typing_keyboard",current).apply();
        if(hasField && typingRequested && (voiceKeyboard || keyboardSuppressed) && android.os.SystemClock.elapsedRealtime()>keyboardTransitionUntil){showTypingKeyboard();return;}
        if(hasField && !typingRequested && !voiceKeyboard && Build.VERSION.SDK_INT>=30){
            String voiceId=new android.content.ComponentName(this,FlowKeyboard.class).flattenToShortString();
            android.view.inputmethod.InputMethodManager input=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            for(android.view.inputmethod.InputMethodInfo ime:input.getEnabledInputMethodList())if(ime.getId().equals(voiceId)){
                releaseTypingKeyboard();keyboardTransitionUntil=android.os.SystemClock.elapsedRealtime()+3000;
                if(controller.switchToInputMethod(voiceId))return;
            }
        }
        // The VonoTation IME has no input view; only other keyboards need suppression.
        if(hasField && !typingRequested && !voiceKeyboard){
            if(!keyboardSuppressed){if(!keyboardOverride)previousKeyboardMode=controller.getShowMode();keyboardSuppressed=controller.setShowMode(SHOW_MODE_HIDDEN);keyboardOverride=keyboardSuppressed;}
        }else if((!hasField && android.os.SystemClock.elapsedRealtime()>keyboardTransitionUntil) || (!typingRequested && voiceKeyboard))releaseTypingKeyboard();
    }
    private void releaseTypingKeyboard(){
        if(keyboardOverride){getSoftKeyboardController().setShowMode(previousKeyboardMode);keyboardSuppressed=false;keyboardOverride=false;}
    }
    private void toggleTypingKeyboard(){
        // Android can dismiss a keyboard without changing the saved preference.
        // The action follows what is currently shown, so a hidden keyboard opens.
        typingRequested=!keyboardIndicatorVisible();
        AccessibilityNodeInfo authField=focusedField(true);
        if(authField!=null){automaticTypingSuppressedKey=typingRequested?"":focusedKey;authField.recycle();}
        keyboardIndicatorPendingUntil=android.os.SystemClock.elapsedRealtime()+1200;
        handler.removeCallbacks(settleKeyboardIndicator);handler.postDelayed(settleKeyboardIndicator,1200);
        Store.prefs(this).edit().putBoolean("typing_visible",typingRequested).apply();
        if(typingRequested)showTypingKeyboard();
        else releaseTypingKeyboard();
        updateBubble();
    }
    private final Runnable settleKeyboardIndicator=()->updateBubble();
    private boolean typingKeyboardVisible(){
        for(AccessibilityWindowInfo window:getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);
            // The VonoTation IME reserves no input view; ignore its empty window.
            if(bounds.height()>Ui.dp(this,40) && bounds.width()>0)return true;
        }
        return false;
    }
    private boolean keyboardIndicatorVisible(){
        boolean visible=typingKeyboardVisible();
        if(visible==typingRequested)keyboardIndicatorPendingUntil=0;
        return android.os.SystemClock.elapsedRealtime()<keyboardIndicatorPendingUntil?typingRequested:visible;
    }
    private void showTypingKeyboard(){showTypingKeyboard(null);}
    private void showTypingKeyboard(AccessibilityNodeInfo typingField){
        keyboardTransitionUntil=android.os.SystemClock.elapsedRealtime()+3000;
        // Password access is temporary; only the explicit toggle changes the saved choice.
        if(!keyboardOverride)previousKeyboardMode=getSoftKeyboardController().getShowMode();
        keyboardOverride=getSoftKeyboardController().setShowMode(Build.VERSION.SDK_INT>=29?SHOW_MODE_IGNORE_HARD_KEYBOARD:SHOW_MODE_AUTO);keyboardSuppressed=false;
        if(FlowKeyboard.instance!=null && FlowKeyboard.instance.hasInputSession())FlowKeyboard.instance.showTypingKeyboard();
        else if(busy && dictation!=null)dictation.stop();
        else if(!typingKeyboardVisible()){
            // Clicking Niagara's search field toggles the page shut. Never click a launcher field.
            if(launcherInFront() || homeFinderWindow>=0)return;
            AccessibilityNodeInfo field=typingField==null?focusedField():typingField;
            if(field!=null && isHomePackage(field.getPackageName())){if(typingField==null)field.recycle();return;}
            if(field!=null)try{field.performAction(AccessibilityNodeInfo.ACTION_CLICK);}finally{if(typingField==null)field.recycle();}
        }
    }
    private View iconControl(String kind,String description){
        return iconControl(kind,description,false);
    }
    private View iconControl(String kind,String description,boolean active){
        int size=Math.max(Ui.dp(this,28),bdp(active?30:34));
        FloatingIcon.Control view=new FloatingIcon.Control(this,kind,Color.WHITE,size);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);view.setContentDescription(description);
        int rippleColor=0x30ffffff;
        float corner=size*0f+diameter()/4f; // 48dp square, 12dp corners (scaled with bubble size)
        android.graphics.drawable.Drawable face=active?Ui.glass(this,corner/getResources().getDisplayMetrics().density,Glass.DAYBOOK_ACTION):frostedGlass(corner);
        android.graphics.drawable.GradientDrawable mask=new android.graphics.drawable.GradientDrawable();mask.setColor(Color.WHITE);mask.setCornerRadius(corner);
        android.graphics.drawable.RippleDrawable ripple=new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor),face,mask);
        view.setBackground(ripple);view.setElevation(Ui.dp(this,4));if(Build.VERSION.SDK_INT>=28){view.setOutlineAmbientShadowColor(0xFF000000);view.setOutlineSpotShadowColor(0xFF000000);}view.setHapticFeedbackEnabled(Store.bool(this,"haptic_feedback",true));return view;
    }
    /** Hidden switch for treatment B (soft coral glow while dictating). Treatment A (plain coral) ships. */
    static final boolean GLOW_WHILE_DICTATING=false;
    /** Round6: regular solid button (opaque surface, thin line border, subtle shadow via elevation). */
    private android.graphics.drawable.Drawable frostedGlass(float corner){return solidFace(this,corner);}
    /** Round7: solid coral face (#B3422F -> #A33A28 top-to-bottom), no light surface; white glyph on top. */
    static android.graphics.drawable.GradientDrawable solidFace(android.content.Context c,float corner){return coralFace(c,corner,0xFFB3422F,0xFFA33A28);}
    static android.graphics.drawable.GradientDrawable coralFace(android.content.Context c,float corner,int top,int bottom){
        android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,new int[]{top,bottom});
        g.setCornerRadius(corner);return g;
    }
    private float bubbleScale(){return BubbleSize.scale(Store.get(this,"bubble_size","small"));}
    private int bdp(float size){return Ui.dp(this,size*bubbleScale());}
    private int diameter(){return Math.max(Ui.dp(this,48),bdp(56));}
    private void buildControls(int state,boolean keyboardMode,boolean typingVisible){
        boolean attached=bubble!=null && bubble.isAttachedToWindow();
        // Keyboard indicator and preference refreshes must not replay a record transition.
        boolean reveal=!attached || (displayedState>=0 && (displayedState&3)!=(state&3));
        if(bubble==null)bubble=Ui.row(this);else{settlePresentation(bubble);bubble.removeAllViews();}
        glyph=null;timer=null;dragging=false;displayedState=state;
        boolean active=recording();int d=diameter(),inset=Ui.dp(this,4),gap=Ui.dp(this,8),problemWidth=Ui.dp(this,176),statusWidth=active?Ui.dp(this,80)+gap:(problem!=null?problemWidth+gap:0);
        bubble.setGravity(Gravity.TOP);bubble.setClipChildren(false);bubble.setClipToPadding(false);bubble.setPadding(inset,inset,inset,inset);bubble.setBackground(null);bubble.setElevation(0);
        bubble.setContentDescription(keyboardMode?"VonoTation floating keyboard":active?(working()?"VonoTation: transcribing":"VonoTation: recording in the active input"):"VonoTation floating controls");
        if(active){
            LinearLayout status=Ui.column(this);status.setGravity(Gravity.CENTER);status.setBackground(Ui.glass(this,18,Glass.DAYBOOK));status.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0);status.setElevation(Ui.dp(this,2));
            glyph=new Glyph();glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);glyph.setContentDescription(keyboardMode?"Keyboard recording waveform":"Recording waveform");status.addView(glyph,new LinearLayout.LayoutParams(-1,Math.max(Ui.dp(this,24),bdp(30))));
            timer=Ui.text(this,working()?currentStage():"00:00",11,Daybook.ink(this),false);timer.setSingleLine(true);timer.setGravity(Gravity.CENTER);LinearLayout.LayoutParams timeParams=new LinearLayout.LayoutParams(-1,-2);timeParams.topMargin=Ui.dp(this,2);status.addView(timer,timeParams);
            LinearLayout.LayoutParams statusParams=new LinearLayout.LayoutParams(Ui.dp(this,80),d);statusParams.rightMargin=gap;bubble.addView(status,statusParams);attachDrag(status);
        }
        LinearLayout rail=Ui.column(this);rail.setClipChildren(false);rail.setClipToPadding(false);bubble.addView(rail,new LinearLayout.LayoutParams(d,-2));
        if(active){
            boolean processingNow=working();
            View finish=iconControl(processingNow?"cancel":"finish",processingNow?"Cancel processing. Nothing will be inserted.":keyboardMode?"Finish keyboard dictation":"Finish dictation",true);finish.setTooltipText(processingNow?"Cancel":"Finish dictation");
            finish.setOnClickListener(v->{Daybook.feedback(v);if(working()){if(busy)cancelProcessing();else if(FlowKeyboard.instance!=null)FlowKeyboard.instance.cancelDictation();}else if(keyboardMode())FlowKeyboard.instance.floatingMic();else if(dictation!=null)dictation.stop();updateBubble();});rail.addView(finish,new LinearLayout.LayoutParams(d,d));
        }else{
            if(problem!=null){
                final DictationProblem shown=problem;
                LinearLayout pill=Ui.row(this);pill.setGravity(Gravity.CENTER_VERTICAL);pill.setBackground(Ui.glass(this,18,Glass.DAYBOOK));pill.setPadding(Ui.dp(this,10),0,Ui.dp(this,6),0);pill.setElevation(Ui.dp(this,2));
                TextView message=Ui.text(this,shown.title,11,Daybook.coral(this),true);message.setMaxLines(2);message.setEllipsize(android.text.TextUtils.TruncateAt.END);pill.addView(message,new LinearLayout.LayoutParams(0,-2,1));
                pill.setContentDescription("VonoTation: "+shown.detail);pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                if(shown.fix!=null){TextView fixAction=Ui.text(this,"Fix",12,Daybook.accent(this),true);fixAction.setGravity(Gravity.CENTER);fixAction.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),0);fixAction.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,12));fixAction.setContentDescription("Fix: "+shown.title);fixAction.setOnClickListener(v->{Daybook.feedback(v);openFix(shown.fix);});pill.addView(fixAction,new LinearLayout.LayoutParams(-2,-1));}
                pill.setOnClickListener(v->{handler.removeCallbacks(clearProblem);problem=null;updateBubble();});
                LinearLayout.LayoutParams pillParams=new LinearLayout.LayoutParams(problemWidth,d);pillParams.rightMargin=gap;bubble.addView(pill,0,pillParams);
            }
            final String keyboardState=typingVisible?"Keyboard visible":"Keyboard hidden";
            View mic=iconControl(typingVisible?"micKeyboardVisible":"micKeyboardHidden",keyboardMode?"Floating keyboard microphone":"VonoTation: start dictation");mic.setTooltipText(keyboardState+". Tap to record. Hold to toggle keyboard.");
            if(Build.VERSION.SDK_INT>=30)mic.setStateDescription(keyboardState);
            mic.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);if(Build.VERSION.SDK_INT<30)info.setContentDescription((keyboardMode?"Start dictation. ":"VonoTation: start dictation. ")+keyboardState);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Hold to toggle keyboard"));}});
            mic.setOnClickListener(v->{Daybook.feedback(v);if(keyboardMode())FlowKeyboard.instance.floatingMic();else begin();updateBubble();});
            // performLongClick emits the framework LONG_PRESS once; never add tap feedback here.
            mic.setHapticFeedbackEnabled(Store.bool(this,"haptic_feedback",true));
            mic.setOnLongClickListener(v->{if(keyboardIndicatorVisible())handler.post(this::toggleTypingKeyboard);else openFan(v);return true;});attachDrag(mic);rail.addView(mic,new LinearLayout.LayoutParams(d,d));
        }
        layout=overlayLayout(d+2*inset+statusWidth,d+2*inset);positionBubble();attachDrag(bubble);
        try{if(attached)manager.updateViewLayout(bubble,layout);else manager.addView(bubble,layout);if(reveal)revealPresentation(bubble);}catch(Exception error){removeBubble();}
    }
    private void revealPresentation(View view){
        if(view instanceof FloatingIcon.Control){((FloatingIcon.Control)view).reveal();return;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)revealPresentation(group.getChildAt(i));}
        // The waveform fades in place; the timer and all hit targets stay steady.
        if(view instanceof Glyph && ValueAnimator.areAnimatorsEnabled()){view.setAlpha(.65f);view.animate().alpha(1).setDuration(160).start();}
    }
    private void settlePresentation(View view){
        view.animate().cancel();view.setAlpha(1);view.setScaleX(1);view.setScaleY(1);view.setTranslationX(0);view.setTranslationY(0);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)settlePresentation(group.getChildAt(i));}
    }
    private WindowManager.LayoutParams overlayLayout(int width,int height){
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;if(Build.VERSION.SDK_INT>=30)params.setFitInsetsTypes(0);return params;
    }
    private int usableBottom(DisplayMetrics metrics) {
        int navigation=Ui.dp(this,36);
        if(Build.VERSION.SDK_INT>=30)try{navigation=Math.max(navigation,manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout()).bottom);}catch(RuntimeException ignored){}
        int bottom=metrics.heightPixels-navigation-Ui.dp(this,12);
        for(AccessibilityWindowInfo window:getWindows())if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect rect=new Rect();window.getBoundsInScreen(rect);if(!rect.isEmpty())bottom=Math.min(bottom,rect.top-Ui.dp(this,10));}
        return bottom;
    }
    private void positionBubble() {
        if(bubble==null || layout==null || (dragging && bubble.isAttachedToWindow()))return;
        int oldX=layout.x,oldY=layout.y;DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);
        int d=diameter(),bottom=usableBottom(metrics),top=Ui.dp(this,32),inset=Ui.dp(this,4);
        android.content.SharedPreferences prefs=Store.prefs(this);
        int anchorX=prefs.getInt("rail_anchor_x",metrics.widthPixels-Ui.dp(this,12)-inset-d/2),anchorY=prefs.getInt("rail_anchor_y",Math.round(metrics.heightPixels*.52f));
        BubblePlacement position=new BubblePlacement(metrics.widthPixels,top,bottom,layout.width,layout.height,d,anchorX+inset,anchorY-inset);layout.x=position.x;layout.y=position.y;
        // Keep the selected editor clear when there is room above its bounds.
        AccessibilityNodeInfo field=focusedField();if(field!=null){try{Rect input=new Rect();field.getBoundsInScreen(input);Rect bar=new Rect(layout.x,layout.y,layout.x+layout.width,layout.y+layout.height);int above=input.top-layout.height-Ui.dp(this,10);if(Rect.intersects(input,bar) && above>=top)layout.y=above;}finally{field.recycle();}}
        if(bubble.isAttachedToWindow() && (oldX!=layout.x || oldY!=layout.y))try{manager.updateViewLayout(bubble,layout);}catch(Exception ignored){}
    }
    private void attachDrag(View view) {
        view.setOnTouchListener(new View.OnTouchListener(){
            float x,y;int sx,sy;boolean moved,held,down,slid;
            final int slop=android.view.ViewConfiguration.get(FlowAccessibility.this).getScaledTouchSlop();
            final Runnable longPress=()->{if(down && !moved && view.isAttachedToWindow() && view.isLongClickable()){held=view.performLongClick();if(held){dragging=false;Haptics.fire(FlowAccessibility.this,Haptics.POP);}}};
            public boolean onTouch(View v,MotionEvent event){
                if(event.getAction()==MotionEvent.ACTION_DOWN){x=event.getRawX();y=event.getRawY();sx=layout.x;sy=layout.y;moved=false;held=false;slid=false;down=true;dragging=true;v.setPressed(true);if(v.isClickable()||v.isLongClickable())Haptics.fire(FlowAccessibility.this,Haptics.PRESS);if(v.isLongClickable())handler.postDelayed(longPress,HOLD_MS);return true;}
                if(!down)return true;
                if(event.getAction()==MotionEvent.ACTION_MOVE){
                    if(held){if(fan!=null&&fan.isOpen()){float hx=event.getRawX()-x,hy=event.getRawY()-y;if(hx*hx+hy*hy>slop*slop)slid=true;fan.track(event.getRawX(),event.getRawY());}return true;}
                    float dx=event.getRawX()-x,dy=event.getRawY()-y;if(dx*dx+dy*dy>slop*slop){moved=true;handler.removeCallbacks(longPress);v.setPressed(false);}
                    if(moved){DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);layout.x=Math.max(0,Math.min(metrics.widthPixels-layout.width,sx+(int)dx));layout.y=Math.max(Ui.dp(FlowAccessibility.this,32),Math.min(usableBottom(metrics)-layout.height,sy+(int)dy));manager.updateViewLayout(bubble,layout);}return true;
                }
                if(event.getAction()==MotionEvent.ACTION_UP&&held&&fan!=null&&fan.isOpen()){down=false;v.setPressed(false);dragging=false;fan.release(event.getRawX(),event.getRawY(),slid);return true;}
                if(event.getAction()==MotionEvent.ACTION_UP){down=false;handler.removeCallbacks(longPress);v.setPressed(false);dragging=false;int inset=Ui.dp(FlowAccessibility.this,4);if(moved){Store.prefs(FlowAccessibility.this).edit().putInt("rail_anchor_x",layout.x+layout.width-inset-(diameter()+1)/2).putInt("rail_anchor_y",layout.y+inset+diameter()/2).apply();positionBubble();}else if(!held)v.performClick();return true;}
                if(event.getAction()==MotionEvent.ACTION_CANCEL){down=false;handler.removeCallbacks(longPress);v.setPressed(false);dragging=false;if(fan!=null&&fan.isOpen())fan.cancelDeleteGesture();positionBubble();return true;}return false;
            }
        });
    }
    static final int HOLD_MS=300;
    /** Fan Keyboard tile: an explicit open (not a toggle that trusts the pending indicator), retried once if the IME didn't appear. */
    private void openTypingKeyboardOnce(){
        if(typingKeyboardVisible())return;
        typingRequested=true;Store.prefs(this).edit().putBoolean("typing_visible",true).apply();
        keyboardIndicatorPendingUntil=android.os.SystemClock.elapsedRealtime()+1200;handler.removeCallbacks(settleKeyboardIndicator);handler.postDelayed(settleKeyboardIndicator,1200);
        showTypingKeyboard();updateBubble();
        handler.postDelayed(()->{if(typingRequested&&!typingKeyboardVisible()){showTypingKeyboard();updateBubble();}},700);
    }
    private ArcFan fan;
    private String fanPackage="";
    private boolean isImePackage(String pkg){String ime=android.provider.Settings.Secure.getString(getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);return ime!=null&&ime.startsWith(pkg+"/")||pkg.equals("com.android.systemui");}
    private void openFan(View mic){
        {AccessibilityNodeInfo ff=focusedField();fanPackage=ff==null||ff.getPackageName()==null?"":ff.getPackageName().toString();if(ff!=null)ff.recycle();}
        if(fan!=null&&fan.isOpen())return;
        int[] loc=new int[2];mic.getLocationOnScreen(loc);float cx=loc[0]+mic.getWidth()/2f,cy=loc[1]+mic.getHeight()/2f;
        fan=new ArcFan(this,manager,new ArcFan.Actions(){
            public void beginDelete(){beginDeleteGesture();}
            public void endDelete(){endDeleteGesture();}
            public boolean deleteWord(){return deleteWordBeforeCaret();}
            public void openClipboard(){showClipTray();}
            public void prepareClipboardPaste(){preparePillPaste();}
            public void cancelClipboardPaste(){clearPillPaste();}
            public void pasteClipboard(String value){pasteAtCaret(value);}
            public void openKeyboard(){openTypingKeyboardOnce();}
            public void closed(){endDeleteGesture();hideClipTray();fan=null;}
        });
        ClipList.capture(this);fan.clipboardPreview(focusedInputIsSensitive()?null:ClipList.latest());
        fan.open(cx,cy,caretLine(),imeHeight());
    }
    private int imeHeight(){for(AccessibilityWindowInfo w:getWindows())if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect b=new Rect();w.getBoundsInScreen(b);if(b.height()>Ui.dp(this,40))return b.height();}return 0;}
    /** Screen rect of the focused line (caret guard). Falls back to the field, or its first line when tall. */
    private Rect caretLine(){
        AccessibilityNodeInfo f=focusedField();if(f==null)return null;
        try{Rect field=new Rect();f.getBoundsInScreen(field);
            int end=f.getTextSelectionEnd();CharSequence t=f.getText();
            if(Build.VERSION.SDK_INT>=26&&t!=null&&end>0&&end<=t.length()){
                android.os.Bundle args=new android.os.Bundle();args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX,end-1);args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH,1);
                if(f.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,args)){android.os.Parcelable[] r=f.getExtras().getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY);
                    if(r!=null&&r.length>0&&r[0] instanceof android.graphics.RectF){android.graphics.RectF c=(android.graphics.RectF)r[0];return new Rect(field.left,(int)c.top,field.right,(int)c.bottom);}}
            }
            if(field.height()>Ui.dp(this,72))field.bottom=field.top+Ui.dp(this,40);return field;
        }catch(RuntimeException e){return null;}finally{f.recycle();}
    }
    private CursorDeletion.Session deleteSession;
    private AccessibilityNodeInfo deleteIdentity;
    private boolean deleteReported;
    private void endDeleteGesture(){deleteSession=null;if(deleteIdentity!=null)deleteIdentity.recycle();deleteIdentity=null;}
    private boolean deleteFieldCurrent(){
        if(deleteIdentity==null)return false;AccessibilityNodeInfo current=focusedField();
        try{return current!=null && current.refresh() && current.equals(deleteIdentity) && !current.isPassword() && !FlowKeyboard.isPasswordType(current.getInputType());}
        finally{if(current!=null)current.recycle();}
    }
    private static boolean knownDeletePosition(EditorInsertion.Snapshot snapshot){return snapshot!=null && snapshot.start>=0 && snapshot.end>=0;}
    private void beginDeleteGesture(){
        endDeleteGesture();deleteReported=false;AccessibilityNodeInfo focused=focusedField();
        if(focused==null)return;
        try{
            if(!focused.refresh() || focused.isPassword() || FlowKeyboard.isPasswordType(focused.getInputType()))return;
            deleteIdentity=AccessibilityNodeInfo.obtain(focused);
            CursorDeletion.Port nativePort=Build.VERSION.SDK_INT>=33 && editor!=null?editor.captureDeletion():null;
            if(nativePort==null || !knownDeletePosition(nativePort.read()))nativePort=FlowKeyboard.instance==null?null:FlowKeyboard.instance.captureDeletion(focused.getPackageName());
            if(nativePort==null || !knownDeletePosition(nativePort.read()))nativePort=nodeDeletionPort();
            final CursorDeletion.Port chosen=nativePort;
            deleteSession=new CursorDeletion.Session(new CursorDeletion.Port(){
                public boolean current(){return deleteFieldCurrent() && chosen.current();}
                public EditorInsertion.Snapshot read(){return current()?chosen.read():null;}
                public boolean delete(CursorDeletion.Plan plan){return current() && chosen.delete(plan);}
            });
        }catch(RuntimeException unavailable){endDeleteGesture();}finally{focused.recycle();}
    }
    /** Fresh offsets only. No unknown-cursor/end-of-text fallback. */
    private EditorInsertion.Snapshot readDeleteNode(AccessibilityNodeInfo field){
        if(field==null || !field.refresh() || (Build.VERSION.SDK_INT>=26 && field.isShowingHintText()))return null;
        CharSequence value=field.getText();if(value==null)return null;String text=value.toString();
        int a=field.getTextSelectionStart(),b=field.getTextSelectionEnd();if(a<0 || b<0 || a>text.length() || b>text.length())return null;
        int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(text.substring(0,lo),text.substring(lo,hi),text.substring(hi),a,b);
    }
    private static boolean supportsDeleteAction(AccessibilityNodeInfo field,int wanted){for(AccessibilityNodeInfo.AccessibilityAction action:field.getActionList())if(action.getId()==wanted)return true;return false;}
    private static Bundle selectionArgs(int a,int b){Bundle args=new Bundle();args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,a);args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,b);return args;}
    /** Older/custom editors: validate both edit actions, then restore and verify the exact cursor. */
    private CursorDeletion.Port nodeDeletionPort(){
        return new CursorDeletion.Port(){
            public boolean current(){return deleteFieldCurrent();}
            public EditorInsertion.Snapshot read(){if(!current())return null;AccessibilityNodeInfo field=focusedField();try{return readDeleteNode(field);}finally{if(field!=null)field.recycle();}}
            public boolean delete(CursorDeletion.Plan plan){
                if(!current())return false;AccessibilityNodeInfo field=focusedField();
                try{
                    if(field==null || !CursorDeletion.same(plan.source,readDeleteNode(field)) || !supportsDeleteAction(field,AccessibilityNodeInfo.ACTION_SET_TEXT) || !supportsDeleteAction(field,AccessibilityNodeInfo.ACTION_SET_SELECTION))return false;
                    if(!field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selectionArgs(Math.min(plan.source.start,plan.source.end),Math.max(plan.source.start,plan.source.end))) || !CursorDeletion.same(plan.source,readDeleteNode(field)))return false;
                    String next=plan.remainingBefore+plan.source.after;Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,next);
                    if(!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))return false;
                    // ACTION_SET_TEXT commonly moves the cursor; explicitly restore this edit's boundary.
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selectionArgs(plan.start,plan.start));
                    EditorInsertion.Snapshot result=readDeleteNode(field);
                    return result!=null && result.start==plan.start && result.end==plan.start && result.before.equals(plan.remainingBefore) && result.selected.isEmpty() && result.after.equals(plan.source.after);
                }finally{if(field!=null)field.recycle();}
            }
        };
    }
    /** Deletes the exact selection, or the preceding Unicode-safe word at the user's current caret. */
    boolean deleteWordBeforeCaret(){
        if(deleteSession==null)beginDeleteGesture();
        boolean removed=false;try{removed=deleteSession!=null && deleteSession.delete();}catch(RuntimeException unavailable){}
        if(!removed && !deleteReported){deleteReported=true;Toast.makeText(this,"Tap the text cursor again, or use your keyboard to delete.",Toast.LENGTH_SHORT).show();}
        return removed;
    }
    private FrameLayout clipTray;
    private ClipPasteTarget clipTarget,pillTarget;
    private boolean pillTargetPrepared;
    private int clipRequest;
    private boolean clipOpening;
    private int clipTrayImeHeight;
    private long clipPeekUntil;
    private int clipPeekRequest;
    private boolean clipPeekReturning;
    private int clipPeekWindow=-1,clipOriginWindow=-1;
    private String clipOriginPackage="";

    private void clipboardWindowChanged(AccessibilityEvent event){
        if(!clipOpening)return;
        CharSequence pkg=event.getPackageName(),kind=event.getClassName();
        String name=pkg==null?"":pkg.toString(),className=kind==null?"":kind.toString();
        if(getPackageName().equals(name)&&className.equals(ClipPeek.class.getName())&&(ClipPeek.pending(clipPeekRequest)||clipPeekReturning)){clipPeekWindow=event.getWindowId();return;}
        if(clipOriginPackage.equals(name)||isImePackage(name))return;
        // A different app or another VonoTation screen is a real user-window change, never a peek handoff.
        hideClipTray();
    }
    private boolean clipboardPeekGrace(){
        if(!clipOpening||clipTarget==null||fan==null||!fan.isOpen()||ownAppActive||android.os.SystemClock.elapsedRealtime()>=clipPeekUntil)return false;
        boolean application=false;
        for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||(!window.isFocused()&&!window.isActive()))continue;
            application=true;int id=window.getId();
            if(id==clipOriginWindow||id==clipPeekWindow)return true;
            // Only the identified helper request may bridge its launch/return before a window-state event.
            if(clipPeekWindow==-1&&(ClipPeek.pending(clipPeekRequest)||clipPeekReturning))return true;
        }
        return !application&&(ClipPeek.pending(clipPeekRequest)||clipPeekReturning);
    }
    private void finishClipOpening(int request){
        if(request!=clipRequest||!clipOpening)return;
        if(clipTarget!=null&&clipTarget.currentField()){
            clipOpening=false;clipPeekUntil=0;clipPeekReturning=false;ClipPeek.cancel(clipPeekRequest);clipPeekRequest=0;showClipTrayNow();return;
        }
        if(clipboardPeekGrace()){handler.postDelayed(()->finishClipOpening(request),60);return;}
        hideClipTray();handler.post(refresh);
        Toast.makeText(this,"Return to the same input, then open Clipboard again.",Toast.LENGTH_SHORT).show();
    }

    private void refreshClipboardField(){
        boolean sensitive=focusedInputIsSensitive();
        if(fan!=null)fan.clipboardPreview(sensitive?null:ClipList.latest());
        if(sensitive || (clipTray!=null && (clipTrayImeHeight!=imeHeight() || clipTarget==null || !clipTarget.currentField())))hideClipTray();
    }
    private void clipboardUiChanged(){
        refreshClipboardField();
        if(clipTray!=null)showClipTrayNow();
    }
    /** A popup never follows focus into a different field or silently moves the user's cursor. */
    private final class ClipPasteTarget {
        AccessibilityNodeInfo identity;EditorInsertion.Port port;EditorInsertion.Snapshot snapshot;
        ClipboardText.Selection selection;boolean consumed;
        void release(){if(identity!=null){identity.recycle();identity=null;}}
        boolean currentField(){
            if(identity==null)return port!=null&&port.current();
            AccessibilityNodeInfo active=focusedField();try{return active!=null&&active.refresh()&&active.equals(identity);}catch(RuntimeException unavailable){return false;}finally{if(active!=null)active.recycle();}
        }
        boolean paste(String exact){
            if(consumed || !ClipboardText.usable(exact) || focusedInputIsSensitive())return false;
            AccessibilityNodeInfo active=focusedField();
            try{
                if(identity!=null && (active==null || !active.refresh() || !active.equals(identity)))return false;
                // Recheck the refreshed object immediately before mutation, including a field-type change.
                if(active!=null && (!active.isEditable()||!active.isFocused()||!active.isEnabled()||!active.isVisibleToUser()||active.isPassword()||FlowKeyboard.isPasswordType(active.getInputType())))return false;
                if(port!=null && port.current()){
                    if(!ClipboardText.same(snapshot,port.read()))return false;
                    // Exact clipboard insertion deliberately bypasses dictation punctuation/spacing cleanup.
                    consumed=true;return port.replace(snapshot.start<0?-1:Math.min(snapshot.start,snapshot.end),snapshot.end<0?-1:Math.max(snapshot.start,snapshot.end),0,exact);
                }
                if(active==null || selection==null || !active.refresh()||!active.isEditable()||!active.isFocused()||!active.isEnabled()||!active.isVisibleToUser()||active.isPassword()||FlowKeyboard.isPasswordType(active.getInputType())||(identity!=null&&!active.equals(identity)))return false;
                CharSequence value=Build.VERSION.SDK_INT>=26&&active.isShowingHintText()?"":active.getText();
                String text=value==null?"":value.toString();
                String next=selection.take(text,active.getTextSelectionStart(),active.getTextSelectionEnd(),exact);
                if(next==null)return false;
                consumed=true;Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,next);
                if(!active.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args))return false;
                int cursor=selection.cursor(exact);Bundle caret=new Bundle();caret.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,cursor);caret.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,cursor);active.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,caret);return true;
            }catch(RuntimeException unavailable){return false;}finally{if(active!=null)active.recycle();}
        }
    }
    private ClipPasteTarget captureClipTarget(){
        if(focusedInputIsSensitive())return null;
        ClipPasteTarget result=new ClipPasteTarget();AccessibilityNodeInfo active=focusedField();
        try{
            if(active!=null && active.refresh()){
                if(active.isPassword()||FlowKeyboard.isPasswordType(active.getInputType()))return null;
                result.identity=AccessibilityNodeInfo.obtain(active);
                CharSequence value=Build.VERSION.SDK_INT>=26&&active.isShowingHintText()?"":active.getText();
                result.selection=new ClipboardText.Selection(value==null?"":value.toString(),active.getTextSelectionStart(),active.getTextSelectionEnd());
            }
            if(Build.VERSION.SDK_INT>=33&&editor!=null){result.port=editor.capture();result.snapshot=result.port==null?null:result.port.read();}
            if((result.selection==null || !result.selection.valid()) && result.snapshot==null){result.release();return null;}
            return result;
        }catch(RuntimeException unavailable){result.release();return null;}finally{if(active!=null)active.recycle();}
    }
    private void showClipTray(){
        if(clipTray!=null||clipOpening){hideClipTray();return;}
        if(focusedInputIsSensitive()){Toast.makeText(this,"Clipboard preview is hidden for this field.",Toast.LENGTH_SHORT).show();return;}
        if(layout==null)return;
        clipTarget=captureClipTarget();
        if(clipTarget==null){Toast.makeText(this,"Tap the input where you want to paste.",Toast.LENGTH_SHORT).show();return;}
        clipOpening=true;clipPeekUntil=android.os.SystemClock.elapsedRealtime()+1200;clipPeekWindow=-1;clipPeekReturning=false;
        clipOriginWindow=clipTarget.identity==null?-1:clipTarget.identity.getWindowId();
        clipOriginPackage=clipTarget.identity==null?fanPackage:String.valueOf(clipTarget.identity.getPackageName());
        final int request=++clipRequest;
        ClipList.capture(this);
        Runnable show=()->finishClipOpening(request);
        if(ClipList.status()==ClipList.UNAVAILABLE)peekClipboard(show);else show.run();
    }
    /** Explicit clipboard-button action only. Android 10+ may deny reads outside a focused app/default IME. */
    private void peekClipboard(Runnable after){
        final int request=clipRequest;
        Runnable returnToInput=()->{if(request!=clipRequest)return;clipPeekReturning=true;handler.postDelayed(after,140);};
        final int peek=ClipPeek.prepare(returnToInput);clipPeekRequest=peek;
        try{
            startActivity(new Intent(this,ClipPeek.class).putExtra(ClipPeek.REQUEST,peek).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_NO_ANIMATION|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
            handler.postDelayed(()->{if(request==clipRequest&&clipOpening){ClipPeek.cancel(peek);after.run();}},900);
        }catch(RuntimeException unavailable){ClipPeek.cancel(peek);after.run();}
    }
    private void showClipTrayNow(){
        if(layout==null||clipTarget==null)return;
        int scrollY=0;if(clipTray!=null){View existing=clipTray.findViewWithTag("clipboard-scroll");if(existing!=null)scrollY=existing.getScrollY();}
        removeClipTrayView();
        String[] clips=ClipboardText.withCurrent(ClipList.latest(),ClipList.recent(this));
        LinearLayout column=Ui.column(this);column.setBackground(Ui.shape(Daybook.surface(this),16,this));int pad=Ui.dp(this,8);column.setPadding(pad,pad,pad,pad);
        boolean unavailable=ClipList.status()==ClipList.UNAVAILABLE;
        String heading=clips.length>0?(unavailable?"Saved copies":"Clipboard"):"Clipboard";
        TextView title=Ui.text(this,heading,11,Daybook.muted(this),true);title.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),Ui.dp(this,4));column.addView(title,new LinearLayout.LayoutParams(-1,Ui.dp(this,24)));
        if(unavailable||clips.length==0){
            String message=unavailable?"Latest copy unavailable on this screen.":ClipList.status()==ClipList.SENSITIVE?"Sensitive copy hidden.":ClipList.status()==ClipList.NON_TEXT?"The latest copy is not text.":"No copied text yet.";
            TextView status=Ui.text(this,message,12,Daybook.muted(this),false);status.setMaxLines(2);status.setPadding(Ui.dp(this,8),0,Ui.dp(this,8),Ui.dp(this,4));column.addView(status,new LinearLayout.LayoutParams(-1,Ui.dp(this,40)));
        }
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setTag("clipboard-scroll");scroll.setFillViewport(false);scroll.setVerticalScrollBarEnabled(true);scroll.setFocusable(false);
        LinearLayout rows=Ui.column(this);scroll.addView(rows,new android.widget.ScrollView.LayoutParams(-1,-2));
        for(String exact:clips){
            TextView row=Ui.text(this,ClipboardText.preview(exact),14,Daybook.ink(this),false);row.setMaxLines(2);row.setEllipsize(android.text.TextUtils.TruncateAt.END);row.setFocusable(false);
            row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(this,8),Ui.dp(this,6),Ui.dp(this,8),Ui.dp(this,6));row.setContentDescription("Paste copy: "+ClipboardText.preview(exact));
            row.setBackground(Daybook.ripple(this,Color.TRANSPARENT,10));
            row.setOnClickListener(v->{ClipPasteTarget saved=clipTarget;clipTarget=null;removeClipTrayView();clipRequest++;if(saved!=null){Haptics.fire(this,Haptics.RELEASE);boolean pasted=saved.paste(exact);saved.release();if(!pasted)Toast.makeText(this,"Input changed. Tap the field and try again.",Toast.LENGTH_SHORT).show();}});
            rows.addView(row,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
        }
        int top=Ui.dp(this,32),bottom;DisplayMetrics dm=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(dm);bottom=dm.heightPixels-imeHeight()-Ui.dp(this,16);
        if(Build.VERSION.SDK_INT>=30){android.view.WindowInsets insets=manager.getCurrentWindowMetrics().getWindowInsets();android.graphics.Insets safe=insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());top=Math.max(top,safe.top+pad);bottom=Math.min(bottom,dm.heightPixels-safe.bottom-pad);}
        int width=Math.min(Ui.dp(this,220),dm.widthPixels-Ui.dp(this,16));int headerHeight=Ui.dp(this,24)+(unavailable||clips.length==0?Ui.dp(this,40):0)+2*pad;
        int visibleRows=Math.min(3,clips.length);int listHeight=Math.min(Ui.dp(this,48*visibleRows),Math.max(0,bottom-top-headerHeight));
        if(clips.length>0)column.addView(scroll,new LinearLayout.LayoutParams(-1,listHeight));
        if(bottom-top<headerHeight+(clips.length>0?Ui.dp(this,48):0)){hideClipTray();Toast.makeText(this,"Close the keyboard to make room for copied text.",Toast.LENGTH_SHORT).show();return;}
        int total=headerHeight+(clips.length>0?listHeight:0);clipTrayImeHeight=imeHeight();
        clipTray=new FrameLayout(this);clipTray.addView(column,new FrameLayout.LayoutParams(width,total));
        Rect anchor=fan==null?null:fan.clipboardBounds();if(anchor==null)anchor=new Rect(layout.x,layout.y,layout.x+layout.width,layout.y+layout.height);
        int x=anchor.centerX()>dm.widthPixels/2?anchor.left-width-Ui.dp(this,8):anchor.right+Ui.dp(this,8);x=Math.max(Ui.dp(this,8),Math.min(dm.widthPixels-width-Ui.dp(this,8),x));int y=Math.max(top,Math.min(bottom-total,anchor.top));
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(width,total,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=x;lp.y=y;
        clipTray.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_OUTSIDE)hideClipTray();return false;});
        try{manager.addView(clipTray,lp);final int previousScroll=scrollY;scroll.post(()->scroll.scrollTo(0,previousScroll));}catch(RuntimeException unavailableWindow){hideClipTray();}
    }
    private void removeClipTrayView(){if(clipTray!=null){try{manager.removeView(clipTray);}catch(RuntimeException ignored){}clipTray=null;}}
    private void hideClipTray(){clipRequest++;clipOpening=false;clipPeekUntil=0;clipPeekReturning=false;ClipPeek.cancel(clipPeekRequest);clipPeekRequest=0;clipPeekWindow=-1;clipOriginWindow=-1;clipOriginPackage="";removeClipTrayView();if(clipTarget!=null){clipTarget.release();clipTarget=null;}}
    private void preparePillPaste(){clearPillPaste();pillTarget=captureClipTarget();pillTargetPrepared=true;}
    private void clearPillPaste(){if(pillTarget!=null)pillTarget.release();pillTarget=null;pillTargetPrepared=false;}
    /** Capture at finger-down so a focus/cursor change before finger-up cannot redirect this paste. */
    void pasteAtCaret(String exact){
        ClipPasteTarget captured=pillTargetPrepared?pillTarget:captureClipTarget();pillTarget=null;pillTargetPrepared=false;if(captured==null){Toast.makeText(this,"Tap the input where you want to paste.",Toast.LENGTH_SHORT).show();return;}
        boolean pasted=captured.paste(exact);captured.release();
        if(!pasted)Toast.makeText(this,"Input changed. Tap the field and try again.",Toast.LENGTH_SHORT).show();
    }
    private void begin() {
        if(busy || CaptureActivity.visible) return;
        // Preflight: never start the microphone or the recording notification without both.
        DictationProblem blocked=DictationProblem.before(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,!Vault.get(this).isEmpty());
        if(blocked!=null){showProblem(blocked);return;}
        AccessibilityNodeInfo sensitiveField=focusedField();
        boolean sensitive=(sensitiveField!=null && (sensitiveField.isPassword() || FlowKeyboard.isPasswordType(sensitiveField.getInputType()))) || (Build.VERSION.SDK_INT>=33 && editor!=null && editor.sensitive());
        if(sensitive){
            AccessibilityEditor.SensitiveTarget secret=Build.VERSION.SDK_INT>=33 && editor!=null?editor.captureSensitive():null;
            if(secret==null || sensitiveField==null || !secret.matchesPackage(sensitiveField.getPackageName())){if(secret!=null)secret.release();if(sensitiveField!=null)sensitiveField.recycle();showProblem(DictationProblem.of("Tap the input again, then retry. Direct credential insertion needs Android 13 or later."));return;}
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){secret.release();sensitiveField.recycle();showProblem(DictationProblem.before(false,true));return;}
            target=new Target();target.sensitive=true;target.sensitiveTarget=secret;target.numeric=secret.numeric();target.packageName=String.valueOf(sensitiveField.getPackageName());target.window=sensitiveField.getWindowId();target.identity=AccessibilityNodeInfo.obtain(sensitiveField);sensitiveField.recycle();
            startTarget();return;
        }
        if(sensitiveField!=null)sensitiveField.recycle();
        EditorInsertion.Port port=Build.VERSION.SDK_INT>=33 && editor!=null?editor.capture():null;
        EditorInsertion.Snapshot snapshot=port==null?null:port.read();
        AccessibilityNodeInfo focus=snapshot==null?focusedField():null; if(snapshot==null && focus==null) { updateBubble(); return; }
        if(focus!=null && (focus.isPassword() || FlowKeyboard.isPasswordType(focus.getInputType()))){focus.recycle();updateBubble();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            if(focus!=null)focus.recycle(); showProblem(DictationProblem.before(false,true)); return;
        }
        target=new Target();
        if(snapshot!=null){target.port=port;target.nativeText=new EditorInsertion(snapshot);}
        else{target.packageName=String.valueOf(focus.getPackageName());target.window=focus.getWindowId();target.identity=AccessibilityNodeInfo.obtain(focus);target.text=new LiveInsertion(focus.getText()==null?"":focus.getText().toString(),focus.getTextSelectionStart(),focus.getTextSelectionEnd());focus.recycle();}
        startTarget();
    }
    private void startTarget(){
        lastPartial="";pendingRaw="";pendingText="";pendingWarning="";pendingWriteAccepted=false;insertionLost=false;finishing=false;
        // The recording notice is optional. Notifications may be denied, and startForeground can be refused
        // (Android 12+ background-start rules, Android 14 microphone-type rules, OEM limits). The recorder runs
        // in this bound accessibility service either way, and the bubble shows recording/processing status,
        // so a refused notice must never end the session.
        foregroundNotice=RecordingNotice.start(this,41,"Tap the checkmark to finish dictation.",new Intent(this,FlowAccessibility.class).setAction("app.voiceflow.STOP"));
         handler.removeCallbacks(clearProblem);problem=null;session++;stageLabel="";busy=true; startedAt=android.os.SystemClock.elapsedRealtime(); updateBubble(); dictation=new Dictation(this,this,target.sensitive); dictation.start(); handler.post(tick);
    }
    /** How the current recording runs: see RecordingNotice. */
    private String foregroundNotice=RecordingNotice.NONE;
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action=intent==null?null:intent.getAction();
        if("app.voiceflow.STOP".equals(action) && dictation!=null) dictation.stop();
        if("app.voiceflow.NOTE_ON".equals(action) && !busy) foregroundNotice=RecordingNotice.start(this,42,"Recording a note. Tap Finish when you're done.",new Intent(this,FlowAccessibility.class).setAction("app.voiceflow.NOTE_OFF"));
        if("app.voiceflow.NOTE_OFF".equals(action)){VoiceTake.finish();if(!busy)RecordingNotice.stop(this,foregroundNotice);}
        stopSelf(id); return START_NOT_STICKY;
    }
    private final Runnable tick=new Runnable() {
        public void run() {
            if(!recording()) return;
            if(!working() && timer!=null) { long seconds=busy?(android.os.SystemClock.elapsedRealtime()-startedAt)/1000:FlowKeyboard.instance.recordingSeconds(); timer.setText(String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60)); }
            handler.postDelayed(this,500);
        }
    };
    private boolean sameField(AccessibilityNodeInfo focus) {
        if(target!=null && target.sensitiveTarget!=null)return target.sensitiveTarget.current() && focus!=null && target.window==focus.getWindowId() && target.packageName.equals(String.valueOf(focus.getPackageName())) && focus.equals(target.identity);
        if(target!=null && target.port!=null)return target.port.current();
        if(target==null || focus==null || target.window!=focus.getWindowId() || !target.packageName.equals(String.valueOf(focus.getPackageName()))) return false;
        return focus.equals(target.identity);
    }
    private boolean insertLive(String words) {
        if(target==null || insertionLost) return false;
        if(target.port!=null){
            if(!target.port.current()){insertionLost=true;return false;}
            EditorInsertion.Snapshot snapshot=target.port.read();if(snapshot==null)return false;
            if(!target.nativeText.matches(snapshot)){insertionLost=true;return false;}
            return target.nativeText.write(target.port,words);
        }
        AccessibilityNodeInfo focus=focusedField();
        try {
            if(focus==null || !focus.refresh())return false;
            if(!sameField(focus) || focus.isPassword() || FlowKeyboard.isPasswordType(focus.getInputType())) { insertionLost=true; return false; }
            String current=focus.getText()==null?"":focus.getText().toString();
            if(!target.text.matches(current,focus.getTextSelectionStart(),focus.getTextSelectionEnd())) { insertionLost=true; return false; }
            String result=target.text.render(words); int cursor=target.text.cursor(words);
            if(!result.equals(current)) {
                Bundle args=new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,result);
                if(!focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) { insertionLost=true; return false; }
            }
            Bundle selection=new Bundle(); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,cursor); selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,cursor);
            boolean placed=focus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION,selection); target.text.accept(words);
            if(!placed) insertionLost=true; return true;
        } finally { if(focus!=null) focus.recycle(); }
    }
    @Override public void state(String status) {
        if(!busy) return; finishing=dictation!=null && dictation.isFinishing(); updateBubble();
        stageLabel=shortStage(status);
        if(finishing && timer!=null){timer.setText(stageLabel);timer.setContentDescription(status);}
    }
    @Override public void partial(String text) {
        if(!busy || text.trim().isEmpty()) return; lastPartial=text;if(target!=null && target.sensitive)return;insertLive(Store.clean(this,text));
    }
    @Override public void level(float level) { if(glyph!=null) glyph.setLevel(level); }
    @Override public void result(String raw,String text,String warning) {
        stageLabel="Inserting";if(timer!=null)timer.setText(stageLabel);
        finishResult(raw,text,warning,0,session);
    }
    private void finishResult(String raw,String text,String warning,int attempt,int owner){
        if(!busy || owner!=session)return;
        pendingRaw=raw;pendingText=text;pendingWarning=warning;
        if(busy && target!=null && target.sensitive){
            String value=target.numeric?FlowKeyboard.normalizePin(raw):raw;
            pendingText=value==null?raw:value;
            AccessibilityNodeInfo field=focusedField();boolean current=sameField(field);if(field!=null)field.recycle();
            boolean requested=current && value!=null && target.sensitiveTarget.commit(value);
            boolean inserted=requested && target.sensitiveTarget.acknowledged();
            if(requested && !inserted && current && attempt<6){handler.postDelayed(()->finishResult(raw,text,warning,attempt+1,owner),150);return;}
            Recovery.keep(this,raw,value==null?raw:value,android.os.SystemClock.elapsedRealtime()-startedAt,inserted,true);endRecording();
            if(!warning.isEmpty())Toast.makeText(this,warning,Toast.LENGTH_LONG).show();return;
        }
        boolean inserted=insertLive(text);pendingWriteAccepted|=inserted;
        if(inserted && target!=null && target.port!=null)inserted=target.nativeText.matches(target.port.read());
        if(!inserted && !insertionLost && attempt<6){handler.postDelayed(()->finishResult(raw,text,warning,attempt+1,owner),150);return;}
        Recovery.keep(this,raw,text,android.os.SystemClock.elapsedRealtime()-startedAt,inserted);endRecording();
        if(!warning.isEmpty())Toast.makeText(this,warning,Toast.LENGTH_LONG).show();
    }
    @Override public void error(String message) {
        if(!busy) return;if(!lastPartial.isEmpty()){boolean sensitive=target!=null && target.sensitive;Recovery.keep(this,lastPartial,sensitive?lastPartial:Store.clean(this,lastPartial),android.os.SystemClock.elapsedRealtime()-startedAt,false,sensitive);}
        endRecording(); showProblem(DictationProblem.of(message));
    }
    /** Shows a dictation problem beside the mic for a few seconds and logs it. */
    void showProblem(DictationProblem value){
        if(value==null)return;
        android.util.Log.w("VonoTation","Dictation blocked: "+value.title+" | "+value.detail);
        problem=value;handler.removeCallbacks(clearProblem);handler.postDelayed(clearProblem,10000);updateBubble();
        if(bubble==null || !bubble.isAttachedToWindow())Toast.makeText(this,value.detail,Toast.LENGTH_LONG).show();
    }
    /** Opens the screen that fixes a problem: the API key dialog or the microphone permission. */
    void openFix(String fix){
        handler.removeCallbacks(clearProblem);problem=null;updateBubble();
        try{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_FIX,fix));}catch(RuntimeException error){android.util.Log.w("VonoTation","Could not open settings",error);}
    }
    static String shortStage(String status){
        if(status==null)return "Working";
        if(status.startsWith("Saving"))return "Saving";
        if(status.startsWith("Uploading"))return "Uploading";
        if(status.startsWith("Transcribing"))return "Transcribing";
        if(status.startsWith("Polishing"))return "Polishing";
        if(status.startsWith("Writing in English"))return "Converting";
        if(status.startsWith("Finishing"))return "Finishing";
        return "Working";
    }
    private String currentStage(){
        if(!busy && FlowKeyboard.instance!=null)return shortStage(FlowKeyboard.instance.floatingStatus());
        return stageLabel.isEmpty()?"Working":stageLabel;
    }
    /** Cancel during processing: stop the request and never insert a late result. */
    private void cancelProcessing(){
        if(!busy)return;
        if(dictation!=null)dictation.cancel();
        endRecording();
        Toast.makeText(this,"Dictation cancelled. Nothing was inserted.",Toast.LENGTH_SHORT).show();
    }
    private void cancelRecording() {
        if(busy && dictation!=null)dictation.stop();
    }
    private void endRecording() {
        session++;stageLabel="";busy=false; finishing=false;lastPartial="";pendingRaw="";pendingText="";pendingWarning="";pendingWriteAccepted=false;if(target!=null && target.sensitiveTarget!=null)target.sensitiveTarget.release();if(target!=null && target.identity!=null) target.identity.recycle(); target=null; handler.removeCallbacks(tick);
        if(dictation!=null) { dictation.close(); dictation=null; }
        RecordingNotice.stop(this,foregroundNotice); foregroundNotice=RecordingNotice.NONE; updateBubble();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) { super.onConfigurationChanged(configuration);displayedState=-1;updateBubble(); }
    private boolean pendingAcknowledged(){
        if(target==null || pendingText.isEmpty())return false;
        try{
            if(target.port!=null)return pendingWriteAccepted && target.port.current() && target.nativeText.matches(target.port.read());
            AccessibilityNodeInfo field=focusedField();if(field==null)return false;
            try{if(!sameField(field))return false;if(target.sensitiveTarget!=null)return target.sensitiveTarget.acknowledged();if(field.isPassword() || FlowKeyboard.isPasswordType(field.getInputType()))return false;int cursor=target.text.cursor(pendingText);return target.text.render(pendingText).contentEquals(field.getText()==null?"":field.getText()) && field.getTextSelectionStart()==cursor && field.getTextSelectionEnd()==cursor;}finally{field.recycle();}
        }catch(RuntimeException unavailable){return false;}
    }
    @Override public void onDestroy() {
        
        releaseTypingKeyboard();Store.prefs(this).unregisterOnSharedPreferenceChangeListener(this);if(dictation!=null){if(!pendingRaw.isEmpty()){Recovery.keep(this,pendingRaw,pendingText,android.os.SystemClock.elapsedRealtime()-startedAt,pendingAcknowledged(),target!=null && target.sensitive);if(!pendingWarning.isEmpty())Toast.makeText(this,pendingWarning,Toast.LENGTH_LONG).show();dictation.close();}else if(busy)dictation.finishDetached(android.os.SystemClock.elapsedRealtime()-startedAt);else dictation.close();dictation=null;}
        pendingRaw="";pendingText="";pendingWarning="";pendingWriteAccepted=false;lastPartial="";if(target!=null && target.sensitiveTarget!=null)target.sensitiveTarget.release();
        handler.removeCallbacksAndMessages(null);ClipList.onChanged=null;ClipPeek.after=null; removeBubble();hideThought();if(thoughtScreenReceiverRegistered){unregisterReceiver(thoughtScreenReceiver);thoughtScreenReceiverRegistered=false;} RecordingNotice.stop(this,foregroundNotice); instance=null; if(target!=null && target.identity!=null) target.identity.recycle(); target=null; super.onDestroy();
    }
    private final class Glyph extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final int[] barHeights={25,12,30,12,25};
        float level;
        Glyph() { super(FlowAccessibility.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void setLevel(float value) { level=Math.max(0,Math.min(1,value)); invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            boolean moving=ValueAnimator.areAnimatorsEnabled() && recording() && !working();
            paint.setColor(Daybook.coral(FlowAccessibility.this)); float gap=bdp(8),width=bdp(4),center=getWidth()/2f;
            for(int i=0;i<5;i++) {
                float h=bdp(barHeights[i]);
                if(moving) h*=.5f+level*.5f*(float)(.6+.4*Math.abs(Math.sin(android.os.SystemClock.uptimeMillis()/140.0+i)));
                float x=center+(i-2)*gap-width/2;canvas.drawRoundRect(x,(getHeight()-h)/2,x+width,(getHeight()+h)/2,width/2,width/2,paint);
            }
            if(moving && isAttachedToWindow()) postInvalidateDelayed(70);
        }
    }
}
