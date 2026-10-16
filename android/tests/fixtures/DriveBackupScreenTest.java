package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.Set;

/** Native disconnected/unsupported-Google-device UI checks; this is not Google sign-in testing. */
public final class DriveBackupScreenTest extends Instrumentation {
    private static final String GOOGLE_UNAVAILABLE="Google Play services must be available and up to date to connect Google Drive";
    private Activity screen;private Context context;private int checks,requests;private String captureWarning="";
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();SharedPreferences prefs=Store.prefs(context);
        Map<String,?> before=prefs.getAll(),beforeSecret=BackupSecrets.snapshot(context);
        try {
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("https") || protocol.equals("http")?new URLStreamHandler(){protected URLConnection openConnection(URL address)throws IOException{requests++;throw new IOException("UI fixture blocks external requests");}}:null);
            DriveBackup.disconnect(context);prefs.edit().remove("backup_frequency").remove("backup_wifi").commit();
            Map<String,?> disconnected=prefs.getAll();
            screen=startActivitySync(new Intent(context,BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
            View connect=findText("Connect Google"),restore=findText("Restore from Google Drive"),save=findText("Back up now");
            Spinner cadence=(Spinner)findDescription("Backup frequency");
            require(connect!=null && connect.isEnabled() && restore!=null && restore.isEnabled(),"Visible Google connect and restore actions are missing");
            require(save!=null && !save.isEnabled() && cadence!=null && !cadence.isEnabled() && cadence.getSelectedItemPosition()==0,"Disconnected backup offers upload/automation or does not start Off");
            require(disconnected.equals(prefs.getAll()) && backupJobMissing(),"Opening backup wrote defaults or scheduled a job");
            capture("drive-backup-disconnected-195.png");
            runOnMainSync(()->connect.performClick());waitForIdleSync();
            long until=android.os.SystemClock.elapsedRealtime()+10000;View unavailable=null;
            while(android.os.SystemClock.elapsedRealtime()<until){unavailable=findText(GOOGLE_UNAVAILABLE);if(unavailable!=null)break;Thread.sleep(100);}
            require(unavailable!=null,"Unsupported AOSP device did not display an actionable Google Play services message");
            require(!screen.isFinishing() && findText("Connect Google").isEnabled() && disconnected.equals(prefs.getAll()) && BackupSecrets.snapshot(context).isEmpty() && backupJobMissing(),"Unavailable Google sign-in crashed, left controls busy, or changed local backup state");
            require(requests==0,"Unsupported Google device sent an external request");
            capture("drive-backup-google-unavailable-195.png");
            report.putString("result","PASS: "+checks+" native Backup screen checks; visible Google connect/restore, Off defaults, disabled upload/automation while disconnected, graceful missing-Play-services feedback, no external requests or configuration mutation. Google account consent remains untested.");
            if(!captureWarning.isEmpty())report.putString("preview_warning",captureWarning);
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally {
            if(screen!=null)runOnMainSync(()->screen.finish());restorePrefs(context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE),beforeSecret);restorePrefs(prefs,before);DriveBackup.schedule(context);
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private View findText(String text){View[] found={null};runOnMainSync(()->found[0]=find(screen.getWindow().getDecorView(),text,null));return found[0];}
    private View findDescription(String description){View[] found={null};runOnMainSync(()->found[0]=find(screen.getWindow().getDecorView(),null,description));return found[0];}
    private View find(View view,String text,String description){if(text!=null && view instanceof TextView){String actual=((TextView)view).getText().toString();if(actual.equals(text) || (GOOGLE_UNAVAILABLE.equals(text) && actual.startsWith(text)))return view;}if(description!=null && description.equals(String.valueOf(view.getContentDescription())))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),text,description);if(found!=null)return found;}}return null;}
    private boolean backupJobMissing(){return ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID)==null;}
    private void require(boolean pass,String message)throws Exception{checks++;if(!pass)throw new Exception(message);}
    private void capture(String name) {
        Throwable[] failed={null};runOnMainSync(()->{try{java.io.File[] media=context.getExternalMediaDirs();if(media==null || media.length==0 || media[0]==null)throw new IOException("Native preview media storage unavailable");View decor=screen.getWindow().getDecorView();Bitmap image=Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);decor.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new java.io.File(media[0],name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}catch(Throwable error){failed[0]=error;}});
        if(failed[0]!=null)captureWarning="Native UI checks passed but preview export failed: "+failed[0].getClass().getSimpleName();
    }
    @SuppressWarnings("unchecked") private static void restorePrefs(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);}edit.commit();}
}
