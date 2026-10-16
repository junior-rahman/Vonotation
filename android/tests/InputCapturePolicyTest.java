package app.voiceflow;

public final class InputCapturePolicyTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        check(InputCapturePolicy.evidence(false,true,false,false)==InputCapturePolicy.Evidence.INPUT,"native WebView editor without an editable node is dictation");
        check(InputCapturePolicy.evidence(true,false,false,false)==InputCapturePolicy.Evidence.INPUT,"accessible input remains dictation");
        check(InputCapturePolicy.evidence(false,false,true,false)==InputCapturePolicy.Evidence.UNKNOWN,"visible third-party keyboard must not silently create a thought");
        check(InputCapturePolicy.evidence(false,false,false,true)==InputCapturePolicy.Evidence.UNKNOWN,"ambiguous focused input must not create a thought");
        check(InputCapturePolicy.evidence(false,false,false,false)==InputCapturePolicy.Evidence.NONE,"confirmed no-input supports thoughts");
        InputCapturePolicy.Session dictation=InputCapturePolicy.start(InputCapturePolicy.Evidence.INPUT);
        InputCapturePolicy.evidence(false,false,false,false);
        check(!dictation.thought(),"leaving an input never changes an existing dictation to a thought");
        InputCapturePolicy.evidence(true,true,true,false);
        check(!dictation.thought(),"returning to an input preserves the original dictation mode");
        check(dictation.insertionAllowed(),"the original connected input can receive text");
        dictation.loseDestination();
        InputCapturePolicy.evidence(true,true,true,false);
        check(!dictation.insertionAllowed(),"a reused input node or new editor epoch cannot regain insertion authority");
        check(!dictation.thought(),"losing insertion authority keeps the session as dictation for clipboard recovery");
        InputCapturePolicy.Session thought=InputCapturePolicy.start(InputCapturePolicy.Evidence.NONE);
        check(thought.thought(),"an explicit no-input capture remains a thought");
        boolean rejected=false;try{InputCapturePolicy.start(InputCapturePolicy.Evidence.UNKNOWN);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"unknown input evidence cannot start a thought session");
        System.out.println("PASS: input evidence and immutable capture origin.");
    }
}
