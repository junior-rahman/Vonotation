package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual production Activities, physical first taps and explicitly synthetic local data. */
public final class DaybookUiTest extends Instrumentation {
    private static final String NEW_TEXT="Synthetic QA: planned a quiet afternoon and a short walk.";
    private static final String OLD_TEXT="Synthetic QA: checked today's notes and the shopping list.";
    private static final String YESTERDAY_TEXT="Synthetic QA: finished a small project and saved the next steps.";
    private static final String GOOGLE_UNAVAILABLE="Google Play services must be available and up to date to connect Google Drive";
    private static final String HAPTICS="haptic_feedback";
    private volatile MainActivity main;
    private volatile BackupActivity backup;
    private Context context;
    private UiAutomation automation;
    private SharedPreferences prefs;
    private final AtomicInteger externalRequests=new AtomicInteger();
    private int checks,physicalTaps;
    private String phase="starting",googleStage="Not exercised";
    private long newer,older,yesterday;
    private String todayKey,yesterdayKey;
    private ClipData previousClipboard;
    private boolean clipboardSaved;

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){
        if(activity instanceof MainActivity)main=(MainActivity)activity;
        if(activity instanceof BackupActivity)backup=(BackupActivity)activity;
        super.callActivityOnCreate(activity,state);
    }
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();prefs=Store.prefs(context);
        Map<String,?> originalPreferences=prefs.getAll(),originalBackupSecrets=BackupSecrets.snapshot(context);
        String originalDraft=MainActivity.currentDraft;
        try {
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("https") || protocol.equals("http") ? new URLStreamHandler(){
                @Override protected URLConnection openConnection(URL address)throws IOException{
                    externalRequests.incrementAndGet();throw new IOException("Synthetic Daybook fixture blocks external requests");
                }
            } : null);
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            android.accessibilityservice.AccessibilityServiceInfo accessibility=automation.getServiceInfo();
            accessibility.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(accessibility);
            seedSyntheticData();
            setInTouchMode(true);
            main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();
            ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            previousClipboard=clipboard.getPrimaryClip();clipboardSaved=true;

            historyChecks();
            settingsChecks();
            backupChecks();
            deletionChecks();
            hapticsArchiveChecks();
            require(externalRequests.get()==0,"Native Daybook fixture made an external request");
            report.putInt("executed_checks",checks);
            report.putInt("physical_taps",physicalTaps);
            report.putString("google_stage",googleStage);
            report.putString("capture_directory",mediaDirectory().getAbsolutePath());
            report.putString("result","PASS: "+checks+" freshly executed native Daybook checks; "+physicalTaps+" physical taps. Single first taps on every direct Settings row, actual return focus, descending days and entries, inline expansion and native recreation, Copy/Delete/Keep/Clear, saved model/size/language/haptics/dictionary choices, actual disconnected Backup guards, and password-encrypted haptic preference portability. Screenshots are actual installed production views with synthetic QA data. "+googleStage+". No live Google consent, upload, remote restore or physical haptic-motor test.");
        }catch(Throwable failure){
            report.putString("failure",android.util.Log.getStackTraceString(failure));
            report.putString("phase",phase);report.putInt("executed_checks",checks);report.putInt("physical_taps",physicalTaps);
            report.putString("google_stage",googleStage);report.putString("native_control_tree",diagnostics());
        }finally{
            try{
                if(backup!=null && !backup.isDestroyed())runOnMainSync(()->backup.finish());
                if(main!=null && !main.isDestroyed())runOnMainSync(()->main.finish());
                waitForIdleSync();
            }catch(Throwable cleanup){report.putString("activity_cleanup_failure",cleanup.getClass().getSimpleName());report.putString("failure",report.getString("failure","")+"\nActivity cleanup failed");}
            try{
                restorePreferences(context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE),originalBackupSecrets);
                restorePreferences(prefs,originalPreferences);DraftState.set(originalDraft);DriveBackup.schedule(context);
                if(!originalPreferences.equals(prefs.getAll()) || !originalBackupSecrets.equals(BackupSecrets.snapshot(context)) || !java.util.Objects.equals(originalDraft,MainActivity.currentDraft))throw new IOException("Synthetic fixture did not restore its original local data");
            }catch(Throwable cleanup){report.putString("local_data_cleanup_failure",cleanup.getClass().getSimpleName());report.putString("failure",report.getString("failure","")+"\nLocal data cleanup failed");}
            try{if(clipboardSaved){ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);if(previousClipboard!=null)clipboard.setPrimaryClip(previousClipboard);else if(android.os.Build.VERSION.SDK_INT>=28)clipboard.clearPrimaryClip();}}
            catch(Throwable cleanup){report.putString("clipboard_cleanup_failure",cleanup.getClass().getSimpleName());report.putString("failure",report.getString("failure","")+"\nClipboard cleanup failed");}
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void seedSyntheticData()throws Exception{
        progress("Seed synthetic QA history and cached recaps; automatic backup and external calls off");
        DriveBackup.disconnect(context);
        Calendar time=Calendar.getInstance();time.set(Calendar.HOUR_OF_DAY,9);time.set(Calendar.MINUTE,10);time.set(Calendar.SECOND,0);time.set(Calendar.MILLISECOND,0);older=time.getTimeInMillis();
        time.set(Calendar.HOUR_OF_DAY,15);time.set(Calendar.MINUTE,20);newer=time.getTimeInMillis();
        time.add(Calendar.DAY_OF_YEAR,-1);time.set(Calendar.HOUR_OF_DAY,18);yesterday=time.getTimeInMillis();
        todayKey=HistoryDays.key(newer,TimeZone.getDefault());yesterdayKey=HistoryDays.key(yesterday,TimeZone.getDefault());
        // Deliberately unsorted: the native timeline, rather than fixture ordering, must sort it.
        JSONArray history=new JSONArray().put(item(older,OLD_TEXT)).put(item(yesterday,YESTERDAY_TEXT)).put(item(newer,NEW_TEXT));
        require(prefs.edit().clear().putString("history",history.toString()).putString("engine","offline")
                .putBoolean("daily_summaries",true).putBoolean("bubble",false).putString("backup_frequency","off")
                .putString("bubble_size","small").putString("language","en-US").putString("dictionary","")
                .putBoolean(HAPTICS,true).putString("polish_model",CloudApi.DEFAULT_POLISH)
                .putString("transcribe_model",CloudApi.DEFAULT_TRANSCRIBE).putString("api_base",CloudApi.DEFAULT_BASE).commit(),"Could not save synthetic QA fixture state");
        List<HistoryDays.Entry> entries=new ArrayList<>();entries.add(new HistoryDays.Entry(older,OLD_TEXT,Cleaner.words(OLD_TEXT)));entries.add(new HistoryDays.Entry(yesterday,YESTERDAY_TEXT,Cleaner.words(YESTERDAY_TEXT)));entries.add(new HistoryDays.Entry(newer,NEW_TEXT,Cleaner.words(NEW_TEXT)));
        for(HistoryDays.Day day:HistoryDays.group(entries,TimeZone.getDefault())){
            String recap=day.key.equals(todayKey)?"Synthetic QA recap: plans, a short walk and everyday notes.":"Synthetic QA recap: a small project and its next steps.";
            DailySummaryCache.put(context,day,CloudApi.DEFAULT_POLISH,CloudApi.DEFAULT_BASE,recap);
            require(recap.equals(DailySummaryCache.get(context,day,CloudApi.DEFAULT_POLISH,CloudApi.DEFAULT_BASE)),"Synthetic recap cache did not match its exact day/model/endpoint/timezone fingerprint before launch");
        }
        require(Store.bool(context,"daily_summaries",false) && Store.get(context,"engine","").equals("offline") && Vault.get(context).isEmpty(),"Synthetic fixture must display recaps while offline with no provider credential");
        DraftState.set(NEW_TEXT);
    }
    private JSONObject item(long id,String text)throws Exception{return new JSONObject().put("id",id).put("text",text).put("raw",text).put("duration",1200).put("words",Cleaner.words(text));}

    private void historyChecks()throws Exception{
        progress("Actual native Home: newest-first days and entries, top navigation, screenshot");
        waitDescription(main,"Settings");
        require(description(main,"Hide timeline for "+todayKey)!=null,"Today's native timeline is not open by default");
        require(description(main,"Show timeline for "+yesterdayKey)!=null,"Yesterday's native timeline is not collapsed by default");
        assertTreeOrder("Hide timeline for "+todayKey,"Show timeline for "+yesterdayKey,"Native days are not newest first");
        assertTreeOrder("Open dictation "+newer,"Open dictation "+older,"Native entries are not newest first");
        require(text(main,"Your days")!=null,"Daybook Home title is missing");
        require(text(main,"Synthetic QA recap: plans, a short walk and everyday notes.")!=null,"Home did not show its synthetic cached daily recap");
        require(!hasBottomNavigation(),"Obsolete Home/Settings bottom navigation remains");
        View gear=description(main,"Settings");require(hasMinimumTouchTarget(gear),"Top Settings target is smaller than the selected compact 44dp target");
        capture("daybook-home-199-rebuilt.png");

        progress("Native entry/day expansion persists through real Activity recreation");
        singleTapDescription(main,"Open dictation "+newer);
        waitDescription(main,"Close dictation "+newer);
        require(visibleDescription(main,"Copy dictation "+newer) && visibleDescription(main,"Delete dictation "+newer),"Expanding an entry did not expose actual Copy/Delete controls");
        singleTapDescription(main,"Show timeline for "+yesterdayKey);waitDescription(main,"Hide timeline for "+yesterdayKey);
        recreateMain();
        require(visibleDescription(main,"Close dictation "+newer) && visibleDescription(main,"Hide timeline for "+yesterdayKey),"Native recreation lost entry/day expansion state");
        singleTapDescription(main,"Hide timeline for "+todayKey);waitDescription(main,"Show timeline for "+todayKey);
        recreateMain();
        require(visibleDescription(main,"Show timeline for "+todayKey),"Collapsed Today did not persist through native recreation");
        singleTapDescription(main,"Show timeline for "+todayKey);waitDescription(main,"Hide timeline for "+todayKey);
    }

    private void settingsChecks()throws Exception{
        progress("Settings: each direct row must open on ONE first physical tap");
        singleTapDescription(main,"Settings");waitDescription(main,"Back to Home");
        require(hasMinimumTouchTarget(description(main,"Back to Home")),"Settings Back target is smaller than the selected compact 44dp target");
        String[] rows={"Transcription","AI cleanup","Language","Bubble size","Personal dictionary","Google Drive backup","Permissions","Advanced · Privacy & connection"};
        for(String row:rows)require(description(main,row)!=null,"Direct Settings row is missing: "+row);
        Switch haptics=(Switch)description(main,"Haptic feedback");require(haptics!=null && checked(haptics),"Haptic feedback should start On");
        capture("daybook-settings-199-rebuilt.png");

        openFirstTap("Transcription","Transcription");
        require(text(main,"SPEECH MODE")!=null && text(main,"TRANSCRIPT MODEL")!=null,"Transcription panel omits mode or model controls");
        singleTapDescription(main,"Cloud");waitPreference("engine","cloud");
        singleTapDescription(main,"Turbo");waitPreference("transcribe_model","whisper-large-v3-turbo");
        singleTapDescription(main,"Large v3");waitPreference("transcribe_model","whisper-large-v3");
        singleTapDescription(main,"On-device");waitPreference("engine","offline");
        backAndAssertFocus("Transcription");

        openFirstTap("AI cleanup","AI cleanup");
        require(description(main,"Basic cleanup") instanceof Switch && description(main,"AI cleanup") instanceof Switch,"Cleanup panel lost its native switches");
        require(text(main,"CLEANUP MODEL")!=null,"Cleanup model selection is missing");
        singleTapDescription(main,"Versatile");waitPreference("polish_model","llama-3.3-70b-versatile");
        singleTapDescription(main,"Instant");waitPreference("polish_model","llama-3.1-8b-instant");
        // Return through the real model control to the synthetic cache's original model.
        // Cached recaps must never be reused after silently changing their model scope.
        singleTapDescription(main,defaultCleanupLabel());waitPreference("polish_model",CloudApi.DEFAULT_POLISH);
        backAndAssertFocus("AI cleanup");

        openFirstTap("Language","Language");
        for(String removed:new String[]{"বাংলা","नेपाली","English","हिन्दी","Español","Auto-detect"})require(description(main,removed)==null,"Manual language choice remains: "+removed);
        singleTapDescription(main,"Write in English letters");waitBoolean("romanize",true);
        recreateMain();require(checked((Switch)description(main,"Write in English letters")),"Recreation lost the transliteration switch");
        capture("language-output-1.9.13-native.png");
        singleTapDescription(main,"Write in English letters");waitBoolean("romanize",false);
        backAndAssertFocus("Language");
        openFirstTap("Bubble size","Bubble size");singleTapDescription(main,"Medium");
        waitPreference("bubble_size","medium");waitDescription(main,"Back to Home");assertReturnFocus("Bubble size");
        singleTapDescription(main,"Haptic feedback");waitBoolean(HAPTICS,false);
        require(!checked((Switch)description(main,"Haptic feedback")),"Haptic switch did not show its saved Off state");
        recreateMain();
        require(description(main,"Back to Home")!=null,"Recreation lost Settings navigation");
        require(!checked((Switch)description(main,"Haptic feedback")) && Store.get(context,"bubble_size","").equals("medium") && !Store.bool(context,"romanize",false),"Native recreation lost saved haptics, size or output choices");

        openFirstTap("Personal dictionary","Personal dictionary");
        typePhysically("Heard spelling","qaspelling");typePhysically("Preferred spelling","Qaword");
        singleTapDescription(main,"Add word");waitPreference("dictionary","qaspelling=Qaword");
        require(visibleDescription(main,"Remove Qaword"),"Added native dictionary row is missing");
        require(fieldText("Heard spelling").isEmpty() && fieldText("Preferred spelling").isEmpty(),"Dictionary Add did not clear its entry fields");
        singleTapDescription(main,"Remove Qaword");waitPreference("dictionary","");
        require(description(main,"Remove Qaword")==null,"Removed dictionary row remains in the panel");
        backAndAssertFocus("Personal dictionary");

        openFirstTap("Permissions","Permissions");
        require(description(main,"Microphone")!=null && description(main,"Floating microphone")!=null && description(main,"VoiceFlow keyboard")!=null,"Interactive permission rows missing");
        String engineBefore=Store.get(context,"engine","system");
        boolean bubbleBefore=Store.bool(context,"bubble",true);
        recreateMain();
        require(text(main,"Permissions")!=null && description(main,"Microphone")!=null,"Permission panel lost on recreation");
        require(engineBefore.equals(Store.get(context,"engine","system")) && bubbleBefore==Store.bool(context,"bubble",true),"Permission refresh changed settings");
        backAndAssertFocus("Permissions");

        openFirstTap("Advanced · Privacy & connection","Privacy & connection");
        require(text(main,"Clear history")!=null,"Advanced privacy panel lacks actual Clear history");
        progress("Actual Show daily recaps Off hides cards; On restores valid offline cached recaps");
        singleTapDescription(main,"Show daily recaps");waitBoolean("daily_summaries",false);
        backAndAssertFocus("Advanced · Privacy & connection");
        singleTapDescription(main,"Back to Home");waitDescription(main,"Settings");
        require(text(main,"✧  Daily recap")==null && text(main,"Synthetic QA recap: plans, a short walk and everyday notes.")==null,"Native Show daily recaps Off left its recap card visible");
        require(Store.history(context).length()==3,"Hiding recaps removed saved dictations");
        singleTapDescription(main,"Settings");waitDescription(main,"Back to Home");
        openFirstTap("Advanced · Privacy & connection","Privacy & connection");
        singleTapDescription(main,"Show daily recaps");waitBoolean("daily_summaries",true);
        backAndAssertFocus("Advanced · Privacy & connection");
        singleTapDescription(main,"Back to Home");waitDescription(main,"Settings");
        require(text(main,"✧  Daily recap")!=null && text(main,"Synthetic QA recap: plans, a short walk and everyday notes.")!=null,"Native Show daily recaps On did not restore the valid cached recap");
        require(externalRequests.get()==0 && Store.get(context,"engine","").equals("offline") && Vault.get(context).isEmpty(),"Offline recap visibility toggling attempted requests or changed provider state");
        singleTapDescription(main,"Settings");waitDescription(main,"Back to Home");
    }

    private void backupChecks()throws Exception{
        progress("Actual Backup screen: first tap navigation, disconnected Off and upload guards");
        View row=description(main,"Google Drive backup");require(row!=null && !touchModeFocusable(row),"Backup row retains first-tap-stealing touch focusability");
        singleTap(row);
        long until=SystemClock.elapsedRealtime()+10000;while(backup==null && SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        require(backup!=null && !backup.isFinishing(),"ONE first Backup tap did not open the native Backup Activity");
        waitForIdleSync();waitDescription(backup,"Back to settings");
        Map<String,?> disconnected=prefs.getAll();
        View connect=text(backup,"Connect Google"),restore=text(backup,"Restore from Google Drive"),upload=text(backup,"Back up now");
        Spinner frequency=(Spinner)description(backup,"Backup frequency");
        require(connect!=null && restore!=null && enabled(connect) && enabled(restore),"Actual disconnected Connect/Restore controls are missing or disabled");
        require(upload!=null && !enabled(upload),"Disconnected backup enables upload");
        require(frequency!=null && !enabled(frequency) && selection(frequency)==0,"Disconnected automatic backup is not disabled and Off");
        require(disconnected.equals(prefs.getAll()) && noBackupJob(),"Opening native Backup changed settings or scheduled automatic backup");
        capture("daybook-backup-199-rebuilt.png");
        if(!packageInstalled("com.google.android.gms")){
            progress("Physical Connect/Restore on unsupported AOSP: graceful Google-services failure, no external calls");
            singleTapText(backup,"Connect Google");waitText(backup,GOOGLE_UNAVAILABLE);
            require(!backup.isFinishing() && enabled(text(backup,"Connect Google")),"Missing Google services crashed Backup or left Connect busy");
            require(disconnected.equals(prefs.getAll()) && BackupSecrets.snapshot(context).isEmpty() && noBackupJob(),"Blocked Google Connect changed local backup configuration or secrets");
            capture("daybook-google-unavailable-199-rebuilt.png");
            singleTapText(backup,"Restore from Google Drive");waitText(backup,GOOGLE_UNAVAILABLE);
            require(enabled(text(backup,"Restore from Google Drive")) && disconnected.equals(prefs.getAll()) && noBackupJob(),"Blocked Google Restore changed local data, scheduled a job or left controls busy");
            require(externalRequests.get()==0,"Unsupported Google controls attempted external HTTP/HTTPS");
            googleStage="BLOCKED live Google authorization/upload/restore: emulator has no Google Play services; actual native Connect/Restore failure guards passed";
        }else{
            googleStage="SKIPPED live Google authorization/upload/restore: this synthetic local UI fixture deliberately does not initiate account consent";
        }
        singleTapDescription(backup,"Back to settings");
        until=SystemClock.elapsedRealtime()+10000;while(!backup.isFinishing() && !backup.isDestroyed() && SystemClock.elapsedRealtime()<until)Thread.sleep(100);
        waitForIdleSync();waitDescription(main,"Back to Home");assertReturnFocus("Google Drive backup");backup=null;
    }

    private void deletionChecks()throws Exception{
        progress("Actual inline Delete removes matching draft from new backup snapshots");
        singleTapDescription(main,"Back to Home");waitDescription(main,"Settings");
        if(description(main,"Close dictation "+newer)==null)singleTapDescription(main,"Open dictation "+newer);
        singleTapDescription(main,"Copy dictation "+newer);
        ClipData copied=((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
        require(copied!=null && copied.getItemCount()==1 && NEW_TEXT.contentEquals(copied.getItemAt(0).coerceToText(context)),"Actual native Copy did not copy the selected synthetic entry");
        singleTapDescription(main,"Delete dictation "+newer);
        require(!historyHas(newer) && Store.history(context).length()==2,"Actual native Delete did not remove only the selected entry");
        JSONObject deletedSnapshot=BackupData.snapshot(context);
        require(!deletedSnapshot.toString().contains(NEW_TEXT) && MainActivity.currentDraft.isEmpty(),"Deleted synthetic text remained in a newly created backup through its draft");
        if(description(main,"Hide timeline for "+yesterdayKey)==null)singleTapDescription(main,"Show timeline for "+yesterdayKey);
        require(description(main,"Open dictation "+yesterday)!=null,"Deleting Today also removed another day's history");

        progress("Actual Clear confirmation: Keep preserves data; Clear removes matching draft and saved history");
        DraftState.set(OLD_TEXT);
        singleTapDescription(main,"Settings");waitDescription(main,"Back to Home");
        openFirstTap("Advanced · Privacy & connection","Privacy & connection");
        singleTapText(main,"Clear history");waitDialogText("Clear saved history?");
        int keptCount=Store.history(context).length();singleTapDialogButton("Keep");waitDialogGone("Clear saved history?");
        require(Store.history(context).length()==keptCount && OLD_TEXT.equals(MainActivity.currentDraft),"Keep confirmation removed history or draft");
        singleTapText(main,"Clear history");waitDialogText("Clear saved history?");singleTapDialogButton("Clear");waitDialogGone("Clear saved history?");
        require(Store.history(context).length()==0 && MainActivity.currentDraft.isEmpty(),"Confirmed Clear did not remove saved history and matching draft");
        JSONObject clearedSnapshot=BackupData.snapshot(context);
        require(clearedSnapshot.getJSONArray("history").length()==0 && !clearedSnapshot.toString().contains(OLD_TEXT) && !clearedSnapshot.toString().contains(YESTERDAY_TEXT),"Cleared synthetic history remained in a new backup");
    }

    private void hapticsArchiveChecks()throws Exception{
        progress("Production AES-GCM archive: portable haptic Off, legacy true default, reject malformed before mutation");
        require(!Store.bool(context,HAPTICS,true),"Native Haptic Off preference was lost before backup");
        JSONObject portable=BackupData.snapshot(context);
        require(portable.getJSONObject("settings").has(HAPTICS) && !portable.getJSONObject("settings").getBoolean(HAPTICS),"Backup snapshot omitted the portable Haptic Off preference");
        char[] password="synthetic-daybook-backup".toCharArray();byte[] archive=null;
        try{
            archive=BackupArchive.encode(portable,password);
            require(!new String(archive,java.nio.charset.StandardCharsets.ISO_8859_1).contains(HAPTICS),"Password-encrypted archive exposes the portable haptic setting as plaintext");
            JSONObject decoded=BackupData.validate(BackupArchive.decode(archive,password));
            require(!decoded.getJSONObject("settings").getBoolean(HAPTICS),"Actual encrypted archive decode lost Haptic Off");
            prefs.edit().putBoolean(HAPTICS,true).commit();BackupData.restore(context,decoded);
            require(!Store.bool(context,HAPTICS,true),"Local restore did not restore the encrypted Haptic Off preference");
            JSONObject legacy=new JSONObject(portable.toString());legacy.getJSONObject("settings").remove(HAPTICS);
            JSONObject validatedLegacy=BackupData.validate(legacy);
            require(validatedLegacy.getJSONObject("settings").getBoolean(HAPTICS),"Schema 1 archive without haptics did not default to true");
            BackupData.restore(context,legacy);require(Store.bool(context,HAPTICS,false),"Legacy local restore did not establish Haptic On default");
            Map<String,?> beforeRejected=prefs.getAll();String beforeDraft=MainActivity.currentDraft;
            JSONObject invalid=new JSONObject(portable.toString());invalid.getJSONObject("settings").put(HAPTICS,"false");
            boolean rejected=false;try{BackupData.restore(context,invalid);}catch(IOException expected){rejected=true;}
            require(rejected,"Malformed string-valued haptic setting was accepted");
            require(beforeRejected.equals(prefs.getAll()) && beforeDraft.equals(MainActivity.currentDraft),"Malformed haptic restore mutated local data before rejection");
        }finally{Arrays.fill(password,'\0');if(archive!=null)Arrays.fill(archive,(byte)0);}
    }

    /** One DOWN/UP only. Waiting for the result never repeats the input. */
    private void singleTap(View control)throws Exception{
        require(control!=null,"Missing control in "+phase);
        reveal(control);
        long readyUntil=SystemClock.elapsedRealtime()+15000;boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<readyUntil){runOnMainSync(()->ready[0]=control.hasWindowFocus() && control.isShown() && control.getWidth()>0 && control.getHeight()>0);if(ready[0])break;Thread.sleep(100);}
        require(ready[0],"Native target window was not focused before the first tap");
        Rect[] bounds={new Rect()};boolean[] usable={false};runOnMainSync(()->{usable[0]=control.isShown() && control.isEnabled() && control.getGlobalVisibleRect(bounds[0]);if(usable[0]){int[] location=new int[2];control.getLocationOnScreen(location);bounds[0].set(location[0],location[1],location[0]+control.getWidth(),location[1]+control.getHeight());}});
        require(usable[0] && bounds[0].width()>0 && bounds[0].height()>0,"Native control is not visibly tappable in "+phase);
        progress("First physical tap at "+bounds[0]+" on "+control.getContentDescription()+"; focused="+ready[0]);
        long down=SystemClock.uptimeMillis();
        injectMotion(MotionEvent.ACTION_DOWN,down,bounds[0].centerX(),bounds[0].centerY());
        SystemClock.sleep(75);
        injectMotion(MotionEvent.ACTION_UP,down,bounds[0].centerX(),bounds[0].centerY());physicalTaps++;
        waitForIdleSync();
    }
    private void injectMotion(int action,long down,float x,float y)throws Exception{
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{require(automation.injectInputEvent(event,true),"Android rejected a physical touch event");}finally{event.recycle();}
    }
    private void singleTapDescription(Activity activity,String label)throws Exception{singleTap(waitDescription(activity,label));}
    private void singleTapText(Activity activity,String label)throws Exception{singleTap(waitText(activity,label));}
    private void openFirstTap(String row,String title)throws Exception{
        progress("ONE first Settings tap: "+row);
        View control=waitDescription(main,row);require(!touchModeFocusable(control),"Settings row steals the first tap through touch-mode focus: "+row);
        singleTap(control);waitDescription(main,"Back to Settings");
        require(text(main,title)!=null,"ONE first physical tap did not open Settings panel: "+row);
    }
    private void backAndAssertFocus(String row)throws Exception{singleTapDescription(main,"Back to Settings");waitDescription(main,"Back to Home");assertReturnFocus(row);}
    private void assertReturnFocus(String row)throws Exception{
        long until=SystemClock.elapsedRealtime()+5000;boolean[] focused={false};
        while(SystemClock.elapsedRealtime()<until){View returned=description(main,row);if(returned!=null)runOnMainSync(()->focused[0]=returned.isFocused());if(focused[0])break;Thread.sleep(100);}
        require(focused[0],"Returning from a native panel did not focus its Settings row: "+row);
    }
    private void typePhysically(String field,String value)throws Exception{
        View control=waitDescription(main,field);require(control instanceof EditText,"Native editable dictionary field is missing: "+field);
        singleTap(control);sendStringSync(value);waitForIdleSync();
        require(value.equals(fieldText(field)),"Physical key events did not edit dictionary field: "+field);
        if(typingKeyboardVisible()){sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();}
        require(description(main,field)!=null,"Hiding the typing keyboard accidentally left the dictionary panel");
    }
    private String fieldText(String description)throws Exception{View field=description(main,description);require(field instanceof EditText,"Dictionary field missing: "+description);String[] value={""};runOnMainSync(()->value[0]=((EditText)field).getText().toString());return value[0];}
    private void recreateMain()throws Exception{
        MainActivity previous=main;runOnMainSync(previous::recreate);long until=SystemClock.elapsedRealtime()+30000;
        while(main==previous && SystemClock.elapsedRealtime()<until)Thread.sleep(100);waitForIdleSync();
        require(main!=previous && !main.isDestroyed(),"Android did not complete real MainActivity recreation");
    }
    private void assertTreeOrder(String first,String second,String message)throws Exception{
        List<String> descriptions=new ArrayList<>();runOnMainSync(()->collectDescriptions(main.getWindow().getDecorView(),descriptions));
        require(descriptions.indexOf(first)>=0 && descriptions.indexOf(second)>=0 && descriptions.indexOf(first)<descriptions.indexOf(second),message);
    }
    private void collectDescriptions(View view,List<String> result){if(view.getContentDescription()!=null)result.add(view.getContentDescription().toString());if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collectDescriptions(group.getChildAt(i),result);}}
    private boolean hasBottomNavigation(){boolean[] found={false};runOnMainSync(()->{View content=main.findViewById(android.R.id.content);found[0]=containsOldNavigation(content);});return found[0];}
    private boolean containsOldNavigation(View view){if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;boolean home=false,settings=false;for(int i=0;i<group.getChildCount();i++){View child=group.getChildAt(i);if(child instanceof TextView && child.isClickable()){String label=((TextView)child).getText().toString();home|=label.equals("Home") || label.equals("History");settings|=label.equals("Settings");}if(containsOldNavigation(child))return true;}return home && settings;}return false;}
    private boolean historyHas(long id){JSONArray history=Store.history(context);for(int i=0;i<history.length();i++){JSONObject item=history.optJSONObject(i);if(item!=null && item.optLong("id")==id)return true;}return false;}
    private String defaultCleanupLabel()throws IOException{for(int i=0;i<ModelCatalog.CLEANUP_IDS.length;i++)if(ModelCatalog.CLEANUP_IDS[i].equals(CloudApi.DEFAULT_POLISH))return ModelCatalog.CLEANUP_NAMES[i];throw new IOException("Synthetic cached recap model is missing from the native model catalogue");}
    private boolean packageInstalled(String name){try{context.getPackageManager().getPackageInfo(name,0);return true;}catch(PackageManager.NameNotFoundException absent){return false;}}
    private boolean noBackupJob(){return ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID)==null;}
    private boolean touchModeFocusable(View view){boolean[] value={true};runOnMainSync(()->value[0]=view.isFocusableInTouchMode());return value[0];}
    private boolean enabled(View view){if(view==null)return false;boolean[] value={false};runOnMainSync(()->value[0]=view.isEnabled());return value[0];}
    private boolean checked(Switch view){if(view==null)return false;boolean[] value={false};runOnMainSync(()->value[0]=view.isChecked());return value[0];}
    private int selection(Spinner view){int[] value={-1};runOnMainSync(()->value[0]=view.getSelectedItemPosition());return value[0];}
    private boolean hasMinimumTouchTarget(View view){if(view==null)return false;boolean[] value={false};runOnMainSync(()->value[0]=view.getWidth()>=Ui.dp(context,44) && view.getHeight()>=Ui.dp(context,44));return value[0];}
    private boolean visibleDescription(Activity activity,String label){View view=description(activity,label);if(view==null)return false;boolean[] shown={false};runOnMainSync(()->shown[0]=view.isShown());return shown[0];}
    private View description(Activity activity,String label){return find(activity,null,label);}
    private View text(Activity activity,String label){return find(activity,label,null);}
    private View find(Activity activity,String text,String description){if(activity==null)return null;View[] found={null};runOnMainSync(()->found[0]=findView(activity.getWindow().getDecorView(),text,description));return found[0];}
    private View findView(View view,String text,String description){
        if(description!=null && description.equals(String.valueOf(view.getContentDescription())))return view;
        if(text!=null && view instanceof TextView){String actual=((TextView)view).getText().toString();if(text.equals(actual) || (GOOGLE_UNAVAILABLE.equals(text) && actual.startsWith(text)))return view;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findView(group.getChildAt(i),text,description);if(found!=null)return found;}}return null;
    }
    private View waitDescription(Activity activity,String description)throws Exception{return waitView(activity,null,description);}
    private View waitText(Activity activity,String text)throws Exception{return waitView(activity,text,null);}
    private View waitView(Activity activity,String text,String description)throws Exception{
        long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){View found=find(activity,text,description);if(found!=null){waitForIdleSync();return found;}Thread.sleep(100);}
        throw new Exception("Expected native control did not appear after the single tap: "+(text==null?description:text));
    }
    private void reveal(View control){runOnMainSync(()->control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true));waitForIdleSync();}
    private void waitPreference(String key,String expected)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(expected.equals(Store.get(context,key,""))){waitForIdleSync();require(true,"Saved preference");return;}Thread.sleep(100);}throw new Exception("Native control did not save preference: "+key);}
    private void waitBoolean(String key,boolean expected)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(prefs.contains(key) && Store.bool(context,key,!expected)==expected){waitForIdleSync();require(true,"Saved boolean preference");return;}Thread.sleep(100);}throw new Exception("Native control did not save switch preference: "+key);}
    private boolean typingKeyboardVisible(){for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows())if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect bounds=new Rect();window.getBoundsInScreen(bounds);if(bounds.height()>80)return true;}return false;}
    private android.view.accessibility.AccessibilityNodeInfo dialogNode(String text){for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){android.view.accessibility.AccessibilityNodeInfo found=findNodeText(window.getRoot(),text);if(found!=null)return found;}return null;}
    private android.view.accessibility.AccessibilityNodeInfo findNodeText(android.view.accessibility.AccessibilityNodeInfo node,String text){if(node==null)return null;CharSequence value=node.getText();if(value!=null && text.equalsIgnoreCase(value.toString()) && node.isVisibleToUser())return node;for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo found=findNodeText(node.getChild(i),text);if(found!=null){node.recycle();return found;}}node.recycle();return null;}
    private void waitDialogText(String text)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){android.view.accessibility.AccessibilityNodeInfo node=dialogNode(text);if(node!=null){node.recycle();return;}Thread.sleep(100);}throw new Exception("Actual confirmation dialog did not appear: "+text);}
    private void waitDialogGone(String text)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){android.view.accessibility.AccessibilityNodeInfo node=dialogNode(text);if(node==null)return;node.recycle();Thread.sleep(100);}throw new Exception("Actual confirmation dialog did not close: "+text);}
    private void singleTapDialogButton(String label)throws Exception{
        android.view.accessibility.AccessibilityNodeInfo node=dialogNode(label);require(node!=null && node.isEnabled() && node.isClickable(),"Actual dialog button is missing: "+label);
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);node.recycle();require(!bounds.isEmpty(),"Dialog button has empty native bounds");
        long down=SystemClock.uptimeMillis();injectMotion(MotionEvent.ACTION_DOWN,down,bounds.centerX(),bounds.centerY());SystemClock.sleep(75);injectMotion(MotionEvent.ACTION_UP,down,bounds.centerX(),bounds.centerY());physicalTaps++;waitForIdleSync();
    }
    private File mediaDirectory()throws IOException{File[] locations=context.getExternalMediaDirs();if(locations==null || locations.length==0 || locations[0]==null)throw new IOException("Native screenshot media directory unavailable");File directory=locations[0];if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Native screenshot directory could not be created");return directory;}
    private void capture(String name)throws Exception{
        waitForIdleSync();Bitmap shot=automation.takeScreenshot();require(shot!=null,"Android did not capture the actual native display");
        File file=new File(mediaDirectory(),name);try{try(FileOutputStream output=new FileOutputStream(file)){require(shot.compress(Bitmap.CompressFormat.PNG,100,output),"Native screenshot PNG export failed");}File review=new File(context.getExternalFilesDir(null),name);try(FileOutputStream output=new FileOutputStream(review)){require(shot.compress(Bitmap.CompressFormat.PNG,100,output),"Review screenshot PNG export failed");}}finally{shot.recycle();}
        require(file.length()>0,"Native screenshot export is empty");
    }
    private void progress(String value){phase=value;Bundle status=new Bundle();status.putString("phase",value);sendStatus(0,status);}
    private void require(boolean okay,String message)throws Exception{checks++;if(!okay)throw new Exception(message);}
    private String diagnostics(){StringBuilder output=new StringBuilder();try{runOnMainSync(()->{if(main!=null)dump(main.getWindow().getDecorView(),output,0);if(backup!=null)dump(backup.getWindow().getDecorView(),output,0);});}catch(Throwable problem){output.append("Control diagnostics unavailable: ").append(problem.getClass().getSimpleName());}return output.toString();}
    private void dump(View view,StringBuilder output,int depth){if(depth>20)return;if(view.getContentDescription()!=null || view instanceof TextView){output.append(view.getClass().getSimpleName()).append(" description=").append(view.getContentDescription()).append(" shown=").append(view.isShown()).append(" enabled=").append(view.isEnabled()).append(" focused=").append(view.isFocused()).append(" touchFocusable=").append(view.isFocusableInTouchMode());if(view instanceof TextView && !(view instanceof EditText))output.append(" label=").append(((TextView)view).getText());output.append('\n');}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)dump(group.getChildAt(i),output,depth+1);}}
    @SuppressWarnings("unchecked")private void restorePreferences(SharedPreferences store,Map<String,?> original)throws IOException{SharedPreferences.Editor editor=store.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);}if(!editor.commit())throw new IOException("Synthetic fixture could not restore the original preference map");}
}
