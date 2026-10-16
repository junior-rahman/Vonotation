package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.SurroundingText;

/** Android framework fixture for fresh SurroundingText/ExtractedText destructive-read policy. */
public final class InputSnapshotTest extends Instrumentation {
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();try{
        require(android.os.Build.VERSION.SDK_INT>=31,"Run input snapshot fixture on Android 12 or newer");
        Connection input=new Connection(getTargetContext());EditorInsertion.Snapshot snapshot=InputSnapshots.readCurrent(input);
        require(snapshot.start==7&&snapshot.end==7,"Fresh extracted selection did not replace unknown surrounding offset");
        require(input.extractedReads==1,"Expected a fresh extracted read after unknown surrounding offset");
        input.extracted=false;snapshot=InputSnapshots.readCurrent(input);
        require(snapshot.start==-1&&snapshot.end==-1&&"one two".equals(snapshot.before),"Valid relative context was discarded without extraction");
        require(CursorDeletion.plan(snapshot)!=null,"Relative current-caret word should remain plannable");
        input.extracted=true;input.invalidExtraction=true;snapshot=InputSnapshots.readCurrent(input);
        require(snapshot.start==-1,"Invalid extracted offsets must not replace valid relative context");
        input.invalidExtraction=false;input.absolute=true;int reads=input.extractedReads;snapshot=InputSnapshots.readCurrent(input);
        require(snapshot.start==7&&input.extractedReads==reads,"Fresh absolute surrounding offsets should not need extraction");
        result.putString("result","PASS: "+checks+" Android fresh extracted-offset and relative-caret checks");
    }catch(Throwable failure){result.putString("failure",android.util.Log.getStackTraceString(failure));}finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);}
    private void require(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static final class Connection extends BaseInputConnection {
        boolean extracted=true,invalidExtraction,absolute;int extractedReads;
        Connection(android.content.Context context){super(new android.view.View(context),false);}
        @Override public SurroundingText getSurroundingText(int before,int after,int flags){return new SurroundingText("one two tail",7,7,absolute?0:-1);}
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request,int flags){extractedReads++;if(!extracted)return null;ExtractedText value=new ExtractedText();value.text="one two tail";value.startOffset=invalidExtraction?-1:0;value.selectionStart=7;value.selectionEnd=7;return value;}
    }
}
