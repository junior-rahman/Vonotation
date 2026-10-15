package app.voiceflow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/** Brings the call watcher back if the process was gone when the phone rang. */
public final class CallEvents extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction()))return;
        String state=intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        if(TelephonyManager.EXTRA_STATE_RINGING.equals(state)){
            if(VoiceTake.noteRunning() && !VoiceTake.callRunning()){CallWatch.decline(context);return;}
            if(Translate.going()){Translate.stop();CallWatch.expectCall();android.widget.Toast.makeText(context,"Translation finished. This call will be recorded.",android.widget.Toast.LENGTH_LONG).show();}
        }
        if(TelephonyManager.EXTRA_STATE_IDLE.equals(state))CallWatch.dropExpect();
        if(!CallWatch.on(context) && !CallWatch.waitingForCall())return;
        if(TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)){CallWatch.dropExpect();CallWatch.recordNow(context);}
        else if(TelephonyManager.EXTRA_STATE_IDLE.equals(state) && CallWatch.on(context)){try{context.startService(new Intent(context,CallWatch.class).setAction(CallWatch.STOP));}catch(RuntimeException ignored){}}
    }
}
