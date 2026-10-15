package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native backup controls and password/restore confirmation, using only the offline QA provider. */
public final class BackupUiTest extends Instrumentation {
    private Activity screen;
    private Context context;
    private ActivityMonitor createMonitor,openMonitor;
    private int checks;
    private String phase="Opening backup controls";
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart() {
        Bundle report=new Bundle();context=getTargetContext();SharedPreferences prefs=Store.prefs(context);Map<String,?> original=prefs.getAll(),secretState=BackupSecrets.snapshot(context);String originalDraft=MainActivity.currentDraft;
        Uri uri=null;byte[] originalFile=null;boolean hadGrant=false;int acquiredGrantFlags=0;char[] password="  UI fixture backup pass  ".toCharArray();
        try {
            java.net.URL.setURLStreamHandlerFactory(protocol->protocol.equals("https") || protocol.equals("http")?new java.net.URLStreamHandler(){protected java.net.URLConnection openConnection(java.net.URL address)throws java.io.IOException{throw new java.io.IOException("Backup UI fixture blocks external requests");}}:null);
            prefs.edit().remove("backup_uri").remove("backup_name").remove("backup_last_success").remove("backup_error").remove("backup_frequency").remove("backup_wifi").putBoolean("daily_summaries",false).putString("history",new JSONArray().put(new JSONObject().put("id",1700000000000L).put("text","Existing UI fixture words").put("raw","Existing UI fixture words").put("duration",1000L).put("words",4)).toString()).putString("dictionary","old=old").commit();BackupSecrets.clear(context);DriveBackup.schedule(context);
            screen=startActivitySync(new Intent().setClassName("app.voiceflow","app.voiceflow.BackupActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
            Spinner frequency=(Spinner)description("Backup frequency");Switch wifi=(Switch)description("Automatic backup on unmetered networks only");
            check(frequency!=null && wifi!=null,"Visible automatic-backup controls are missing");
            check(frequency.getSelectedItemPosition()==0 && wifi.isChecked(),"New installation did not start with Off and Wi-Fi only");
            check(!prefs.contains("backup_frequency") && !prefs.contains("backup_wifi") && !DriveBackup.automaticEnabled(context),"Opening backup opted into a schedule or wrote defaults");check(noJob(),"Off-by-default backup created a background job");
            runOnMainSync(()->frequency.setSelection(1));waitSetting("backup_frequency","daily");check(noJob(),"An unconfigured backup created a daily job");
            runOnMainSync(()->frequency.setSelection(2));waitSetting("backup_frequency","weekly");runOnMainSync(()->frequency.setSelection(0));waitSetting("backup_frequency","off");
            runOnMainSync(()->wifi.performClick());waitBoolean("backup_wifi",false);runOnMainSync(()->wifi.performClick());waitBoolean("backup_wifi",true);
            String before=dataState();Map<String,?> secretBefore=BackupSecrets.snapshot(context);
            progress("Validating backup password");clickText("Choose Drive backup");waitNode("Protect your backup",10000);clickNode("Continue");waitNode("Use a backup password with at least 8 characters.",10000);
            check(node("Protect your backup")!=null,"Empty password dismissed the validation dialog");check(before.equals(dataState()),"Empty password changed saved data");
            setDialogFields("fixture passphrase","different passphrase");clickNode("Continue");waitNode("The passwords do not match.",10000);check(before.equals(dataState()),"Mismatched password changed saved data");clickNode("Cancel");waitGone("Protect your backup");check(before.equals(dataState()) && secretBefore.equals(BackupSecrets.snapshot(context)),"Cancelling password setup changed data or remembered a password");
            progress("Cancelling create-file picker");createMonitor=addMonitor(pickerFilter(Intent.ACTION_CREATE_DOCUMENT),new ActivityResult(Activity.RESULT_CANCELED,null),true);
            clickText("Choose Drive backup");waitNode("Protect your backup",10000);setDialogFields(new String(password),new String(password));clickNode("Continue");waitMonitor(createMonitor);waitGone("Protect your backup");waitForIdleSync();
            check(before.equals(dataState()) && secretBefore.equals(BackupSecrets.snapshot(context)) && !DriveBackup.configured(context),"Cancelling the create-file picker configured backup or changed data");removeMonitor(createMonitor);createMonitor=null;
            progress("Checking restore confirmation with the offline provider");control("unblock");control("allow-write");control("available");uri=control("grant");
            int originalGrantFlags=0;
            for(android.content.UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(uri.equals(grant.getUri())){if(grant.isReadPermission())originalGrantFlags|=Intent.FLAG_GRANT_READ_URI_PERMISSION;if(grant.isWritePermission())originalGrantFlags|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;}
            hadGrant=originalGrantFlags!=0;int needed=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(uri,needed);acquiredGrantFlags=needed & ~originalGrantFlags;
            boolean persisted=false;for(android.content.UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(uri.equals(grant.getUri()) && grant.isReadPermission() && grant.isWritePermission())persisted=true;check(persisted,"QA provider grant did not persist for the fresh Backup screen");
            originalFile=readBytes(uri);JSONObject archived=BackupData.snapshot(context);archived.getJSONObject("settings").put("dictionary","restored=restored");archived.put("history",new JSONArray().put(new JSONObject().put("id",1700000000010L).put("text","Words from the backup").put("raw","Words from the backup").put("duration",2000L).put("words",4)));
            progress("Preparing encrypted UI restore archive");byte[] encrypted=BackupArchive.encode(archived,password);try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){check(out!=null,"QA provider could not receive the archive");out.write(encrypted);}Arrays.fill(encrypted,(byte)0);progress("Encrypted UI restore archive ready");
            // A persisted QA grant survives deterministic task clearing; the test releases
            // only permissions it acquired, preserving any prior fixture permission.
            progress("Opening fresh Backup screen for restore");if(screen!=null)runOnMainSync(()->screen.finish());screen=startActivitySync(new Intent().setClassName("app.voiceflow","app.voiceflow.BackupActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();progress("Fresh Backup screen ready");
            before=dataState();secretBefore=BackupSecrets.snapshot(context);
            Intent selected=new Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);openMonitor=addMonitor(pickerFilter(Intent.ACTION_OPEN_DOCUMENT),new ActivityResult(Activity.RESULT_OK,selected),true);
            clickText("Restore backup");waitMonitor(openMonitor);waitNode("Unlock backup",10000);check(editableNodes().size()==1,"Restore requested an unnecessary confirmation password");clickNode("Continue");waitNode("Use a backup password with at least 8 characters.",10000);setDialogFields(new String(password));progress("Submitting restore unlock password");clickNode("Continue");waitNode("Restore this backup?",120000);
            check(nodeContaining("Replaces this phone’s current history, dictionary, settings and API connection.")!=null,"Restore did not explain which current data will be replaced");check(before.equals(dataState()),"Unlocking backup applied data before confirmation");clickNode("Keep current data");waitGone("Restore this backup?");check(before.equals(dataState()) && secretBefore.equals(BackupSecrets.snapshot(context)),"Cancelling restore changed current data or password settings");
            check(description("Back to settings")!=null,"Backup screen has no accessible Back control");click(description("Back to settings"));boolean[] finished={false};runOnMainSync(()->finished[0]=screen.isFinishing());check(finished[0],"Back did not close the backup screen");
            report.putString("result","PASS: "+checks+" native backup UI checks; visible frequency/Wi-Fi controls, no automatic default opt-in, preference updates, empty/mismatched password rejection, setup and file-picker cancellation, single restore password, explicit replacement confirmation, cancel keeps current data, and accessible Back (offline QA provider)");
        } catch(Throwable error){report.putString("failure",phase+": "+error.toString());}
        finally {
            Arrays.fill(password,'\0');if(createMonitor!=null)removeMonitor(createMonitor);if(openMonitor!=null)removeMonitor(openMonitor);
            if(screen!=null)runOnMainSync(()->screen.finish());
            if(uri!=null && originalFile!=null)try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){if(out!=null)out.write(originalFile);}catch(Exception ignored){}
            if(uri!=null && acquiredGrantFlags!=0)try{context.getContentResolver().releasePersistableUriPermission(uri,acquiredGrantFlags);}catch(Exception ignored){}
            if(uri!=null && !hadGrant)try{control("revoke");}catch(Exception ignored){}
            restorePrefs(prefs,original);try{BackupSecrets.restore(context,secretState);}catch(Exception ignored){}MainActivity.currentDraft=originalDraft;DriveBackup.schedule(context);
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void progress(String value){phase=value;Bundle status=new Bundle();status.putString("phase",value);sendStatus(0,status);}
    private IntentFilter pickerFilter(String action)throws IntentFilter.MalformedMimeTypeException{IntentFilter filter=new IntentFilter(action);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");return filter;}
    private void check(boolean okay,String message)throws Exception{checks++;if(!okay)throw new Exception(message);}
    private boolean noJob(){JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);return scheduler==null || scheduler.getPendingJob(DriveBackup.JOB_ID)==null;}
    private String dataState()throws Exception{JSONObject state=BackupData.snapshot(context);state.remove("created_at");state.getJSONObject("settings").remove("typing_keyboard");state.put("backup_uri",Store.get(context,"backup_uri",""));state.put("backup_name",Store.get(context,"backup_name",""));return state.toString();}
    private View description(String name){View[] found={null};runOnMainSync(()->found[0]=find(screen.getWindow().getDecorView(),name,null));return found[0];}
    private View find(View view,String description,String text){if(description!=null && view.getContentDescription()!=null && description.contentEquals(view.getContentDescription()))return view;if(text!=null && view instanceof TextView && text.contentEquals(((TextView)view).getText()) && view.isClickable())return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),description,text);if(found!=null)return found;}return null;}
    private void click(View view)throws Exception{check(view!=null,"Expected native button is missing");runOnMainSync(()->view.performClick());waitForIdleSync();}
    private void clickText(String text)throws Exception{View[] found={null};runOnMainSync(()->found[0]=find(screen.getWindow().getDecorView(),null,text));click(found[0]);}
    private AccessibilityNodeInfo node(String text){AccessibilityNodeInfo root=automation().getRootInActiveWindow();return root==null?null:findNode(root,text,false);}
    private AccessibilityNodeInfo nodeContaining(String text){AccessibilityNodeInfo root=automation().getRootInActiveWindow();return root==null?null:findNode(root,text,true);}
    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo current,String text,boolean contains){CharSequence value=current.getText();if(value!=null && (contains?value.toString().contains(text):value.toString().equals(text)))return current;for(int i=0;i<current.getChildCount();i++){AccessibilityNodeInfo child=current.getChild(i);if(child!=null){AccessibilityNodeInfo found=findNode(child,text,contains);if(found!=null)return found;}}return null;}
    private void clickNode(String text)throws Exception{
        long end=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<end){
            AccessibilityNodeInfo root=automation().getRootInActiveWindow(),current=root==null?null:findActionNode(root,text);
            if(current!=null && current.refresh() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)){checks++;waitForIdleSync();return;}
            Thread.sleep(100);
        }
        throw new Exception("Dialog button did not become available: "+text);
    }
    private AccessibilityNodeInfo findActionNode(AccessibilityNodeInfo current,String text){
        CharSequence label=current.getText();
        // Framework dialog buttons can expose their all-caps display text to Accessibility.
        if(label!=null && label.toString().equalsIgnoreCase(text) && current.isEnabled() && current.isClickable() && current.isVisibleToUser())return current;
        for(int i=0;i<current.getChildCount();i++){AccessibilityNodeInfo child=current.getChild(i);if(child!=null){AccessibilityNodeInfo found=findActionNode(child,text);if(found!=null)return found;}}return null;
    }
    private UiAutomation automation(){return getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);}
    private List<AccessibilityNodeInfo> editableNodes(){List<AccessibilityNodeInfo> fields=new ArrayList<>();AccessibilityNodeInfo root=automation().getRootInActiveWindow();if(root!=null)collectEditable(root,fields);return fields;}
    private void collectEditable(AccessibilityNodeInfo current,List<AccessibilityNodeInfo> fields){if(current.isEditable())fields.add(current);for(int i=0;i<current.getChildCount();i++){AccessibilityNodeInfo child=current.getChild(i);if(child!=null)collectEditable(child,fields);}}
    private void setDialogFields(String... values)throws Exception{List<AccessibilityNodeInfo> fields=editableNodes();check(fields.size()==values.length,"Unexpected password field count");for(int i=0;i<values.length;i++){Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,values[i]);check(fields.get(i).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text),"Password field could not be edited");}waitForIdleSync();}
    private void waitNode(String text,long timeout)throws Exception{long end=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<end){if(node(text)!=null){waitForIdleSync();return;}Thread.sleep(100);}throw new Exception("Expected dialog text did not appear: "+text);}
    private void waitGone(String text)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(node(text)==null){waitForIdleSync();return;}Thread.sleep(100);}throw new Exception("Dialog did not close: "+text);}
    private void waitMonitor(ActivityMonitor monitor)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(monitor.getHits()>0){checks++;return;}Thread.sleep(100);}throw new Exception("The Android file picker was not requested");}
    private void waitSetting(String key,String value)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(value.equals(Store.get(context,key,""))){waitForIdleSync();checks++;return;}Thread.sleep(100);}throw new Exception("Backup preference did not update: "+key);}
    private void waitBoolean(String key,boolean value)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){if(Store.prefs(context).contains(key) && Store.bool(context,key,!value)==value){waitForIdleSync();checks++;return;}Thread.sleep(100);}throw new Exception("Backup switch did not update");}
    private Uri control(String command)throws Exception{
        CountDownLatch done=new CountDownLatch(1);Bundle[] result={null};int[] resultCode={Integer.MIN_VALUE};
        ResultReceiver reply=new ResultReceiver(null){protected void onReceiveResult(int code,Bundle data){resultCode[0]=code;result[0]=data;done.countDown();}};
        context.startActivity(new Intent().setClassName("app.voiceflow.fixture","app.voiceflow.fixture.BackupGrantActivity").putExtra("command",command).putExtra("reply",reply).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        check(done.await(30,TimeUnit.SECONDS),"Offline provider control timed out after 30 seconds: "+command);
        String detail=result[0]==null?"empty result":result[0].getString("error","ok flag missing");
        check(resultCode[0]==Activity.RESULT_OK && result[0]!=null && result[0].getBoolean("ok"),"Offline provider control failed: "+command+", code="+resultCode[0]+", "+detail);
        String uri=result[0].getString("uri");check(uri!=null && !uri.isEmpty(),"Offline provider result omitted its URI: "+command);waitForIdleSync();return Uri.parse(uri);
    }
    private byte[] readBytes(Uri uri)throws Exception{try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){check(in!=null,"Offline provider could not open the archive");byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1)out.write(chunk,0,n);return out.toByteArray();}}
    @SuppressWarnings("unchecked")private void restorePrefs(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);}edit.commit();}
}
