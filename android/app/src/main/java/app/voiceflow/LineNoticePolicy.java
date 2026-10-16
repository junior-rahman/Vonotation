package app.voiceflow;

/** Debounces authenticated recovery and limits new connection notices across process restarts. */
final class LineNoticePolicy {
    static final long HEALTHY_MS=60000;
    static final long NOTICE_COOLDOWN_MS=30*60*1000;
    private long healthySince=-1;
    boolean healthy(long now){
        if(healthySince<0 || now<healthySince){healthySince=now;return false;}
        return now-healthySince>=HEALTHY_MS;
    }
    void outage(){healthySince=-1;}
    static boolean shouldNotify(long now,long lastNotified,boolean outageAlreadyNotified){
        if(outageAlreadyNotified)return false;
        return lastNotified<=0 || now<lastNotified || now-lastNotified>=NOTICE_COOLDOWN_MS;
    }
}
