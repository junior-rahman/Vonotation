package app.voiceflow;

/** Only a successful clipboard fallback after losing a dictation destination reveals the fan. */
final class ClipboardFallback {
    static boolean shouldReveal(boolean inputLost,boolean inserted,boolean sensitive,boolean copied){
        return inputLost&&!inserted&&!sensitive&&copied;
    }
    private ClipboardFallback(){}
}
