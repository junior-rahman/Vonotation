package app.voiceflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Keeps a call recording alive after you leave VonoTation for the phone app. */
public final class CallService extends Service {
    static final String START="app.voiceflow.CALL_ON";
    static final String STOP="app.voiceflow.CALL_OFF";
    static final String DONE="app.voiceflow.CALL_DONE";

    static void begin(Context context) {
        if(!CallWatch.on(context) || CaptureBarrier.installPending(context))return;
        Intent intent=new Intent(context,CallService.class).setAction(START);
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void done(Context context) {
        try { context.startService(new Intent(context,CallService.class).setAction(DONE)); }
        catch(RuntimeException ignored) {}
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action=intent==null?START:intent.getAction();
        if(DONE.equals(action)){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(action)){VoiceTake.finishCall(VoiceTake.callId());stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        if(!CallWatch.on(this) || CaptureBarrier.installPending(this)){stopSelf();return START_NOT_STICKY;}
        notice();
        if(!VoiceTake.running())Notes.beginCall(this);
        return START_NOT_STICKY;
    }

    private void notice() {
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("calls","Call recording",NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getService(this,43,new Intent(this,CallService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"calls").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOnlyAlertOnce(true).setDefaults(0).setSound(null).setVibrate(new long[0]).setShowWhen(false)
            .setContentTitle("Recording this call").setContentText("VonoTation is keeping the call. Tap Finish when it ends.").setOngoing(true)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Finish",stop).build()).build();
        if(Build.VERSION.SDK_INT>=30)startForeground(43,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        else startForeground(43,notice);
    }
}
