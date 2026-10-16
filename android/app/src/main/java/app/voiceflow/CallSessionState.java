package app.voiceflow;

/** A delayed teardown may close only the call epoch that scheduled it. */
final class CallSessionState {
    private long id=-1,epoch;
    private boolean finishing,queued;
    long id(){return id;}
    long epoch(){return epoch;}
    void begin(long nextId){id=nextId;epoch++;finishing=false;queued=false;}
    void finish(){finishing=true;}
    void queueStart(){if(finishing)queued=true;}
    boolean queued(){return queued;}
    boolean owns(long ownerId,long ownerEpoch){return id==ownerId && epoch==ownerEpoch;}
}
