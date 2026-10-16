package app.voiceflow;

import android.content.Context;

/** Restores the installer barrier after process death as well as serializing live capture starts. */
final class CaptureBarrier {
    private static final CaptureGate gate=new CaptureGate();
    private CaptureBarrier(){}
    static synchronized CaptureGate.Lease begin(Context context){
        if(AppUpdate.installPending(context))return null;
        return gate.begin();
    }
    static synchronized boolean reserveInstall(){return gate.reserveInstall();}
    static synchronized void releaseInstall(){gate.releaseInstall();}
    static synchronized boolean installPending(Context context){return gate.installationReserved()||AppUpdate.installPending(context);}
}
