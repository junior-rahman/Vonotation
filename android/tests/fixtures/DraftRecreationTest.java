package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.Parcel;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Activity recreation with the exact Bundle saved before a later data restore. */
public final class DraftRecreationTest extends Instrumentation {
    private static final String BEFORE="Synthetic draft A saved before restore";
    private static final String RESTORED="Synthetic restored draft B";
    private volatile MainActivity main;
    private Activity backup;
    private Bundle preRestore;
    private volatile boolean injectOldBundle,injected,parentStopped;
    private boolean observeOriginal;
    private String processStage="",processCase="restore";
    private int checks;

    @Override public void onCreate(Bundle args){super.onCreate(args);observeOriginal=args!=null && "true".equals(args.getString("observe_original"));if(args!=null){processStage=args.getString("process_stage","");processCase=args.getString("process_case","restore");}start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){
        if(activity instanceof MainActivity){
            main=(MainActivity)activity;
            if(injectOldBundle){state=new Bundle(preRestore);injectOldBundle=false;injected=true;}
        }
        super.callActivityOnCreate(activity,state);
    }
    @Override public void callActivityOnStop(Activity activity){super.callActivityOnStop(activity);if(activity==main)parentStopped=true;}
    @Override public void onStart(){
        if(!processStage.isEmpty()){processRestartCheck();return;}
        Bundle report=new Bundle();Context context=getTargetContext();SharedPreferences prefs=Store.prefs(context);
        Map<String,?> before=prefs.getAll();String oldDraft=MainActivity.currentDraft;
        try {
            // Synthetic local data only; summaries, jobs and real authorization stay off.
            prefs.edit().clear().putBoolean("daily_summaries",false).putString("backup_frequency","off").commit();
            MainActivity.currentDraft=BEFORE;
            main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
            preRestore=new Bundle();runOnMainSync(()->callActivityOnSaveInstanceState(main,preRestore));
            require(BEFORE.equals(preRestore.getString("draft")),"Pre-restore Bundle does not contain draft A");
            // Opening another native Activity stops the parent before restore mutates the draft.
            backup=startActivitySync(new Intent(context,BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            long stoppedUntil=SystemClock.elapsedRealtime()+10000;
            while(!parentStopped && SystemClock.elapsedRealtime()<stoppedUntil)Thread.sleep(100);
            require(parentStopped,"Parent MainActivity was not stopped before restore");
            JSONObject payload=new JSONObject().put("format","voiceflow-backup").put("schema",1)
                    .put("created_at",1700000000000L).put("settings",new JSONObject().put("daily_summaries",false).put("backup_frequency","off"))
                    .put("history",new JSONArray()).put("draft",RESTORED);
            BackupData.restore(context,payload);
            require(RESTORED.equals(MainActivity.currentDraft),"Restore did not establish draft B");
            require(BEFORE.equals(preRestore.getString("draft")),"Saved Bundle was replaced with a fresh post-restore Bundle");
            MainActivity oldMain=main;injectOldBundle=true;
            runOnMainSync(()->oldMain.recreate());
            // A stopped Activity may defer relaunch until it is visible again.
            runOnMainSync(()->backup.finish());backup=null;
            long until=SystemClock.elapsedRealtime()+30000;
            while(SystemClock.elapsedRealtime()<until && (!injected || main==oldMain)){Thread.sleep(100);}
            waitForIdleSync();
            require(injected && main!=oldMain,"Android did not recreate MainActivity with the PRE-restore Bundle");
            boolean staleOverwrite=BEFORE.equals(MainActivity.currentDraft);
            report.putBoolean("stale_bundle_overwrote_restored_draft",staleOverwrite);
            if(observeOriginal){
                require(staleOverwrite,"Frozen original did not reproduce the source-audit lifecycle candidate");
                report.putString("result","REPRODUCED: "+checks+" native checks; real same-process MainActivity recreation with PRE-restore Bundle A overwrote restored draft B. No Google authorization or remote restore exercised.");
            }else{
                require(RESTORED.equals(MainActivity.currentDraft),"PRE-restore Bundle A overwrote restored draft B");
                MainActivity beforeOrdinary=main;
                runOnMainSync(()->beforeOrdinary.recreate());
                long ordinaryUntil=SystemClock.elapsedRealtime()+30000;
                while(main==beforeOrdinary && SystemClock.elapsedRealtime()<ordinaryUntil)Thread.sleep(100);
                waitForIdleSync();
                require(main!=beforeOrdinary,"Ordinary Android recreation did not complete");
                require(RESTORED.equals(MainActivity.currentDraft),"Ordinary fresh-Bundle recreation lost draft B");
                report.putString("result","PASS: "+checks+" native stale/fresh-Bundle checks; real same-process MainActivity recreation retained restored draft B against PRE-restore Bundle A and across ordinary recreation. No Google authorization or remote restore exercised.");
            }
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally {
            if(backup!=null)runOnMainSync(()->backup.finish());if(main!=null)runOnMainSync(()->main.finish());
            restorePrefs(prefs,before);MainActivity.currentDraft=oldDraft;DriveBackup.schedule(context);
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    /** Two instrumentations separated by an explicit adb force-stop of the target. */
    private void processRestartCheck(){
        Bundle report=new Bundle();Context context=getTargetContext();SharedPreferences prefs=Store.prefs(context);
        File file=new File(context.getCacheDir(),"draft-process-fixture.bin");Bundle cached=null;boolean seeded=false;
        try {
            if(processStage.equals("seed")) {
                require(processCase.equals("restore") || processCase.equals("delete") || processCase.equals("clear"),"Unknown process-restart case");
                cached=new Bundle();cached.putBundle("original_preferences",preferencesBundle(prefs.getAll()));cached.putString("original_draft",MainActivity.currentDraft);
                prefs.edit().clear().putBoolean("daily_summaries",false).putString("backup_frequency","off").putString("dictionary","synthetic=Dictionary").commit();
                Store.save(context,BEFORE,BEFORE,0);DraftState.set(BEFORE);
                main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
                preRestore=new Bundle();runOnMainSync(()->callActivityOnSaveInstanceState(main,preRestore));
                require(BEFORE.equals(preRestore.getString("draft")),"Process seed did not capture exact pre-mutation Bundle A");
                backup=startActivitySync(new Intent(context,BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
                long stoppedUntil=SystemClock.elapsedRealtime()+10000;while(!parentStopped && SystemClock.elapsedRealtime()<stoppedUntil)Thread.sleep(100);
                require(parentStopped,"Process seed parent was not stopped before mutation");
                if(processCase.equals("restore")) {
                    JSONObject entry=new JSONObject().put("id",1700000000000L).put("text",RESTORED).put("raw",RESTORED).put("duration",0).put("words",Cleaner.words(RESTORED));
                    BackupData.restore(context,new JSONObject().put("format","voiceflow-backup").put("schema",1).put("created_at",1700000000000L)
                            .put("settings",new JSONObject().put("daily_summaries",false).put("backup_frequency","off").put("dictionary","restored=Dictionary"))
                            .put("history",new JSONArray().put(entry)).put("draft",RESTORED));
                    require(RESTORED.equals(MainActivity.currentDraft),"Process seed restore did not establish B");
                }else{
                    // A is the deleted history item while an independent live B remains.
                    DraftState.set(RESTORED);
                    if(processCase.equals("delete"))Store.delete(context,Store.history(context).getJSONObject(0).getLong("id"));else Store.clearHistory(context);
                    require(RESTORED.equals(MainActivity.currentDraft),"Deleting A discarded unrelated live draft B");
                    require(Store.history(context).length()==0,"Deleted A remains in history");
                }
                require(!prefs.getString("draft_epoch","").equals(preRestore.getString("draft_epoch","")),"Mutation did not invalidate the pre-mutation Bundle epoch");
                require(!BackupData.snapshot(context).toString().contains(BEFORE),"New seed snapshot exported removed A");
                // Delete/clear use the app's existing apply path. This commit flushes
                // the completed preferences mutation before intentionally killing it;
                // it does not claim coverage of failed physical preference writes.
                require(prefs.edit().putBoolean("draft_process_fixture_flush",true).commit(),"Could not persist process seed");
                cached.putBundle("pre_mutation_bundle",preRestore);cached.putInt("seed_pid",android.os.Process.myPid());cached.putString("case",processCase);
                cached.putString("expected_history",prefs.getString("history","[]"));cached.putString("expected_dictionary",prefs.getString("dictionary",""));
                writeBundle(file,cached);seeded=true;
                report.putString("result","SEEDED: "+checks+" native "+processCase+" checks; exact stopped-parent PRE-mutation Bundle A cached after persisted mutation. Force-stop app.voiceflow, then run process_stage verify.");
            }else if(processStage.equals("verify")) {
                cached=readBundle(file);processCase=cached.getString("case","");preRestore=cached.getBundle("pre_mutation_bundle");
                require(android.os.Process.myPid()!=cached.getInt("seed_pid"),"Verify did not run in a new target process");
                require(preRestore!=null && BEFORE.equals(preRestore.getString("draft")),"Verify lost the exact PRE-mutation Bundle A");
                require(MainActivity.currentDraft.isEmpty(),"New process unexpectedly inherited the old static draft");
                require(!prefs.getString("draft_epoch","").equals(preRestore.getString("draft_epoch","")),"Persisted invalidation epoch was lost across process death");
                injectOldBundle=true;
                main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();
                require(injected,"Native MainActivity did not receive the cached PRE-mutation Bundle");
                require(MainActivity.currentDraft.isEmpty(),"Old Bundle resurrected draft A after process death");
                JSONObject snapshot=BackupData.snapshot(context);
                require(!snapshot.toString().contains(BEFORE),"New backup snapshot exported removed A after process death");
                require(snapshot.getJSONArray("history").toString().equals(cached.getString("expected_history")),"Process restart changed committed history");
                require(snapshot.getJSONObject("settings").getString("dictionary").equals(cached.getString("expected_dictionary")),"Process restart changed committed dictionary");
                report.putString("result","PASS: "+checks+" native "+processCase+" process-death checks; new PID, actual PRE-mutation Bundle replay, deleted/replaced A absent from fresh backup, committed history/dictionary retained. Draft B remains ephemeral. No Google or remote restore exercised.");
            }else throw new Exception("Unknown process_stage");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally {
            if(!seeded){
                if(backup!=null)runOnMainSync(()->backup.finish());if(main!=null)runOnMainSync(()->main.finish());
                if(cached!=null){restorePreferenceBundle(prefs,cached.getBundle("original_preferences"));DraftState.set(cached.getString("original_draft",""));}
                file.delete();DriveBackup.schedule(context);
            }
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    @SuppressWarnings("unchecked") private static Bundle preferencesBundle(Map<String,?> preferences){
        Bundle saved=new Bundle();for(Map.Entry<String,?> item:preferences.entrySet()){
            Object value=item.getValue();String key=item.getKey();
            if(value instanceof String)saved.putString(key,(String)value);else if(value instanceof Integer)saved.putInt(key,(Integer)value);else if(value instanceof Long)saved.putLong(key,(Long)value);
            else if(value instanceof Float)saved.putFloat(key,(Float)value);else if(value instanceof Boolean)saved.putBoolean(key,(Boolean)value);else if(value instanceof Set)saved.putStringArrayList(key,new java.util.ArrayList<>((Set<String>)value));
        }return saved;
    }
    @SuppressWarnings("unchecked") private static void restorePreferenceBundle(SharedPreferences preferences,Bundle saved){
        if(saved==null)return;Map<String,Object> values=new java.util.HashMap<>();for(String key:saved.keySet()){
            Object value=saved.get(key);values.put(key,value instanceof java.util.ArrayList?new java.util.HashSet<>((java.util.ArrayList<String>)value):value);
        }restorePrefs(preferences,values);
    }
    private static void writeBundle(File file,Bundle bundle)throws Exception{
        Parcel parcel=Parcel.obtain();try{parcel.writeBundle(bundle);try(FileOutputStream out=new FileOutputStream(file)){out.write(parcel.marshall());out.getFD().sync();}}finally{parcel.recycle();}
    }
    private static Bundle readBundle(File file)throws Exception{
        if(!file.isFile() || file.length()>8*1024*1024)throw new Exception("Process seed cache missing or invalid");
        byte[] bytes=new byte[(int)file.length()];try(FileInputStream in=new FileInputStream(file)){int position=0,count;while(position<bytes.length && (count=in.read(bytes,position,bytes.length-position))>0)position+=count;if(position!=bytes.length)throw new Exception("Process seed cache truncated");}
        Parcel parcel=Parcel.obtain();try{parcel.unmarshall(bytes,0,bytes.length);parcel.setDataPosition(0);Bundle value=parcel.readBundle(DraftRecreationTest.class.getClassLoader());if(value==null)throw new Exception("Process seed cache empty");return value;}finally{parcel.recycle();}
    }
    private void require(boolean pass,String message)throws Exception{checks++;if(!pass)throw new Exception(message);}
    @SuppressWarnings("unchecked") private static void restorePrefs(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);}edit.commit();}
}
