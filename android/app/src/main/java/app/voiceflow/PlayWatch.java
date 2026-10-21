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

/** Shows a player notification while a recording is playing, including after the app is closed. */
public final class PlayWatch extends Service {
    static final String STOP="app.voiceflow.PLAY_STOP";
    private static final int ID=48;

    private static Context app;
    static void show(Context context,String title){
        app=context.getApplicationContext();
        Intent intent=new Intent(app,PlayWatch.class).putExtra("title",title==null||title.isEmpty()?"Recording":title);
        try{if(Build.VERSION.SDK_INT>=26)app.startForegroundService(intent);else app.startService(intent);}
        catch(RuntimeException ignored){}
    }

    static void hide(){
        if(app==null)return;
        try{app.stopService(new Intent(app,PlayWatch.class));}
        catch(RuntimeException ignored){}
    }

    @Override public IBinder onBind(Intent intent){return null;}

    // Both merged product manifests declare PlayWatch as mediaPlayback; variant lint cannot resolve it.
    @android.annotation.SuppressLint("ForegroundServiceType")
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null && STOP.equals(intent.getAction())){Notes.stopFromNotice();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
        String title=intent==null?"Recording":intent.getStringExtra("title");
        if(title==null || title.isEmpty())title="Recording";
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("playback","Playback",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(this,49,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,48,new Intent(this,PlayWatch.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notice=new Notification.Builder(this,"playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText("Playing")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(open)
            .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0))
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Stop",stop).build())
            .build();
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(ID,notice);
        }catch(RuntimeException error){
            notifications.notify(ID,notice);
        }
        return START_NOT_STICKY;
    }
}
