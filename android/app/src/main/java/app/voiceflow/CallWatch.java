package app.voiceflow;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import java.io.File;

/** A transient foreground recorder, started by the phone-state receiver only for a connected call. */
public final class CallWatch extends Service {
    static final String STOP="app.voiceflow.CALL_STOP";
    private static final String QUIT="app.voiceflow.CALL_QUIT";
    private static final String NOW="app.voiceflow.CALL_NOW";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private TelephonyManager telephony;
    private PhoneStateListener legacy;
    private TelephonyCallback modern;
    private boolean ear;
    private boolean ending;
    private long recordingGeneration;

    static boolean on(Context context){return Store.bool(context,"record_calls",false);}
    private static volatile boolean expect;

    static void expectCall(){expect=true;}
    static void dropExpect(){expect=false;}
    static boolean waitingForCall(){return expect;}

    /** Rejects a ringing call so a voice note is not cut off. */
    static void decline(Context context){
        if(context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            android.widget.Toast.makeText(context,"Allow calls permission so a voice note can decline an incoming call.",android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try{
            android.telecom.TelecomManager telecom=(android.telecom.TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
            if(telecom!=null && telecom.endCall())android.widget.Toast.makeText(context,"Call declined. A voice note is still recording.",android.widget.Toast.LENGTH_LONG).show();
        }catch(SecurityException ignored){}
    }

    static void wake(Context context){LineWatch.start(context,null);}

    static void recordNow(Context context){
        Intent intent=new Intent(context,CallWatch.class).setAction(NOW);
        try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);}
        catch(RuntimeException error){android.util.Log.w("VonoTation","Android did not allow call recording to start",error);}
    }

    static void quit(Context context){
        try{context.stopService(new Intent(context,CallWatch.class));}
        catch(RuntimeException ignored){}
    }

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?null:intent.getAction();
        if(STOP.equals(action) || QUIT.equals(action)){
            ending=true;
            if(LineWire.running()){LineWire.stop();return START_NOT_STICKY;}
            ShellEar.stop();
            if(VoiceTake.callRunning()){VoiceTake.finish();handler.postDelayed(this::endWatch,2500);return START_NOT_STICKY;}
            endWatch();
            return START_NOT_STICKY;
        }
        ending=false;
        if(!recordingNotice()){endWatch();return START_NOT_STICKY;}
        LineWatch.start(this,null);
        ensureEar();
        int state=TelephonyManager.CALL_STATE_IDLE;
        try{if(telephony!=null)state=telephony.getCallState();}catch(SecurityException ignored){}
        if(NOW.equals(action) || state==TelephonyManager.CALL_STATE_OFFHOOK)begin();
        else endWatch();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){ending=true;LineWire.stop();dropEar();super.onDestroy();}

    private boolean forcedSpeaker;

    private void begin(){
        if(ending || !recordingNotice())return;
        if(LineWire.running() || ShellEar.running() || VoiceTake.callRunning())return;
        if(VoiceTake.noteRunning()){decline(this);return;}
        File folder=new File(getFilesDir(),"notes");
        if(!folder.isDirectory())folder.mkdirs();
        long id=System.currentTimeMillis();
        File dest=new File(folder,id+"-0.m4a");
        long take=++recordingGeneration;
        if(LineWire.paired(this) && LineWire.start(this,dest,new LineWire.Callback(){
            public void saved(){Notes.keepFile(CallWatch.this,dest);if(take==recordingGeneration)endWatch();}
            public void failed(){if(dest.exists() && dest.length()<1000)dest.delete();if(take!=recordingGeneration)return;if(!ending && stillInCall())mic();else endWatch();}
        }))return;
        mic();
    }

    private void mic(){if(ending)return;if(!lineOpen())speakerOn();Notes.beginCall(this);}

    private void endWatch(){ending=true;speakerOff();try{stopForeground(STOP_FOREGROUND_REMOVE);}catch(RuntimeException ignored){}stopSelf();}

    /** True when this phone lets a normal app open the call audio itself. */
    private boolean lineOpen() {
        AudioRecord rec=null;
        try {
            int min=AudioRecord.getMinBufferSize(16000,android.media.AudioFormat.CHANNEL_IN_MONO,android.media.AudioFormat.ENCODING_PCM_16BIT);
            if(min<=0)min=2048;
            rec=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16000).setChannelMask(android.media.AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(min*2).build();
            if(rec.getState()!=AudioRecord.STATE_INITIALIZED)return false;
            rec.startRecording();
            return rec.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING;
        } catch(Exception error) { return false; }
        finally { if(rec!=null){try{rec.stop();}catch(Exception ignored){} try{rec.release();}catch(Exception ignored){}} }
    }

