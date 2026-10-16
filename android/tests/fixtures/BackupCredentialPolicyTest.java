package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Settings-only backup policy, using isolated preferences and no real credentials/network. */
public final class BackupCredentialPolicyTest extends Instrumentation {
    private static final String LOCAL_KEY="fixture-local-only-key-envelope";
    private static final String LOCAL_IV="fixture-local-only-iv";
    private int checks;
    private SharedPreferences isolated;
    private Context context;

    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart() {
        Bundle report=new Bundle();String oldDraft=MainActivity.currentDraft;
        isolated=getTargetContext().getSharedPreferences("backup-policy-test",Context.MODE_PRIVATE);
        context=withPreferences(isolated);
        try {
            isolated.edit().clear().putString("api_base",CloudApi.DEFAULT_BASE)
                    .putString("api_secret",LOCAL_KEY).putString("api_iv",LOCAL_IV)
                    .putString("drive_email","fixture@example.invalid")
                    .putString("backup_password_wrapped","fixture-password-secret")
                    .putString("notes_transcribe_model","fixture-notes-transcript").putString("notes_polish_model","fixture-notes-summary")
                    .putString("translate_transcribe_model","fixture-translation-transcript").putString("translate_model","fixture-translation")
                    .putString("dictionary","local=Local").commit();
            MainActivity.currentDraft="fixture portable draft";
            JSONObject snapshot=BackupData.snapshot(context);
            require(!snapshot.has("api_key"),"New snapshot includes an API key field");
            String plain=snapshot.toString();
            require(!plain.contains(LOCAL_KEY) && !plain.contains(LOCAL_IV)
                    && !plain.contains("fixture-password-secret") && !plain.contains("fixture@example.invalid"),
                    "Snapshot exported a local credential or Google connection");
            require(snapshot.getJSONObject("settings").getString("api_base").equals(CloudApi.DEFAULT_BASE),
                    "Provider endpoint was not exported");
            require(snapshot.getString("draft").equals("fixture portable draft"),"Draft was not retained");
            require(snapshot.getJSONObject("settings").getString("notes_transcribe_model").equals("fixture-notes-transcript")
                    && snapshot.getJSONObject("settings").getString("notes_polish_model").equals("fixture-notes-summary")
                    && snapshot.getJSONObject("settings").getString("translate_transcribe_model").equals("fixture-translation-transcript")
                    && snapshot.getJSONObject("settings").getString("translate_model").equals("fixture-translation"),"Notes and Translation model settings were not exported");
            String fingerprint=DriveBackup.dataFingerprint(context);
            isolated.edit().putString("api_secret","changed-local-secret").commit();
            require(fingerprint.equals(DriveBackup.dataFingerprint(context)),"A local credential affected backup freshness");
            isolated.edit().putString("api_secret",LOCAL_KEY).putString("notes",new JSONArray().put(new JSONObject().put("id",1).put("created",1).put("thought",true).put("raw","A saved thought").put("text","A saved thought").put("title","Thought").put("summary","Saved summary").put("duration",1000).put("lang","en")).toString()).commit();
            String withThought=DriveBackup.dataFingerprint(context);
            require(!fingerprint.equals(withThought),"A new Quick Thought did not make the saved snapshot stale");
            isolated.edit().putBoolean("daily_recaps_opt_in",true).commit();
            require(!withThought.equals(DriveBackup.dataFingerprint(context)),"Daily recap consent did not make the saved snapshot stale");

            JSONObject legacy=archive(CloudApi.DEFAULT_BASE).put("api_key","fixture-legacy-archive-key");
            JSONObject checked=BackupData.validate(legacy);
            require(!checked.has("api_key"),"Legacy API key was retained by validation");
            require(legacy.getString("api_key").equals("fixture-legacy-archive-key"),"Validation mutated the source archive");
            require(!BackupData.validate(checked).has("api_key"),"Validating a stripped archive reintroduced credentials");

            BackupData.restore(context,legacy);
            requireLocalKey("Same-provider restore replaced local credentials with archived credentials");
            requireRestoredSettings(CloudApi.DEFAULT_BASE);
            require(isolated.getString("drive_email","").equals("fixture@example.invalid"),"Restore changed local Google connection");

            seedConnection(CloudApi.DEFAULT_BASE+"///");
            BackupData.restore(context,archive("  "+CloudApi.DEFAULT_BASE+"/  ").put("api_key","fixture-legacy-archive-key"));
            requireLocalKey("Equivalent normalized API bases removed the local key");

            seedConnection(CloudApi.DEFAULT_BASE+"/different-path");
            BackupData.restore(context,legacy);
            requireNoLocalKey("Changed API path retained credentials for a different connection");
            requireRestoredSettings(CloudApi.DEFAULT_BASE);

            seedConnection(CloudApi.DEFAULT_BASE);
            BackupData.restore(context,archive("https://provider.example.invalid/v1").put("api_key","fixture-legacy-archive-key"));
            requireNoLocalKey("Changed provider retained or imported credentials");
            requireRestoredSettings("https://provider.example.invalid/v1");

            seedConnection("http://invalid.example.invalid/v1");
            BackupData.restore(context,legacy);
            requireNoLocalKey("Invalid old endpoint retained credentials");

            isolated.edit().clear().commit();
            BackupData.restore(context,legacy);
            requireNoLocalKey("Fresh-phone restore imported legacy credentials");
            requireRestoredSettings(CloudApi.DEFAULT_BASE);

            seedConnection(CloudApi.DEFAULT_BASE);
            MainActivity.currentDraft="fixture draft before failed restore";
            Map<String,?> before=isolated.getAll();
            reject(()->BackupData.restore(context,archive("http://unsafe.example.invalid/v1")),"Unsafe endpoint was accepted");
            require(before.equals(isolated.getAll()),"Invalid archive changed local credentials/settings");

            // Android preference writes may update memory even when commit returns false.
            // Fail exactly the first commit after applying its memory changes, then allow
            // the rollback commit so the production recovery path can be exercised.
            AtomicBoolean failCommit=new AtomicBoolean(true);
            Context failing=withPreferences(failingCommitPreferences(isolated,failCommit));
            reject(()->BackupData.restore(failing,archive("https://changed.example.invalid/v1")),"Failed preference commit reported success");
            require(!failCommit.get(),"Commit failure injection was not exercised");
            require(before.equals(isolated.getAll()),"Failed restore did not roll back endpoint, settings and local credentials together");
            require(MainActivity.currentDraft.equals("fixture draft before failed restore"),"Failed restore replaced the draft");
            requireLocalKey("Rollback lost the previous local credential");
            report.putString("result","PASS: "+checks+" settings-only backup/restore checks; no exported or imported API credentials, legacy compatibility, portable provider/model/endpoint settings, same-provider local key preservation, changed-path/provider/invalid-base clearance, fresh-phone reconnect and failed-commit rollback");
        } catch(Throwable failure) {report.putString("failure",failure.toString());}
        finally {MainActivity.currentDraft=oldDraft;isolated.edit().clear().commit();}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void seedConnection(String base) {
        isolated.edit().putString("api_base",base).putString("api_secret",LOCAL_KEY).putString("api_iv",LOCAL_IV).commit();
    }
    private void requireLocalKey(String message) throws Exception {
        require(isolated.getString("api_secret","").equals(LOCAL_KEY) && isolated.getString("api_iv","").equals(LOCAL_IV),message);
    }
    private void requireNoLocalKey(String message) throws Exception {
        require(!isolated.contains("api_secret") && !isolated.contains("api_iv"),message);
    }
    private void requireRestoredSettings(String base) throws Exception {
        require(isolated.getString("api_base","").equals(base)
                && isolated.getString("transcribe_model","").equals("fixture-transcript-model")
                && isolated.getString("polish_model","").equals("fixture-cleanup-model")
                && isolated.getString("notes_transcribe_model","").equals("fixture-notes-transcript")
                && isolated.getString("notes_polish_model","").equals("fixture-notes-summary")
                && isolated.getString("translate_transcribe_model","").equals("fixture-translation-transcript")
                && isolated.getString("translate_model","").equals("fixture-translation")
                && isolated.getString("engine","").equals("cloud"),"Provider/model/endpoint settings were not restored");
        require(isolated.getString("dictionary","").equals("archived=Archived") && Store.history(context).length()==1,
                "Dictionary/history were not restored");
    }
    private JSONObject archive(String base) throws Exception {
        return new JSONObject().put("format","voiceflow-backup").put("schema",1).put("created_at",1700000000000L)
                .put("settings",new JSONObject().put("api_base",base).put("engine","cloud")
                        .put("transcribe_model","fixture-transcript-model").put("polish_model","fixture-cleanup-model")
                        .put("notes_transcribe_model","fixture-notes-transcript").put("notes_polish_model","fixture-notes-summary")
                        .put("translate_transcribe_model","fixture-translation-transcript").put("translate_model","fixture-translation")
                        .put("dictionary","archived=Archived"))
                .put("history",new JSONArray().put(new JSONObject().put("id",1700000000000L).put("text","Fixture words")
                        .put("raw","Fixture words").put("duration",1000L).put("words",2)))
                .put("draft","fixture restored draft");
    }
    private Context withPreferences(final SharedPreferences preferences) {
        return new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
        };
    }
    private static SharedPreferences failingCommitPreferences(SharedPreferences target,AtomicBoolean failCommit) {
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
            try {
                Object value=method.invoke(target,args);
                if(!method.getName().equals("edit"))return value;
                final SharedPreferences.Editor edit=(SharedPreferences.Editor)value;
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editorProxy,editorMethod,editorArgs)->{
                    try {
                        Object edited=editorMethod.invoke(edit,editorArgs);
                        if(editorMethod.getName().equals("commit") && failCommit.compareAndSet(true,false))return Boolean.FALSE;
                        return edited==edit?editorProxy:edited;
                    } catch(InvocationTargetException failure){throw failure.getCause();}
                });
            } catch(InvocationTargetException failure){throw failure.getCause();}
        });
    }
    private void reject(Task task,String message) throws Exception {
        boolean rejected=false;try{task.run();}catch(Exception expected){rejected=true;}require(rejected,message);
    }
    private void require(boolean pass,String message) throws Exception {checks++;if(!pass)throw new Exception(message);}
    private interface Task{void run() throws Exception;}
}
