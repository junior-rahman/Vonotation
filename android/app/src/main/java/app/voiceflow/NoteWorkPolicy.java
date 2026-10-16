package app.voiceflow;

/** Shared rules for immutable async note snapshots and fair, bounded retries. */
final class NoteWorkPolicy {
    static boolean canApply(String captured,String current){return captured!=null&&current!=null&&captured.equals(current);}
    static boolean eligible(boolean pending,boolean recording,long retryAfter,long now){return pending&&!recording&&retryAfter<=now;}
    static long retryDelay(int attempt){return 5*60*1000L*(1L<<Math.min(6,Math.max(0,attempt-1)));}
    private NoteWorkPolicy(){}
}
