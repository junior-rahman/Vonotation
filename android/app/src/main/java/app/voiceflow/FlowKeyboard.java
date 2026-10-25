package app.voiceflow;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/** The entire recorder stays in the IME. No Activity or transcript window is opened. */
public final class FlowKeyboard extends InputMethodService implements Dictation.Listener {
    /** Incremented per dictation start/end; stale insertion retries are ignored. */
    private int generation;
    static FlowKeyboard instance;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long session,targetSession,startedAt;
    private int selectionStart=-1,selectionEnd=-1;
    private long selectionRevision,targetSelectionRevision;
    private int sensitiveStart,sensitiveEnd,sensitiveCursor;
    private String targetPackage="",lastPartial="";
    private String pendingRaw="",pendingText="",pendingWarning="";
    private boolean pendingWriteAccepted;
    private int targetField;
    private Dictation dictation;
    private EditorInsertion insertion;
    private boolean busy,finishing,inputActive,inputWindowRequested;
    private boolean sensitive,numeric,sensitiveAttempted,sensitiveSent,sensitiveEdited;
    private String floatingStatus="Ready";
    private boolean setupNoticeShown;
    @Override public void onCreate(){super.onCreate();instance=this;}
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public boolean onEvaluateInputViewShown(){super.onEvaluateInputViewShown();return false;}
    @Override public View onCreateInputView(){return null;}
    boolean hasInputSession(){return inputActive && getCurrentInputConnection()!=null;}
    boolean hasActiveEditor(){EditorInfo info=getCurrentInputEditorInfo();return hasInputSession() && info!=null && info.inputType!=InputType.TYPE_NULL;}
    /** Clipboard callbacks may inspect cached IME metadata, never an accessibility node. */
    static boolean clipboardSensitiveContext(){FlowKeyboard owner=instance;if(owner==null || !owner.inputActive)return false;EditorInfo info=owner.getCurrentInputEditorInfo();return info!=null && isPasswordType(info.inputType);}
    boolean inputWindowRequestedFor(CharSequence packageName){EditorInfo info=getCurrentInputEditorInfo();return inputWindowRequested && hasActiveEditor() && info!=null && packageName!=null && packageName.toString().equals(info.packageName);}
    /** Track explicit show/hide requests even though this IME has no visible window. */
    @Override public AbstractInputMethodImpl onCreateInputMethodInterface(){
        return new InputMethodImpl(){
            @Override public void showSoftInput(int flags,android.os.ResultReceiver receiver){inputWindowRequested=true;super.showSoftInput(flags,receiver);refreshBubble();}
            @Override public void hideSoftInput(int flags,android.os.ResultReceiver receiver){inputWindowRequested=false;super.hideSoftInput(flags,receiver);refreshBubble();}
        };
    }
    boolean isRecording(){return busy;}
    boolean isFinishing(){return finishing;}
    String floatingStatus(){return floatingStatus;}
    long recordingSeconds(){return (android.os.SystemClock.elapsedRealtime()-startedAt)/1000;}
    void floatingMic(){if(busy){if(!finishing && dictation!=null)dictation.stop();}else record();}
    void showTypingKeyboard(){
        if(getCurrentInputConnection()==null)return;
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        String preferred=Store.get(this,"typing_keyboard","");
        for(android.view.inputmethod.InputMethodInfo ime:manager.getEnabledInputMethodList())if(!ime.getPackageName().equals(getPackageName()) && ime.getId().equals(preferred)){switchInputMethod(ime.getId());return;}
        if(Build.VERSION.SDK_INT>=28 && switchToPreviousInputMethod())return;
        for(android.view.inputmethod.InputMethodInfo ime:manager.getEnabledInputMethodList())if(!ime.getPackageName().equals(getPackageName())){switchInputMethod(ime.getId());return;}
        Toast.makeText(this,"Enable a typing keyboard in Android keyboard settings.",Toast.LENGTH_LONG).show();
    }
    @Override public void onStartInput(EditorInfo info,boolean restarting){
        super.onStartInput(info,restarting);
        // A recording belongs to the editor session in which it began. Keep the
        // microphone alive while Android replaces/hides input connections, but
        // never let an old dictation follow a new editor into another field.
        session++;
        selectionStart=info.initialSelStart;selectionEnd=info.initialSelEnd;inputActive=true;refreshBubble();
        if(requiresTyping(info) && FlowAccessibility.instance==null)handler.post(()->{if(hasInputSession() && requiresTyping(getCurrentInputEditorInfo()))showTypingKeyboard();});
        if(!requiresTyping(info) && FlowAccessibility.instance==null && !setupNoticeShown){setupNoticeShown=true;Toast.makeText(this,"Enable Floating microphone in Voitation Settings to show the keyboard controls.",Toast.LENGTH_LONG).show();}
        if(FlowAccessibility.instance!=null)setupNoticeShown=false;
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting){super.onStartInputView(info,restarting);refreshControls();refreshBubble();}
    @Override public void onFinishInputView(boolean finishingInput){if(finishingInput)session++;super.onFinishInputView(finishingInput);refreshBubble();}
    @Override public void onFinishInput(){inputActive=false;inputWindowRequested=false;session++;super.onFinishInput();refreshBubble();}
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);if(selectionStart!=newStart || selectionEnd!=newEnd)selectionRevision++;selectionStart=newStart;selectionEnd=newEnd;
    }
    static boolean isPassword(EditorInfo info){
        if(info==null || info.inputType==InputType.TYPE_NULL)return true;
        return isPasswordType(info.inputType);
    }
    /** Email and secret fields use the ordinary keyboard without changing the saved choice. */
    static boolean requiresTyping(EditorInfo info){return isPassword(info) || (info!=null && requiresTyping(info.inputType));}
    static boolean isEmail(int inputType){int variation=inputType&InputType.TYPE_MASK_VARIATION;return (inputType&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_TEXT && (variation==InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation==InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);}
    static boolean requiresTyping(int inputType){return isEmail(inputType) || isPasswordType(inputType);}
    static boolean isPasswordType(int inputType){
        int variation=inputType&InputType.TYPE_MASK_VARIATION,kind=inputType&InputType.TYPE_MASK_CLASS;
        return (kind==InputType.TYPE_CLASS_TEXT && (variation==InputType.TYPE_TEXT_VARIATION_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) || (kind==InputType.TYPE_CLASS_NUMBER && variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }
    /** PINs accept only explicit digits or single-digit names; never infer larger numbers. */
    static String normalizePin(String raw){
        if(raw==null)return null;String[] names={"zero","one","two","three","four","five","six","seven","eight","nine"};StringBuilder digits=new StringBuilder();
        for(String token:raw.trim().toLowerCase(java.util.Locale.ROOT).split("[\\s,.-]+")){
            if(token.isEmpty())continue;int named=-1;for(int i=0;i<names.length;i++)if(names[i].equals(token)){named=i;break;}
            if(named>=0){digits.append((char)('0'+named));continue;}
            for(int at=0;at<token.length();){int code=token.codePointAt(at),digit=Character.digit(code,10);if(digit<0)return null;digits.append((char)('0'+digit));at+=Character.charCount(code);}
        }
        return digits.length()==0?null:digits.toString();
    }
    private void refreshControls(){
        if(!busy)floatingStatus="Ready";
        refreshBubble();
    }
    private void setStatus(String value){floatingStatus=value;refreshBubble();}
    private boolean privateEditor(){return isPassword(getCurrentInputEditorInfo()) || (FlowAccessibility.instance!=null && FlowAccessibility.instance.focusedInputIsSensitive());}
    private boolean sameTarget(){EditorInfo info=getCurrentInputEditorInfo();return hasInputSession() && info!=null && session==targetSession && targetPackage.equals(info.packageName) && targetField==info.fieldId && (sensitive?isPassword(info):!privateEditor());}
    void sensitiveInputChanged(){if(busy && sensitive && !sensitiveAttempted && sameTarget())sensitiveEdited=true;}
    private final EditorInsertion.Port port=new EditorInsertion.Port(){
        public boolean current(){return sameTarget();}
        public EditorInsertion.Snapshot read(){return !sensitive && sameTarget()?InputSnapshots.read(getCurrentInputConnection(),selectionStart,selectionEnd):null;}
        public boolean replace(int start,int end,int previousLength,String text){
            if(sensitive || !sameTarget())return false;InputConnection c=getCurrentInputConnection();if(c==null)return false;
            c.beginBatchEdit();try{
                c.finishComposingText();
                EditorInsertion.Snapshot snapshot=read();if(snapshot==null)return false;
                int mode=EditorInsertion.replacementMode(snapshot,start,end,previousLength);
                if(mode==1){if(!c.deleteSurroundingText(previousLength,0))return false;}
                else if(mode==2 && !c.setSelection(start,end))return false;
                return c.commitText(text,1);
            }finally{c.endBatchEdit();}
        }
    };
    CursorDeletion.Port captureDeletion(CharSequence packageName){
        EditorInfo initial=getCurrentInputEditorInfo();
        if(!hasActiveEditor() || privateEditor() || initial==null || packageName==null || !initial.packageName.contentEquals(packageName))return null;
        final long owner=session;final String pkg=initial.packageName;final int field=initial.fieldId;
        return new CursorDeletion.Port(){
            public boolean current(){EditorInfo active=getCurrentInputEditorInfo();return hasActiveEditor() && session==owner && active!=null && pkg.equals(active.packageName) && field==active.fieldId && !privateEditor();}
            public EditorInsertion.Snapshot read(){return current()?InputSnapshots.readCurrent(getCurrentInputConnection()):null;}
            public boolean delete(CursorDeletion.Plan plan){
                if(!current())return false;InputConnection connection=getCurrentInputConnection();
                connection.beginBatchEdit();try{
                    connection.finishComposingText();
                    if(!current() || !CursorDeletion.same(plan.source,read()))return false;
                    return plan.selected?connection.commitText("",1):connection.deleteSurroundingText(plan.beforeUnits,0);
                }finally{connection.endBatchEdit();}
            }
        };
    }
    private void record(){
        EditorInfo info=getCurrentInputEditorInfo();if(busy || info==null || info.inputType==InputType.TYPE_NULL)return;
        if(!isPassword(info) && FlowAccessibility.instance!=null && FlowAccessibility.instance.focusedInputIsSensitive()){Toast.makeText(this,"This credential field does not expose a compatible private input connection.",Toast.LENGTH_LONG).show();return;}
        if(FlowAccessibility.instance!=null && FlowAccessibility.instance.isRecording()){Toast.makeText(this,"Finish the floating dictation first.",Toast.LENGTH_SHORT).show();return;}
        DictationProblem blocked=DictationProblem.before(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED,!Vault.get(this).isEmpty());if(blocked!=null){showProblem(blocked);return;}
        InputConnection c=getCurrentInputConnection();if(c==null)return;
        c.finishComposingText();targetSession=session;targetPackage=info.packageName;targetField=info.fieldId;
        sensitive=isPassword(info);numeric=sensitive && (info.inputType&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER;sensitiveAttempted=false;sensitiveSent=false;sensitiveEdited=false;
        if(sensitive){sensitiveStart=selectionStart;sensitiveEnd=selectionEnd;targetSelectionRevision=selectionRevision;if(sensitiveStart<0 || sensitiveEnd<0){setStatus("Tap the input again, then retry the mic.");return;}insertion=null;}
        else{EditorInsertion.Snapshot snapshot=port.read();if(snapshot==null){setStatus("Tap the input again, then retry the mic.");return;}insertion=new EditorInsertion(snapshot);}
        lastPartial="";pendingRaw="";pendingText="";pendingWarning="";pendingWriteAccepted=false;startedAt=android.os.SystemClock.elapsedRealtime();
        foregroundNotice=RecordingNotice.start(this,42,"Dictating into your selected input.",new Intent(this,FlowKeyboard.class).setAction("app.voiceflow.STOP_KEYBOARD"));
        generation++;busy=true;finishing=false;refreshControls();dictation=new Dictation(this,this,sensitive);dictation.start();
    }
    private String foregroundNotice=RecordingNotice.NONE;
    @Override public int onStartCommand(Intent intent,int flags,int id){if(intent!=null && "app.voiceflow.STOP_KEYBOARD".equals(intent.getAction()) && dictation!=null)dictation.stop();stopSelf(id);return START_NOT_STICKY;}
    @Override public void state(String value){if(!busy)return;finishing=dictation!=null && dictation.isFinishing();refreshControls();setStatus(value);}
    @Override public void partial(String text){if(!busy || text.trim().isEmpty())return;lastPartial=text;if(sensitive)return;if(insertion!=null && !insertion.write(port,Store.clean(this,text)))setStatus("Listening… · Copy on Finish");}
    @Override public void level(float value){if(FlowAccessibility.instance!=null)FlowAccessibility.instance.keyboardLevel(value);}
    @Override public void result(String raw,String text,String warning){
        setStatus("Inserting…");finishResult(raw,text,warning,0,generation);
    }
    /** Cancel during processing: stop requests and never insert a late result. */
    void cancelDictation(){
        if(!busy)return;
        if(dictation!=null)dictation.cancel();
        endRecording();setStatus("Cancelled");
        Toast.makeText(this,"Dictation cancelled. Nothing was inserted.",Toast.LENGTH_SHORT).show();
    }
    private void finishResult(String raw,String text,String warning,int attempt,int owner){
        if(!busy || owner!=generation)return;pendingRaw=raw;pendingText=text;pendingWarning=warning;
        if(busy && sensitive){
            String value=numeric?normalizePin(raw):raw;
            pendingText=value==null?raw:value;
            if(!sensitiveAttempted && value!=null && !sensitiveEdited && sameTarget() && selectionRevision==targetSelectionRevision){InputConnection c=getCurrentInputConnection();if(c!=null){sensitiveAttempted=true;sensitiveCursor=Math.min(sensitiveStart,sensitiveEnd)+value.length();try{sensitiveSent=c.commitText(value,1);}catch(RuntimeException failure){sensitiveSent=false;}}}
            boolean inserted=sensitiveSent && sameTarget() && selectionStart==sensitiveCursor && selectionEnd==sensitiveCursor;
            if(sensitiveSent && !inserted && sameTarget() && attempt<6){handler.postDelayed(()->finishResult(raw,text,warning,attempt+1,owner),150);return;}
            Recovery.keep(this,raw,value==null?raw:value,android.os.SystemClock.elapsedRealtime()-startedAt,inserted,true);endRecording();setStatus(inserted?"Inserted":"Not inserted");if(!warning.isEmpty())Toast.makeText(this,warning,Toast.LENGTH_LONG).show();return;
        }
        boolean inserted=insertion!=null && insertion.write(port,text);pendingWriteAccepted|=inserted;
        if(inserted)inserted=insertion.matches(port.read());
        if(!inserted && sameTarget() && attempt<14){handler.postDelayed(()->finishResult(raw,text,warning,attempt+1,owner),150);return;}
        if(Recovery.keep(this,raw,text,android.os.SystemClock.elapsedRealtime()-startedAt,inserted,false,!sameTarget())&&dictation!=null)dictation.acknowledgeSaved();endRecording();
        if(!inserted)setStatus("Copied and kept");
        else{setStatus("Inserted");if(!warning.isEmpty())Toast.makeText(this,warning,Toast.LENGTH_LONG).show();}
    }
    @Override public void error(String message){if(!busy)return;if(!lastPartial.isEmpty())Recovery.keep(this,lastPartial,sensitive?lastPartial:Store.clean(this,lastPartial),android.os.SystemClock.elapsedRealtime()-startedAt,false,sensitive,!sameTarget());endRecording();showProblem(DictationProblem.of(message));}
    /** Keyboard problems appear in the floating controls (with Fix) and the keyboard status, not as a toast. */
    private void showProblem(DictationProblem problem){
        android.util.Log.w("VonoTation","Keyboard dictation blocked: "+problem.title+" | "+problem.detail);
        setStatus(problem.title);
        if(FlowAccessibility.instance!=null)FlowAccessibility.instance.showProblem(problem);
        else Toast.makeText(this,problem.detail,Toast.LENGTH_LONG).show();
    }
    private void endRecording(){generation++;busy=false;finishing=false;if(dictation!=null){dictation.close();dictation=null;}insertion=null;lastPartial="";pendingRaw="";pendingText="";pendingWarning="";pendingWriteAccepted=false;sensitive=false;sensitiveAttempted=false;sensitiveSent=false;sensitiveEdited=false;RecordingNotice.stop(this,foregroundNotice);foregroundNotice=RecordingNotice.NONE;refreshControls();}
    private void refreshBubble(){handler.post(()->{if(FlowAccessibility.instance!=null)FlowAccessibility.instance.updateBubble();});}
    private boolean pendingAcknowledged(){try{return sameTarget() && (sensitive?sensitiveSent && selectionStart==sensitiveCursor && selectionEnd==sensitiveCursor:pendingWriteAccepted && insertion!=null && insertion.matches(port.read()));}catch(RuntimeException unavailable){return false;}}
    @Override public void onDestroy(){if(dictation!=null && busy){if(!pendingRaw.isEmpty()){if(Recovery.keep(this,pendingRaw,pendingText,android.os.SystemClock.elapsedRealtime()-startedAt,pendingAcknowledged(),sensitive,!sameTarget()))dictation.acknowledgeSaved();if(!pendingWarning.isEmpty())Toast.makeText(this,pendingWarning,Toast.LENGTH_LONG).show();dictation.close();}else dictation.finishDetached(android.os.SystemClock.elapsedRealtime()-startedAt,true);dictation=null;}endRecording();handler.removeCallbacksAndMessages(null);instance=null;refreshBubble();super.onDestroy();}
}
