package app.voiceflow;

/** Atomic ownership between audio capture and an Android installation confirmation. */
final class CaptureGate {
    private int captures;
    private boolean installing;

    synchronized Lease begin(){
        if(installing)return null;
        captures++;
        return new Lease(this);
    }
    synchronized boolean reserveInstall(){
        if(captures!=0)return false;
        installing=true;
        return true;
    }
    synchronized void releaseInstall(){installing=false;}
    synchronized boolean installationReserved(){return installing;}
    private synchronized void release(){if(captures>0)captures--;}

    static final class Lease implements AutoCloseable {
        private CaptureGate owner;
        private Lease(CaptureGate owner){this.owner=owner;}
        @Override public synchronized void close(){if(owner!=null){owner.release();owner=null;}}
    }
}
