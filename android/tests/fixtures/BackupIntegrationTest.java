package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tests production backup against a real granted DocumentsProvider, entirely offline. */
public final class BackupIntegrationTest extends Instrumentation {
    private static final String PASSWORD="fixture backup passphrase";
    private static final String API_KEY="fixture-portable-api-key";
    private static final int GRANT_FLAGS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
    private static final long DAY=24*60*60*1000L;
    private Context context;
    private Uri uri;
    private Thread backupWorker;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle report=new Bundle();context=getTargetContext();SharedPreferences prefs=Store.prefs(context),secrets=context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE);Map<String,?> previous=prefs.getAll(),previousSecrets=secrets.getAll();String oldDraft=MainActivity.currentDraft;Uri fixtureUri=android.provider.DocumentsContract.buildDocumentUri("app.voiceflow.fixture.backups","voiceflow-backup");boolean hadGrant=false;for(android.content.UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(grant.getUri().equals(fixtureUri) && grant.isReadPermission() && grant.isWritePermission())hadGrant=true;try{
        check(!DriveBackup.configured(context) || Store.get(context,"backup_uri","").equals(fixtureUri.toString()),"Run backup QA only on an isolated test installation");
        java.net.URL.setURLStreamHandlerFactory(protocol->protocol.equals("https") || protocol.equals("http")?new java.net.URLStreamHandler(){protected java.net.URLConnection openConnection(java.net.URL address)throws java.io.IOException{throw new java.io.IOException("Backup fixture blocks external API requests");}}:null);
        progress("Granting real provider access");control("reset");uri=control("grant");
        prefs.edit().putString("backup_frequency","off").putBoolean("backup_wifi",true).commit();
        DriveBackup.configure(context,uri,GRANT_FLAGS,PASSWORD.toCharArray());
        check(DriveBackup.configured(context),"Document grant was not configured");
        boolean persisted=false;for(android.content.UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(grant.getUri().equals(uri) && grant.isReadPermission() && grant.isWritePermission())persisted=true;
        check(persisted,"Backup did not persist an actual read/write URI grant");
        prefs.edit().putString("engine","cloud").putString("language","bn-BD").putString("bubble_size","large").putBoolean("typing_visible",true).putBoolean("save_history",true).putBoolean("daily_summaries",false).putBoolean("clean",true).putBoolean("ai_polish",false).putBoolean("bubble",true).putString("dictionary","jewel=Juel").putString("transcribe_model","whisper-large-v3").putString("polish_model","openai/gpt-oss-20b").putString("api_base",CloudApi.DEFAULT_BASE).putString("history","[]").commit();Vault.set(context,API_KEY);Store.save(context,"um backup words","Backup words",1234);
        JSONObject snapshot=BackupData.snapshot(context);String portable=snapshot.toString();
        check(!portable.contains(uri.toString()) && !portable.contains("backup_uri") && !portable.contains("api_secret") && !portable.contains("api_iv") && !portable.contains(PASSWORD),"Archive exported a URI or device-bound secret capability");
        progress("Writing and restoring encrypted backup");DriveBackup.backup(context);byte[] archive=readBytes();
        check(archive.length>100,"Provider did not receive an encrypted backup");String ciphertext=new String(archive,"UTF-8");check(!ciphertext.contains("Backup words") && !ciphertext.contains(API_KEY) && !ciphertext.contains("jewel=Juel"),"Backup file contains plain personal data");
        check(prefs.getLong("backup_last_success",0)>0,"Successful backup timestamp not saved");
        JSONObject restored=DriveBackup.read(context,uri,PASSWORD.toCharArray());
        prefs.edit().putString("engine","system").putString("language","en-US").putString("bubble_size","small").putBoolean("typing_visible",false).putString("dictionary","changed=changed").putString("history","[]").putString("transcribe_model","whisper-large-v3-turbo").putString("polish_model","openai/gpt-oss-120b").putBoolean("clean",false).putBoolean("ai_polish",true).putBoolean("bubble",false).putBoolean("save_history",false).putBoolean("daily_summaries",true).commit();Vault.set(context,"fixture-different-key");DriveBackup.restore(context,restored);
        check(Store.get(context,"engine","").equals("cloud") && Store.get(context,"language","").equals("bn-BD") && Store.get(context,"bubble_size","").equals("large") && Store.bool(context,"typing_visible",false),"Portable settings were not restored");
        check(Store.get(context,"dictionary","").equals("jewel=Juel") && Vault.get(context).equals(API_KEY),"Dictionary or API key was not restored portably");
        check(Store.get(context,"transcribe_model","").equals("whisper-large-v3") && Store.get(context,"polish_model","").equals("openai/gpt-oss-20b") && Store.bool(context,"clean",false) && !Store.bool(context,"ai_polish",true) && Store.bool(context,"bubble",false) && Store.bool(context,"save_history",false) && !Store.bool(context,"daily_summaries",true),"Model, cleanup, control or history switches were not restored");
        JSONArray history=Store.history(context);check(history.length()==1 && history.getJSONObject(0).getString("text").equals("Backup words") && history.getJSONObject(0).getString("raw").equals("um backup words"),"History round trip changed text or raw transcript");
        check(DriveBackup.configured(context) && Store.get(context,"backup_uri","").equals(uri.toString()),"Restore overwrote the local backup connection");
        progress("Checking serialized backup and interrupted writes");
        String safeState=userData(),selected=Store.get(context,"backup_uri","");
        expectFailure(()->DriveBackup.configure(context,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION,"different fixture password".toCharArray()),"Read-only configuration was accepted");
        expectFailure(()->DriveBackup.configure(context,Uri.parse("file:///invalid.vfbackup"),GRANT_FLAGS,"different fixture password".toCharArray()),"Non-content configuration was accepted");
        expectFailure(()->DriveBackup.configure(context,uri,GRANT_FLAGS,"short".toCharArray()),"Short backup password was accepted");
        char[] remembered=BackupSecrets.get(context);try{check(Arrays.equals(PASSWORD.toCharArray(),remembered),"Failed configuration changed the backup password");}finally{if(remembered!=null)Arrays.fill(remembered,'\0');}
        check(selected.equals(Store.get(context,"backup_uri","")) && safeState.equals(userData()) && DriveBackup.configured(context),"Failed configuration changed data or lost the previous grant");
        control("block-read");java.util.concurrent.atomic.AtomicReference<Throwable> busyFailure=new java.util.concurrent.atomic.AtomicReference<>();Thread busyWorker=new Thread(()->{try{DriveBackup.backup(context);}catch(Throwable failure){busyFailure.set(failure);}},"Backup-fixture-busy");backupWorker=busyWorker;busyWorker.start();waitProvider("read_entered",busyWorker,busyFailure);
        long started=android.os.SystemClock.elapsedRealtime();expectFailure(()->DriveBackup.configure(context,uri,GRANT_FLAGS,"different fixture password".toCharArray()),"Configure ran while backup was active");expectFailure(()->DriveBackup.disconnect(context),"Disconnect ran while backup was active");expectFailure(()->DriveBackup.restore(context,restored),"Restore ran while backup was active");check(android.os.SystemClock.elapsedRealtime()-started<2000,"Busy requests blocked instead of reporting the active operation");control("unblock");busyWorker.join(15000);check(!busyWorker.isAlive() && busyFailure.get()==null,"Serialized backup did not complete after provider release");archive=readBytes();
        long previousSuccess=prefs.getLong("backup_last_success",0);control("block-write");java.util.concurrent.atomic.AtomicReference<Throwable> interruptedFailure=new java.util.concurrent.atomic.AtomicReference<>();Thread interruptedWorker=new Thread(()->{try{DriveBackup.backup(context);}catch(Throwable failure){interruptedFailure.set(failure);}},"Backup-fixture-cancel");backupWorker=interruptedWorker;interruptedWorker.start();waitProvider("write_entered",interruptedWorker,interruptedFailure);interruptedWorker.interrupt();control("unblock");interruptedWorker.join(15000);check(!interruptedWorker.isAlive() && interruptedFailure.get()!=null,"Interrupted backup was reported as successful");check(Arrays.equals(archive,readBytes()) && prefs.getLong("backup_last_success",0)==previousSuccess && safeState.equals(userData()),"Interrupted write did not recover previous bytes or changed user data");
        progress("Checking schedules and rejected backups");
        prefs.edit().putString("backup_frequency","daily").putBoolean("backup_wifi",true).commit();DriveBackup.schedule(context);JobInfo job=backupJob();check(job!=null && job.isPeriodic() && job.getIntervalMillis()==DAY && job.getNetworkType()==JobInfo.NETWORK_TYPE_UNMETERED,"Daily Wi-Fi backup job has incorrect constraints");
        prefs.edit().putString("backup_frequency","weekly").putBoolean("backup_wifi",false).commit();DriveBackup.schedule(context);job=backupJob();check(job!=null && job.getIntervalMillis()==7*DAY && job.getNetworkType()==JobInfo.NETWORK_TYPE_ANY,"Weekly backup job has incorrect constraints");
        prefs.edit().putString("backup_frequency","off").commit();DriveBackup.schedule(context);check(backupJob()==null,"Off frequency left an automated backup job active");
        String before=userData();long lastSuccess=prefs.getLong("backup_last_success",0);control("fail-write");expectFailure(()->DriveBackup.backup(context),"Refused provider write was reported as successful");check(Arrays.equals(archive,readBytes()),"Failed write replaced the previous backup");check(prefs.getLong("backup_last_success",0)==lastSuccess && before.equals(userData()),"Failed backup changed saved data or last-success time");control("allow-write");
        expectFailure(()->{JSONObject data=DriveBackup.read(context,uri,"incorrect password".toCharArray());DriveBackup.restore(context,data);},"Wrong backup password was accepted");check(before.equals(userData()),"Wrong password mutated settings/history/API key");
        control("tamper");expectFailure(()->{JSONObject data=DriveBackup.read(context,uri,PASSWORD.toCharArray());DriveBackup.restore(context,data);},"Tampered backup was accepted");check(before.equals(userData()),"Tampered archive mutated settings/history/API key");
        control("unavailable");expectFailure(()->DriveBackup.backup(context),"Missing provider document was reported as successful");check(before.equals(userData()),"Missing document changed user data");control("available");
        control("revoke");expectFailure(()->DriveBackup.backup(context),"Revoked provider access was reported as successful");check(before.equals(userData()),"Revoked permission changed user data");
        DriveBackup.disconnect(context);check(!DriveBackup.configured(context) && backupJob()==null,"Disconnect retained a backup capability or schedule");char[] forgotten=BackupSecrets.get(context);try{check(forgotten==null || forgotten.length==0,"Disconnect retained the saved backup password");}finally{if(forgotten!=null)Arrays.fill(forgotten,'\0');}
        java.io.File[] cached=context.getCacheDir().listFiles();if(cached!=null)for(java.io.File file:cached)check(!file.getName().startsWith("voiceflow-backup-"),"Backup left a staging file after completion or failure");
        report.putString("result","PASS: actual persistable document grants, encrypted provider write/read, settings/history/API-key restore, configuration rollback, busy-operation exclusion, interrupted-write recovery, daily/weekly/off network constraints, failed-write preservation, wrong password/tamper rejection, missing-document/revoked-grant safety, staging cleanup and disconnect (offline test provider; not a Google Drive sync test)");
    }catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));}finally{
        try{control("unblock");if(backupWorker!=null && backupWorker.isAlive()){backupWorker.interrupt();backupWorker.join(15000);}control("allow-write");control("available");control("revoke");if(hadGrant){Uri restoredGrant=control("grant");context.getContentResolver().takePersistableUriPermission(restoredGrant,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}}catch(Exception ignored){}
        restorePrefs(secrets,previousSecrets);restorePrefs(prefs,previous);MainActivity.currentDraft=oldDraft;try{DriveBackup.schedule(context);}catch(Exception ignored){}
    }finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);}
    private Uri control(String command)throws Exception{return Uri.parse(controlResult(command).getString("uri"));}
    private Bundle controlResult(String command)throws Exception{CountDownLatch done=new CountDownLatch(1);Bundle[] response={null};ResultReceiver reply=new ResultReceiver(null){protected void onReceiveResult(int code,Bundle result){response[0]=result;done.countDown();}};context.startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.BackupGrantActivity").putExtra("command",command).putExtra("reply",reply).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));check(done.await(30,TimeUnit.SECONDS),"Provider control did not complete: "+command);check(response[0]!=null && response[0].getBoolean("ok"),"Provider control failed: "+(response[0]==null?command:response[0].getString("error")));waitForIdleSync();return response[0];}
    private void waitProvider(String field,Thread worker,java.util.concurrent.atomic.AtomicReference<Throwable> failure)throws Exception{long started=android.os.SystemClock.elapsedRealtime(),deadline=started+120000;while(android.os.SystemClock.elapsedRealtime()<deadline){Throwable problem=failure.get();if(problem!=null)throw new Exception("Backup worker failed before reaching provider "+field,problem);if(providerEntered(field)){progress("Reached provider "+field+" after "+(android.os.SystemClock.elapsedRealtime()-started)+" ms");return;}if(!worker.isAlive())throw new Exception("Backup worker completed without reaching provider latch: "+field);Thread.sleep(100);}throw new Exception("Backup did not reach provider "+field+" within 120 seconds; worker="+worker.getState()+", stack="+Arrays.toString(worker.getStackTrace()));}
    private boolean providerEntered(String field)throws Exception{String column=field.equals("read_entered")?"fixture_read_entered":"fixture_write_entered";try(android.database.Cursor cursor=context.getContentResolver().query(uri,new String[]{column},null,null,null)){check(cursor!=null && cursor.moveToFirst(),"Provider status cursor missing");return cursor.getInt(0)!=0;}}
    private byte[] readBytes()throws Exception{try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){check(in!=null,"Provider input stream missing");byte[] buffer=new byte[4096];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();}}
    private JobInfo backupJob(){JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);for(JobInfo job:scheduler.getAllPendingJobs())if(job.getService().getClassName().toLowerCase(java.util.Locale.ROOT).contains("backup"))return job;return null;}
    private String userData()throws Exception{JSONObject data=BackupData.snapshot(context);data.remove("created_at");return data.toString();}
    private void progress(String value){Bundle state=new Bundle();state.putString("phase",value);sendStatus(0,state);}
    private void expectFailure(Attempt attempt,String message)throws Exception{boolean failed=false;try{attempt.run();}catch(Exception expected){failed=true;}check(failed,message);}
    private interface Attempt{void run()throws Exception;}
    private void check(boolean value,String message)throws Exception{if(!value)throw new Exception(message);}
    @SuppressWarnings("unchecked") private static void restorePrefs(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor editor=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);}editor.commit();}
}
