package app.voiceflow;

/** Consent and call-log matching rules shared by the receiver and its regression tests. */
final class CallSessionPolicy {
    private CallSessionPolicy(){}
    static boolean mayStart(boolean callsEnabled,boolean offHook){return callsEnabled && offHook;}
    static long callLogDistance(long startedAt,long endedAt,long logAt,long durationSeconds){
        if(logAt<startedAt-120000 || logAt>endedAt+5000 || durationSeconds<0)return Long.MAX_VALUE;
        long logEnd=logAt+Math.min(durationSeconds,24*60*60)*1000;
        // The log normally dates the ring/dial start. Require the same end as well as a nearby start.
        long endDistance=Math.abs(logEnd-endedAt);
        if(endDistance>20000)return Long.MAX_VALUE;
        return endDistance+Math.abs(logAt-startedAt);
    }
}
