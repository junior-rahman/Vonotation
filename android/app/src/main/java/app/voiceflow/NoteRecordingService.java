package app.voiceflow;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** The notes app owns its microphone without an accessibility/keyboard service. */
public final class NoteRecordingService extends Service {
    private static final String START="app.vonotation.notes.RECORD_NOTE";
    private static final String FINISH="app.vonotation.notes.FINISH_NOTE";
    private static final String CANCEL="app.vonotation.notes.CANCEL_NOTE";
    private static final String CHANNEL="voice_notes_capture";
    private static final int NOTICE=72;
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static final NoteCaptureSession session=new NoteCaptureSession();
    private static Request pending;
    private static NoteRecordingService instance;
    private long owner=-1;

    private static final class Request {
        final long id;final Runnable ready;final java.util.function.Consumer<String> failed;
        Request(long id,Runnable ready,java.util.function.Consumer<String> failed){this.id=id;this.ready=ready;this.failed=failed;}
    }

    static boolean busy(){return session.busy();}

    /** Called from the visible recording button. Recording begins only after startForeground. */
    static void begin(Activity activity,long id,Runnable ready,java.util.function.Consumer<String> failed){
        if(!AppProduct.isNotes() || activity.isFinishing() || activity.isDestroyed() || !activity.hasWindowFocus()){
            failed.accept("Return to Vono Notes and tap Record to begin.");return;
        }
        if(!session.begin(id)){failed.accept("The previous recording is still being saved.");return;}
        Context app=activity.getApplicationContext();
        Request request=new Request(id,ready,failed);pending=request;
        try {
            app.startForegroundService(new Intent(app,NoteRecordingService.class).setAction(START).putExtra("note_id",id));
            main.postDelayed(()->{
                if(pending==request && session.owns(id))failPending(app,id,"The recording service did not start. Tap Record to try again.");
            },10000);
        }catch(RuntimeException unavailable){failPending(app,id,"Could not start background microphone recording. Keep Vono Notes open and try again.");}
    }

    static void finalizing(Context context,long id){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->finalizing(context,id));return;}
        if(!session.finalizing(id))return;
        NoteRecordingService active=instance;
        if(active!=null && active.owner==id){active.showNotice(id,true);active.awaitFinalization(id);}
    }

    /** A late completion for an older recording cannot stop a newer service session. */
    static void completed(Context context,long id){
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post(()->completed(context,id));return;}
        if(!session.release(id))return;
        if(pending!=null && pending.id==id)pending=null;
        NoteRecordingService active=instance;
        if(active!=null && active.owner==id){active.owner=-1;active.stopForeground(STOP_FOREGROUND_REMOVE);active.stopSelf();}
        else try{context.getApplicationContext().stopService(new Intent(context,NoteRecordingService.class));}catch(RuntimeException ignored){}
    }

    private static void failPending(Context context,long id,String message){
        Request request=pending;
        if(request==null || request.id!=id || !session.owns(id))return;
        pending=null;completed(context,id);request.failed.accept(message);
    }

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){super.onCreate();instance=this;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(!AppProduct.isNotes()){stopSelf(startId);return START_NOT_STICKY;}
        long id=intent==null?-1:intent.getLongExtra("note_id",-1);
        if(!session.owns(id)){if(!session.busy())stopSelf(startId);return START_NOT_STICKY;}
        String action=intent.getAction();
        if(FINISH.equals(action)){
            if(Notes.finishRecording(id))finalizing(this,id);
            return START_NOT_STICKY;
        }
        if(CANCEL.equals(action)){
            if(Notes.cancelRecording(this,id))finalizing(this,id);
            return START_NOT_STICKY;
        }
        if(!START.equals(action))return START_NOT_STICKY;
        Request request=pending;
        if(request==null || request.id!=id)return START_NOT_STICKY;
        owner=id;
        try {
            Notification notice=notice(id,false);
            if(Build.VERSION.SDK_INT>=30)startForeground(NOTICE,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NOTICE,notice);
        }catch(RuntimeException unavailable){
            failPending(this,id,"Android could not allow background microphone recording. Keep Vono Notes open and check microphone permission.");
            stopSelf(startId);return START_NOT_STICKY;
        }
        pending=null;
        if(session.ready(id)){
            try{request.ready.run();}
            catch(RuntimeException failed){
                if(Notes.finishRecording(id))finalizing(this,id);
                else completed(this,id);
                request.failed.accept("The recording could not finish starting. Any saved source audio remains on this phone.");
            }
        }
        return START_NOT_STICKY;
    }

    private void showNotice(long id,boolean saving){
        try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE,notice(id,saving));}catch(RuntimeException ignored){}
    }
    private Notification notice(long id,boolean saving){
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel=new NotificationChannel(CHANNEL,"Voice note recording",NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null,null);channel.enableVibration(false);notifications.createNotificationChannel(channel);
        PendingIntent open=PendingIntent.getActivity(this,73,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notice=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(saving?"Saving voice note":"Recording voice note")
            .setContentText(saving?"Keeping your source audio on this phone.":"Keeps recording until you choose Finish or Cancel.")
            .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open);
        if(!saving){
            notice.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Finish",action(id,FINISH,"finish")).build());
            notice.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Cancel",action(id,CANCEL,"cancel")).build());
        }
        return notice.build();
    }
    private PendingIntent action(long id,String action,String suffix){
        Intent intent=new Intent(this,NoteRecordingService.class).setAction(action)
            .setData(Uri.parse("vononotes://recording/"+id+"/"+suffix)).putExtra("note_id",id);
        return PendingIntent.getService(this,74,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private void awaitFinalization(long id){
        main.postDelayed(()->{
            if(instance!=this || owner!=id || !session.owns(id))return;
            if(VoiceTake.updateBusy())awaitFinalization(id);else completed(this,id);
        },100);
    }
    private static void awaitStoppedCapture(Context context,long id){
        main.postDelayed(()->{
            if(!session.owns(id))return;
            if(VoiceTake.updateBusy())awaitStoppedCapture(context,id);else completed(context,id);
        },100);
    }
    @Override public void onDestroy(){
        long id=owner;owner=-1;if(instance==this)instance=null;
        // A system stop must seal the source, never leave a microphone running
        // without foreground ownership. It must never act on another session.
        if(session.owns(id)){
            if(pending!=null && pending.id==id)failPending(this,id,"Recording service stopped before the microphone could begin.");
            else {
                Notes.finishRecording(id);session.finalizing(id);
                awaitStoppedCapture(getApplicationContext(),id);
            }
        }
        super.onDestroy();
    }
}