    private void speakerOn() {
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null || audio.isSpeakerphoneOn())return;
        forcedSpeaker=true;
        try {
            if(Build.VERSION.SDK_INT>=31){
                for(AudioDeviceInfo device:audio.getAvailableCommunicationDevices()){
                    if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){audio.setCommunicationDevice(device);break;}
                }
            }
            audio.setSpeakerphoneOn(true);
        } catch(RuntimeException ignored) { forcedSpeaker=false; }
    }

    private void speakerOff() {
        if(!forcedSpeaker)return;
        forcedSpeaker=false;
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(audio==null)return;
        try {
            audio.setSpeakerphoneOn(false);
            if(Build.VERSION.SDK_INT>=31)audio.clearCommunicationDevice();
        } catch(RuntimeException ignored) {}
    }

    private boolean stillInCall() {
        if(!on(this) || VoiceTake.callRunning() || ShellEar.running() || LineWire.running())return false;
        try { return telephony!=null && telephony.getCallState()==TelephonyManager.CALL_STATE_OFFHOOK; }
        catch(SecurityException error) { return false; }
    }

    private void changed(int state){
        if(!on(this))return;
        if(state==TelephonyManager.CALL_STATE_OFFHOOK)begin();
        else if(state==TelephonyManager.CALL_STATE_IDLE){
            ending=true;
            boolean live=LineWire.running() || ShellEar.running() || VoiceTake.callRunning();
            if(LineWire.running())LineWire.stop();
            else if(ShellEar.running())ShellEar.stop();
            else if(VoiceTake.callRunning()){VoiceTake.finish();handler.postDelayed(this::endWatch,2500);}
            else endWatch();
            speakerOff();
            if(live)handler.postDelayed(this::tagLatest,1600);
        }
    }

    private void tagLatest(){
        if(checkSelfPermission(android.Manifest.permission.READ_CALL_LOG)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        String who="";
        try(Cursor cursor=getContentResolver().query(CallLog.Calls.CONTENT_URI,new String[]{CallLog.Calls.CACHED_NAME,CallLog.Calls.NUMBER},null,null,CallLog.Calls.DATE+" DESC")){
            if(cursor!=null && cursor.moveToFirst()){
                String name=cursor.getString(0),number=cursor.getString(1);
                who=name!=null && !name.isEmpty()?name:number==null?"":number;
            }
        }catch(RuntimeException ignored){}
        Notes.tagLatestCall(this,who);
    }

    private void ensureEar(){
        if(ear)return;
        telephony=(TelephonyManager)getSystemService(TELEPHONY_SERVICE);
        if(telephony==null)return;
        if(checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(Build.VERSION.SDK_INT>=31){
            modern=new Ear();
            telephony.registerTelephonyCallback(getMainExecutor(),modern);
        }else{
            legacy=new PhoneStateListener(){public void onCallStateChanged(int state,String ignored){changed(state);}};
            telephony.listen(legacy,PhoneStateListener.LISTEN_CALL_STATE);
        }
        ear=true;
    }

    private void dropEar(){
        if(!ear || telephony==null)return;
        try{if(Build.VERSION.SDK_INT>=31 && modern!=null)telephony.unregisterTelephonyCallback(modern);else if(legacy!=null)telephony.listen(legacy,PhoneStateListener.LISTEN_NONE);}
        catch(RuntimeException ignored){}
        ear=false;
    }

    private final class Ear extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        public void onCallStateChanged(int state){changed(state);}
    }

    private boolean recordingNotice(){return foreground("Recording this call","Saved in Calls after you hang up.",true);}

    private boolean foreground(String title,String text,boolean recording){
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("callwatch","Call recording",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,44,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"callwatch").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true).setDefaults(0).setSound(null).setVibrate(new long[0]).setShowWhen(false).setContentIntent(open);
        if(recording){
            PendingIntent stop=PendingIntent.getService(this,45,new Intent(this,CallWatch.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Finish",stop).build());
        }
        Notification notice=builder.build();
        try{
            if(Build.VERSION.SDK_INT>=34)startForeground(44,notice,recording?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else if(Build.VERSION.SDK_INT>=29 && recording)startForeground(44,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(44,notice);
            return true;
        }catch(RuntimeException error){android.util.Log.w("VonoTation","Android did not allow the call microphone foreground service",error);return false;}
    }
}
