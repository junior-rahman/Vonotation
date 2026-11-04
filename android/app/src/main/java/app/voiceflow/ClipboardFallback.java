package app.voiceflow;

/** A lost-input copy waits for a later, usable input activation before revealing the fan. */
final class ClipboardFallback {
    static boolean shouldReveal(boolean inputLost,boolean inserted,boolean sensitive,boolean copied){
        return inputLost&&!inserted&&!sensitive&&copied;
    }
    static final class Pending {
        private String text;
        private long copiedAtActivation,generation;
        boolean available(){return text!=null;}
        long generation(){return generation;}
        void offer(String value,long activation){
            if(value==null||value.trim().isEmpty())return;
            text=value;copiedAtActivation=activation;generation++;
        }
        String peek(long activation,boolean inputActive,boolean blocked){
            if(text==null||activation<=copiedAtActivation||!inputActive||blocked)return null;
            return text;
        }
        // Rendering or losing focus does not consume the copy. Only explicit dismissal or acknowledged paste does.
        void complete(long shownGeneration){if(generation==shownGeneration)text=null;}
    }
    private ClipboardFallback(){}
}
