package app.voiceflow;

/** Input evidence chooses the capture mode once; later focus changes cannot turn dictation into a note. */
final class InputCapturePolicy {
    enum Evidence { NONE, INPUT, UNKNOWN }
    static Evidence evidence(boolean accessibleInput,boolean nativeEditor,boolean visibleKeyboard,boolean ambiguousFocus){
        if(accessibleInput || nativeEditor)return Evidence.INPUT;
        return visibleKeyboard || ambiguousFocus?Evidence.UNKNOWN:Evidence.NONE;
    }
    static final class Session {
        private final boolean thought;
        private boolean destinationLost;
        private Session(boolean thought){this.thought=thought;}
        boolean thought(){return thought;}
        void loseDestination(){destinationLost=true;}
        boolean insertionAllowed(){return !thought && !destinationLost;}
    }
    static Session start(Evidence evidence){
        if(evidence==Evidence.UNKNOWN)throw new IllegalArgumentException("Choose a confirmed input or no-input capture.");
        return new Session(evidence==Evidence.NONE);
    }
    private InputCapturePolicy(){}
}
