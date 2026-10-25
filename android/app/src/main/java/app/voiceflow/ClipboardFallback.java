package app.voiceflow;

/** A lost-input copy waits for a later, usable input activation before revealing the fan. */
final class ClipboardFallback {
    static boolean shouldReveal(boolean inputLost,boolean inserted,boolean sensitive,boolean copied){
        return inputLost&&!inserted&&!sensitive&&copied;
    }
    static final class Pending {
        private String text;
        private long copiedAtActivation;
        boolean available(){return text!=null;}
        void offer(String value,long activation){
            if(value==null||value.trim().isEmpty())return;
            text=value;copiedAtActivation=activation;
        }
        String take(long activation,boolean inputActive,boolean blocked){
            if(text==null||activation<=copiedAtActivation||!inputActive||blocked)return null;
            String value=text;text=null;return value;
        }
    }
    private ClipboardFallback(){}
}
