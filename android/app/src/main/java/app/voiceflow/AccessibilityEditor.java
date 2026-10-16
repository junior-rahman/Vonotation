package app.voiceflow;

import android.accessibilityservice.InputMethod;
import android.view.inputmethod.EditorInfo;

/** Android 13+ editor connection; it coexists with the user's normal keyboard. */
@android.annotation.TargetApi(33)
final class AccessibilityEditor extends InputMethod {
    private final FlowAccessibility service;
    private long generation;
    private int selectionStart=-1,selectionEnd=-1;
    private long selectionRevision;
    private SensitiveTarget activeSensitiveTarget;
    AccessibilityEditor(FlowAccessibility service){super(service);this.service=service;}
    @Override public void onStartInput(EditorInfo info,boolean restarting){super.onStartInput(info,restarting);generation++;selectionStart=info.initialSelStart;selectionEnd=info.initialSelEnd;service.editorChanged();}
    @Override public void onFinishInput(){generation++;super.onFinishInput();service.editorChanged();}
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd){super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);if(newStart!=selectionStart || newEnd!=selectionEnd)selectionRevision++;selectionStart=newStart;selectionEnd=newEnd;service.editorChanged();}
    private boolean connected(){EditorInfo info=getCurrentInputEditorInfo();return getCurrentInputStarted() && getCurrentInputConnection()!=null && info!=null && !service.getPackageName().equals(info.packageName);}
    /** Framework editor metadata remains useful when a WebView exposes no editable node. */
    boolean hasActiveEditor(){return connected();}
    CharSequence activePackage(){EditorInfo info=getCurrentInputEditorInfo();return connected()?info.packageName:null;}
    boolean sensitive(){return connected() && FlowKeyboard.isPassword(getCurrentInputEditorInfo());}
    /** Cached framework metadata only: safe from clipboard callbacks, including our own Activity. */
    boolean clipboardSensitiveContext(){EditorInfo info=getCurrentInputEditorInfo();return getCurrentInputStarted() && info!=null && FlowKeyboard.isPasswordType(info.inputType);}
    boolean eligible(){EditorInfo info=getCurrentInputEditorInfo();return getCurrentInputStarted() && getCurrentInputConnection()!=null && info!=null && !service.getPackageName().equals(info.packageName) && !FlowKeyboard.isPassword(info) && !service.focusedInputIsSensitive();}
    /** A native commit into a credential selection, without reading any secret text. */
    final class SensitiveTarget {
        private final String pkg;private final int field,start,end;private final long expectedGeneration,expectedRevision;private final boolean numeric;
        private boolean invalid,sent;private int expectedCursor;
        private SensitiveTarget(EditorInfo info){pkg=info.packageName;field=info.fieldId;start=selectionStart;end=selectionEnd;expectedGeneration=generation;expectedRevision=selectionRevision;numeric=(info.inputType&android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER;}
        boolean current(){EditorInfo info=getCurrentInputEditorInfo();return connected() && generation==expectedGeneration && pkg.equals(info.packageName) && field==info.fieldId && FlowKeyboard.isPassword(info);}
        boolean matchesPackage(CharSequence value){return value!=null && pkg.contentEquals(value);}
        void release(){invalid=true;if(activeSensitiveTarget==this)activeSensitiveTarget=null;}
        void invalidate(){if(!sent)invalid=true;}
        boolean sent(){return sent;}
        boolean numeric(){return numeric;}
        boolean commit(String text){
            if(sent)return !invalid;
            if(invalid || !current() || selectionRevision!=expectedRevision || start<0 || end<0 || text==null || text.isEmpty())return false;
            expectedCursor=Math.min(start,end)+text.length();sent=true;
            try{getCurrentInputConnection().commitText(text,1,null);return true;}catch(RuntimeException failure){invalid=true;return false;}
        }
        boolean acknowledged(){return sent && current() && selectionStart==expectedCursor && selectionEnd==expectedCursor;}
    }
    SensitiveTarget captureSensitive(){EditorInfo info=getCurrentInputEditorInfo();if(!connected() || !FlowKeyboard.isPassword(info))return null;activeSensitiveTarget=new SensitiveTarget(info);return activeSensitiveTarget;}
    EditorInsertion.Port capture(){
        if(!eligible())return null;
        EditorInfo info=getCurrentInputEditorInfo();final String pkg=info.packageName;final int field=info.fieldId;final long expected=generation;
        return new EditorInsertion.Port(){
            public boolean current(){EditorInfo active=getCurrentInputEditorInfo();return eligible() && expected==generation && pkg.equals(active.packageName) && field==active.fieldId;}
            public EditorInsertion.Snapshot read(){return current()?InputSnapshots.from(getCurrentInputConnection().getSurroundingText(EditorInsertion.CONTEXT,EditorInsertion.CONTEXT,0)):null;}
            public boolean replace(int start,int end,int previousLength,String text){
                if(!current())return false;AccessibilityInputConnection connection=getCurrentInputConnection();
                EditorInsertion.Snapshot snapshot=read();if(snapshot==null)return false;
                int mode=EditorInsertion.replacementMode(snapshot,start,end,previousLength);
                // A regular IME may have resumed composition on the inserted word.
                // Accessibility commitText first calls finishComposingText in Android's
                // remote editor bridge, so this empty commit preserves the composing
                // word. Ordinary IME commitText does not provide that guarantee.
                if(mode==1){connection.commitText("",1,null);connection.deleteSurroundingText(previousLength,0);}
                else if(mode==2)connection.setSelection(start,end);
                connection.commitText(text,1,null);return true;
            }
        };
    }
    CursorDeletion.Port captureDeletion(CharSequence packageName){
        if(!eligible()||packageName==null)return null;
        EditorInfo initial=getCurrentInputEditorInfo();
        if(initial.packageName==null||!initial.packageName.contentEquals(packageName))return null;
        EditorInfo info=getCurrentInputEditorInfo();final String pkg=info.packageName;final int field=info.fieldId;final long expected=generation;
        return new CursorDeletion.Port(){
            public boolean current(){EditorInfo active=getCurrentInputEditorInfo();return eligible() && expected==generation && pkg.equals(active.packageName) && field==active.fieldId;}
            public EditorInsertion.Snapshot read(){return current()?InputSnapshots.from(getCurrentInputConnection().getSurroundingText(EditorInsertion.CONTEXT,EditorInsertion.CONTEXT,0)):null;}
            public boolean delete(CursorDeletion.Plan plan){
                if(!current() || !CursorDeletion.same(plan.source,read()))return false;
                AccessibilityInputConnection connection=getCurrentInputConnection();
                if(plan.selected)connection.commitText("",1,null);
                else {
                    // Android's accessibility bridge ends composition before this empty commit.
                    // Then delete only before the unchanged current caret, never select an old range.
                    connection.commitText("",1,null);
                    // Both commands target this connection's current caret. Do not interpose an
                    // async selection-callback equality gate after the harmless empty commit.
                    connection.deleteSurroundingText(plan.beforeUnits,0);
                }
                return true;
            }
        };
    }

}
