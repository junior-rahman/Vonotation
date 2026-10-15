package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs the production Drive protocol against an in-process server: no Google account or network. */
public final class DriveCloudTest extends Instrumentation {
    private static final String TOKEN_A="fixture-account-a-access", TOKEN_B="fixture-account-b-access";
    private static final String EMAIL_A="fixture-a@example.invalid", EMAIL_B="fixture-b@example.invalid";
    private static final char[] PASSWORD="fixture private backup pass".toCharArray();
    private static final char[] WRONG_PASSWORD="fixture wrong backup pass".toCharArray();
    private static final String FILE_NAME="VoiceFlow-backup-1700000000000-0123456789abcdef0123456789abcdef.vfb";
    private static final byte[] MAGIC="VFLOWBKP".getBytes(StandardCharsets.US_ASCII);
    private final List<MockConnection> requests=new ArrayList<>();
    private Responder responder;
    private Context context;
    private int checks;
    private boolean networkIsolated,wifiWasOn,dataWasOn;
    private boolean followUp,partialListing;

    @Override public void onCreate(Bundle args){super.onCreate(args);followUp=args!=null && "true".equals(args.getString("follow_up"));partialListing=args!=null && "true".equals(args.getString("partial_listing"));start();}
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();
        SharedPreferences prefs=Store.prefs(context),secrets=context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE);
        Map<String,?> oldPrefs=prefs.getAll(),oldSecrets=secrets.getAll();String oldDraft=MainActivity.currentDraft;
        try {
            require(Store.get(context,"backup_account","").isEmpty() && Store.get(context,"backup_uri","").isEmpty(),"Run cloud tests only on an isolated app installation");
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("https") || protocol.equals("http")?new URLStreamHandler(){
                protected URLConnection openConnection(URL url)throws IOException {
                    requireUrl(url);MockConnection request=new MockConnection(url);requests.add(request);return request;
                }
            }:null);
            isolateJobNetwork();
            if(partialListing){progress("Testing older-archive recovery beside rejected Drive generations");testPartialListing();}
            else{
                if(!followUp){progress("Testing bounded Drive requests and managed-file discovery");testTransport();}
                progress("Testing encrypted cloud-backup lifecycle and recovery rules");testLifecycle();
            }
            report.putString("result","PASS: "+checks+" production "+(partialListing?"partial-listing recovery":followUp?"cloud lifecycle follow-up":"Drive transport and cloud lifecycle")+" checks, entirely offline; real Google authorization/account/Console setup remains unverified");
        } catch(Throwable error){report.putString("failure",android.util.Log.getStackTraceString(error));report.putInt("completed_checks",checks);}
        finally {
            restorePrefs(secrets,oldSecrets);restorePrefs(prefs,oldPrefs);MainActivity.currentDraft=oldDraft;
            try{DriveBackup.schedule(context);}catch(Exception ignored){}
            try{((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(DriveBackup.JOB_ID);restoreJobNetwork();}catch(Exception ignored){}
            Arrays.fill(PASSWORD,'\0');Arrays.fill(WRONG_PASSWORD,'\0');
        }
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void testPartialListing()throws Exception {
        SharedPreferences prefs=Store.prefs(context);prefs.edit().clear().putString("history","[]").putString("dictionary","older=Older").putString("engine","cloud").commit();BackupSecrets.clear(context);Vault.set(context,"fixture-device-only-key");DraftState.set("Older archived draft sentinel");
        FakeServer server=new FakeServer();responder=server;
        DriveBackup.Connection empty=DriveBackup.discover(context,TOKEN_A);DriveBackup.connect(context,empty);DriveBackup.configurePassword(context,empty,PASSWORD,null);
        Store.save(context,"older source words","Older recoverable words",12000);DriveBackup.backup(context,TOKEN_A);
        DriveBackup.Connection healthy=DriveBackup.discover(context,TOKEN_A);DriveCloudClient.BackupFile older=healthy.files.get(0);byte[] original=server.files.get(older.id).bytes.clone();
        prefs.edit().putString("backup_frequency","daily").putBoolean("backup_wifi",true).commit();DriveBackup.schedule(context);require(DriveBackup.passwordReady(context) && backupJob()!=null,"Healthy setup did not enable the explicitly selected schedule");

        server.files.put("b-zero",new RemoteFile(TOKEN_B,metadata("b-zero",1700000030000L,"committed",0),new byte[0]));
        Map<String,?> pinned=prefs.getAll();Map<String,?> pinnedSecret=BackupSecrets.snapshot(context);
        DriveBackup.Connection other=DriveBackup.discover(context,TOKEN_B);
        require(other.hasRejected && other.files.isEmpty(),"Rejected-only candidate was presented as an empty Drive account");
        require(prefs.getAll().equals(pinned) && BackupSecrets.snapshot(context).equals(pinnedSecret) && backupJob()!=null,"Another account's rejected files paused or changed the pinned account");

        server.files.put("a-zero",new RemoteFile(TOKEN_A,metadata("a-zero",1700000040000L,"committed",0),new byte[0]));
        JSONObject future=metadata("a-future",1700000050000L,"committed",original.length);future.getJSONObject("appProperties").put("schema","2");
        server.files.put("a-future",new RemoteFile(TOKEN_A,future,original));
        require(!DriveBackup.listingIncomplete(context) && !healthy.hasRejected,"Reconnect rollback did not begin from a clean saved connection");
        reject(()->DriveBackup.connect(context,healthy),"Reconnect adopted history that became partial after clean discovery");
        require(DriveBackup.listingIncomplete(context) && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context)
                && backupJob()==null && "daily".equals(Store.get(context,"backup_frequency","")),"Reconnect rollback erased the newly discovered partial-listing pause or cadence consent");
        Map<String,Object> rolledBack=new LinkedHashMap<>(prefs.getAll()),expectedReconnect=new LinkedHashMap<>(pinned);
        rolledBack.remove("backup_listing_incomplete");rolledBack.remove("backup_listing_warning");
        expectedReconnect.remove("backup_listing_incomplete");expectedReconnect.remove("backup_listing_warning");
        require(rolledBack.equals(expectedReconnect) && BackupSecrets.snapshot(context).equals(pinnedSecret),"Rejected reconnect changed the saved identity, data, password or other configuration instead of preserving only its safety pause");
        DriveBackup.Connection mixed=DriveBackup.discover(context,TOKEN_A);
        require(mixed.hasRejected && mixed.rejected.size()==2 && mixed.files.size()==1 && mixed.files.get(0).id.equals(older.id) && !mixed.warning.isEmpty(),"Rejected generations hid the valid older archive or lost the warning");
        require(DriveBackup.configured(context) && DriveBackup.listingIncomplete(context) && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context) && backupJob()==null && "daily".equals(Store.get(context,"backup_frequency","")),"Incomplete pinned listing was treated as upload-ready or erased raw cadence consent");
        require(Store.bool(context,"backup_cloud_ready",false) && BackupSecrets.snapshot(context).equals(pinnedSecret),"Discovery erased the existing validated password rather than pausing this listing");
        int uploads=server.uploads,deletes=server.deletes;long success=DriveBackup.lastSuccess(context);
        reject(()->DriveBackup.configurePassword(context,mixed,PASSWORD,null),"Rejected cloud history was bypassed as a fresh account");
        reject(()->DriveBackup.configurePassword(context,mixed,PASSWORD,older),"Partial history enabled a new password/upload configuration");
        reject(()->DriveBackup.backup(context,TOKEN_A),"Partial history allowed a replacement upload");
        require(server.uploads==uploads && server.deletes==deletes && Arrays.equals(original,server.files.get(older.id).bytes) && DriveBackup.lastSuccess(context)==success,"Blocked partial-history backup changed a remote generation or success status");

        JSONArray currentHistory=new JSONArray().put(new JSONObject().put("id",1700000090000L).put("text","Current local words before recovery")
                .put("raw","Current local raw before recovery").put("duration",1000L).put("words",6));
        synchronized(Store.class){require(prefs.edit().putString("history",currentHistory.toString()).putString("dictionary","current=Current").commit(),"Could not seed distinct current data before older recovery");DraftState.set("Current unsaved draft sentinel");}
        require(Store.history(context).getJSONObject(0).getString("text").equals("Current local words before recovery")
                && Store.get(context,"dictionary","").equals("current=Current") && MainActivity.currentDraft.equals("Current unsaved draft sentinel"),"Older recovery did not begin with distinct current history, dictionary and draft");
        JSONObject restored=DriveBackup.read(context,mixed,older,PASSWORD);DriveBackup.restore(context,mixed,PASSWORD,older,restored);
        require(Store.history(context).length()==1 && Store.history(context).getJSONObject(0).getString("text").equals("Older recoverable words")
                && Store.history(context).getJSONObject(0).getString("raw").equals("older source words")
                && Store.get(context,"dictionary","").equals("older=Older") && MainActivity.currentDraft.equals("Older archived draft sentinel"),"Explicit older restore did not replace the distinct current history, dictionary and draft with the archived data");
        require(Vault.get(context).equals("fixture-device-only-key"),"Explicit older restore changed this phone's same-endpoint credential");
        require(DriveBackup.listingIncomplete(context) && !Store.bool(context,"backup_cloud_ready",true) && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context) && "off".equals(Store.get(context,"backup_frequency","")) && backupJob()==null,"Older recovery beside rejected files enabled backup or automation");
        require(server.files.containsKey("a-zero") && server.files.containsKey("a-future") && server.uploads==uploads && server.deletes==deletes,"Older recovery deleted rejected generations or uploaded replacement data");

        server.files.remove("a-zero");server.files.remove("a-future");
        DriveBackup.Connection repaired=DriveBackup.discover(context,TOKEN_A);DriveBackup.configurePassword(context,repaired,PASSWORD,older);
        require(!DriveBackup.listingIncomplete(context) && DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context),"Validated repaired history did not restore readiness with automation still Off");
        prefs.edit().putString("backup_frequency","daily").commit();DriveBackup.schedule(context);require(backupJob()!=null,"Explicit new cadence was not schedulable after repair");
        DriveBackup.backup(context,TOKEN_A);require(server.uploads==uploads+1 && server.files.containsKey(older.id),"Healthy repair could not save a new acknowledged generation or lost the old one");
        DriveBackup.disconnect(context);
        DriveBackup.Connection rejectedOnly=DriveBackup.discover(context,TOKEN_B);
        reject(()->DriveBackup.configurePassword(context,rejectedOnly,PASSWORD,null),"Rejected-only remote files were treated as a new empty Drive account");
        require(!DriveBackup.configured(context) && server.files.containsKey("b-zero"),"Rejected-only adoption changed identity or deleted a remote file");
    }

    private void testTransport()throws Exception {
        DriveCloudClient client=new DriveCloudClient(TOKEN_A);
        responder=request->json(200,new JSONObject().put("user",new JSONObject().put("emailAddress",EMAIL_A).put("displayName","Fixture A").put("permissionId","fixture-permission-a")));
        DriveCloudClient.Profile profile=client.profile();
        require(EMAIL_A.equals(profile.email) && "fixture-permission-a".equals(profile.permissionId),"Drive account identity was not parsed");
        require(last().getURL().getPath().equals("/drive/v3/about") && query(last().getURL(),"fields").contains("permissionId"),"Account identity uses the wrong Drive resource or missing stable identity");
        assertPrivateRequest(last(),TOKEN_A);
        responder=request->json(200,new JSONObject().put("user",new JSONObject().put("emailAddress",EMAIL_A)));
        reject(()->client.profile(),"Incomplete account identity was accepted");

        JSONObject older=metadata("older",1700000000000L,"committed",512);
        JSONObject newer=metadata("newer",1700000002000L,"committed",512);
        JSONObject pending=metadata("pending",1700000003000L,"pending",512);
        JSONObject otherApp=metadata("other-app",1700000004000L,"committed",512);otherApp.getJSONObject("appProperties").put("app","unrelated");
        JSONObject wrongMime=metadata("wrong-mime",1700000004000L,"committed",512).put("mimeType","text/plain");
        JSONObject huge=metadata("too-large",1700000004000L,"committed",BackupArchive.MAX_BYTES+1L);
        responder=request->{String page=query(request.getURL(),"pageToken");return json(200,page.isEmpty()?new JSONObject().put("nextPageToken","fixture-page-two").put("files",new JSONArray().put(older)):new JSONObject().put("files",new JSONArray().put(newer).put(older)));};
        int before=requests.size();List<DriveCloudClient.BackupFile> files=client.list();
        require(requests.size()==before+2,"Discovery did not follow Drive pagination");
        require(files.size()==2 && files.get(0).id.equals("newer") && files.get(1).id.equals("older"),"Discovery did not deduplicate pages or sort newest first");
        for(int i=before;i<requests.size();i++){
            MockConnection request=requests.get(i);assertPrivateRequest(request,TOKEN_A);
            require("appDataFolder".equals(query(request.getURL(),"spaces")),"Discovery searched outside appDataFolder");
            require(query(request.getURL(),"q").contains("committed"),"Discovery query included incomplete backup generations");
        }
        for(JSONObject invalid:new JSONObject[]{pending,otherApp,wrongMime,huge}){
            responder=request->json(200,new JSONObject().put("files",new JSONArray().put(invalid)));
            reject(()->client.list(),"Discovery accepted unfinished/unmanaged/invalid metadata despite the managed-file query");
        }
        responder=request->json(200,new JSONObject().put("incompleteSearch",true).put("files",new JSONArray().put(older)));
        reject(()->client.list(),"Incomplete Drive discovery was treated as safe empty/complete history");
        responder=request->json(200,new JSONObject().put("nextPageToken","same-token").put("files",new JSONArray().put(older)));
        reject(()->client.list(),"A pagination loop was not bounded");

        byte[] sample=BackupArchive.encode(validPayload("Recovered words"),PASSWORD);
        DriveCloudClient.BackupFile candidate=new DriveCloudClient.BackupFile("candidate",FILE_NAME,"2023-11-14T22:13:20.000Z",sample.length);
        responder=request->Response.bytes(200,sample);
        require(Arrays.equals(sample,client.download(candidate)),"Drive download changed encrypted archive bytes");
        require("media".equals(query(last().getURL(),"alt")),"Archive download did not request binary media");assertPrivateRequest(last(),TOKEN_A);
        responder=request->Response.bytes(302,new byte[0]).header("Location","https://example.invalid/token-recipient");
        before=requests.size();reject(()->client.download(candidate),"Drive redirect was followed or accepted");
        require(requests.size()==before+1 && !last().getInstanceFollowRedirects(),"Bearer request permits automatic redirect forwarding");
        Response oversized=Response.stream(200,BackupArchive.MAX_BYTES+1L,-1);
        responder=request->oversized;reject(()->client.download(candidate),"Oversized chunked media was accepted");
        require(oversized.consumed<=BackupArchive.MAX_BYTES+65536L,"Oversized download was consumed without an early bound");
        responder=request->Response.stream(200,64,BackupArchive.MAX_BYTES+1L);
        reject(()->client.download(candidate),"Oversized declared Content-Length was accepted");
        expectHttp(client,candidate,401,false);expectHttp(client,candidate,429,true);expectHttp(client,candidate,503,true);
        for(String reason:new String[]{"insufficientPermissions","accessNotConfigured","rateLimitExceeded"}){
            responder=request->json(403,new JSONObject().put("error",new JSONObject().put("message","private "+TOKEN_A).put("errors",new JSONArray().put(new JSONObject().put("reason",reason)))));
            Exception failure=reject(()->client.download(candidate),"Drive 403 reason was accepted");
            require(failure instanceof DriveCloudClient.HttpFailure,"Drive 403 lost its status");DriveCloudClient.HttpFailure denied=(DriveCloudClient.HttpFailure)failure;
            require(denied.retryable==reason.equals("rateLimitExceeded") && denied.requiresReconnect==reason.equals("insufficientPermissions"),"Permission/setup/rate-limit 403 has incorrect recovery classification");
            require(!failure.getMessage().contains(TOKEN_A) && !failure.getMessage().contains(reason),"Drive 403 forwarded a raw private body or reason");
        }

        FakeServer server=new FakeServer();responder=server;
        DriveCloudClient.BackupFile upload=client.upload(sample);
        require(server.files.containsKey(upload.id) && "pending".equals(server.files.get(upload.id).metadata.getJSONObject("appProperties").getString("state")),"Uploaded data was exposed as committed before acknowledgement");
        require(Arrays.equals(sample,server.files.get(upload.id).bytes),"Multipart upload changed archive bytes");
        MockConnection uploadRequest=last();assertPrivateRequest(uploadRequest,TOKEN_A);
        require(uploadRequest.getURL().getPath().equals("/upload/drive/v3/files") && "multipart".equals(query(uploadRequest.getURL(),"uploadType")),"Upload used the wrong Drive endpoint or mode");
        DriveCloudClient.BackupFile committed=client.commit(upload.id);
        require(committed.id.equals(upload.id) && "committed".equals(server.files.get(upload.id).metadata.getJSONObject("appProperties").getString("state")),"Upload generation was not committed");
        require(client.list().size()==1,"Committed generation is not discoverable");
        int deletions=server.deletes;JSONObject foreign=metadata("foreign",1700000000000L,"committed",sample.length);foreign.getJSONObject("appProperties").put("app","other");server.files.put("foreign",new RemoteFile(TOKEN_A,foreign,sample));
        reject(()->client.delete("foreign"),"Delete accepted an unmanaged file");require(server.deletes==deletions && server.files.containsKey("foreign"),"Unmanaged data was deleted");
        reject(()->client.commit("foreign"),"Commit accepted an unmanaged file");
        client.delete(upload.id);require(server.deletes==deletions+1 && !server.files.containsKey(upload.id),"Managed pending/committed generation was not deleted through the guarded path");
        int outgoing=requests.size();reject(()->client.upload(new byte[64]),"Upload accepted a non-VoiceFlow archive");reject(()->client.upload(new byte[BackupArchive.MAX_BYTES+1]),"Upload accepted an oversized archive");
        require(requests.size()==outgoing,"Invalid upload made a remote request");
    }

    private void expectHttp(DriveCloudClient client,DriveCloudClient.BackupFile file,int status,boolean retry)throws Exception {
        responder=request->Response.bytes(status,("{\"error\":{\"message\":\"private server response "+TOKEN_A+"\"}}").getBytes(StandardCharsets.UTF_8));
        Exception failure=reject(()->client.download(file),"HTTP "+status+" was accepted");
        require(failure instanceof DriveCloudClient.HttpFailure,"HTTP error lost its typed status");
        DriveCloudClient.HttpFailure http=(DriveCloudClient.HttpFailure)failure;
        require(http.status==status && http.retryable==retry,"HTTP "+status+" has the wrong retry classification");
        require(!failure.getMessage().contains(TOKEN_A) && !failure.getMessage().contains("private server response"),"Remote error exposed account-sensitive response content");
    }

    private void testLifecycle()throws Exception {
        SharedPreferences prefs=Store.prefs(context);prefs.edit().clear().putString("history","[]").putString("dictionary","jewel=Juel").putString("engine","cloud").putString("bubble_size","large").putBoolean("save_history",true).putString("backup_frequency","weekly").commit();
        BackupSecrets.clear(context);Vault.set(context,"fixture-current-api");MainActivity.currentDraft="fixture retained draft";
        FakeServer server=new FakeServer();responder=server;
        Map<String,?> untouched=prefs.getAll(),noSecret=BackupSecrets.snapshot(context);
        DriveBackup.Connection empty=DriveBackup.discover(context,TOKEN_A);
        require(empty.files.isEmpty() && prefs.getAll().equals(untouched) && BackupSecrets.snapshot(context).equals(noSecret),"Discovery modified data, credentials or schedule before a user choice");
        DriveBackup.connect(context,empty);
        require(DriveBackup.configured(context) && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context) && "off".equals(Store.get(context,"backup_frequency","")),"Connection silently enabled backup or retained a portable schedule");
        DriveBackup.configurePassword(context,empty,PASSWORD,null);
        require(DriveBackup.passwordReady(context) && EMAIL_A.equals(DriveBackup.accountEmail(context)) && !DriveBackup.automaticEnabled(context),"New encrypted backup setup did not pin account identity with automation off");
        Store.save(context,"fixture source words","Fixture saved words",20000);
        JSONObject portable=BackupData.snapshot(context);String plain=portable.toString();
        for(String excluded:new String[]{TOKEN_A,EMAIL_A,"fixture-permission-a","backup_account","backup_remote_id","backup_reconnect","backup_cloud_ready","voiceflow_backup_password","api_key","fixture-current-api"})require(!plain.contains(excluded),"Portable archive leaked a credential or device-bound cloud state: "+excluded);
        DriveBackup.backup(context,TOKEN_A);
        DriveBackup.Connection saved=DriveBackup.discover(context,TOKEN_A);
        require(saved.files.size()==1 && DriveBackup.lastSuccess(context)>0,"Successful backup was not committed or recorded");
        DriveCloudClient.BackupFile previous=saved.files.get(0);byte[] previousBytes=server.files.get(previous.id).bytes.clone();long success=DriveBackup.lastSuccess(context);
        require(!new String(previousBytes,StandardCharsets.ISO_8859_1).contains("Fixture saved words") && !new String(previousBytes,StandardCharsets.ISO_8859_1).contains("fixture-current-api"),"Cloud archive exposes plaintext user data");
        JSONObject remote=DriveBackup.read(context,saved,previous,PASSWORD);
        require(remote.getJSONArray("history").getJSONObject(0).getString("text").equals("Fixture saved words") && !remote.has("api_key"),"Encrypted cloud read lost history or imported a provider credential");

        if(!followUp){
        untouched=prefs.getAll();Map<String,?> secret=BackupSecrets.snapshot(context);
        reject(()->DriveBackup.configurePassword(context,saved,WRONG_PASSWORD,previous),"Wrong remote password was adopted");
        reject(()->DriveBackup.configurePassword(context,saved,PASSWORD,null),"Existing cloud history was bypassed when creating new backup configuration");
        reject(()->DriveBackup.read(context,saved,previous,WRONG_PASSWORD),"Wrong password restored remote history");
        require(prefs.getAll().equals(untouched) && BackupSecrets.snapshot(context).equals(secret),"Failed adoption/read changed local data, account or saved password");
        Context failConfiguration=new CommitFailureContext(context,1);
        reject(()->DriveBackup.configurePassword(failConfiguration,saved,PASSWORD,previous),"Failed configuration commit was accepted");
        require(prefs.getAll().equals(untouched) && BackupSecrets.snapshot(context).equals(secret),"Failed configuration did not roll back identity and password together");
        server.overridePermission="different-permission";
        reject(()->DriveBackup.configurePassword(context,saved,PASSWORD,previous),"A stale connection changed to another Drive identity");server.overridePermission=null;
        require(prefs.getAll().equals(untouched) && BackupSecrets.snapshot(context).equals(secret),"Stale-account callback mutated configuration");

        prefs.edit().putString("dictionary","temporary=Temporary").putString("history","[]").putString("bubble_size","small").putString("backup_frequency","daily").putBoolean("backup_wifi",true).commit();Vault.set(context,"fixture-temporary-api");
        String beforeRestore=userData();Map<String,?> beforeRestorePrefs=prefs.getAll(),beforeRestoreSecrets=BackupSecrets.snapshot(context);
        runOnMainSync(()->CaptureActivity.visible=true);
        try{reject(()->DriveBackup.restore(context,saved,PASSWORD,previous,remote),"Active capture was replaced by a restore");}
        finally{runOnMainSync(()->CaptureActivity.visible=false);}
        require(beforeRestore.equals(userData()) && prefs.getAll().equals(beforeRestorePrefs) && BackupSecrets.snapshot(context).equals(beforeRestoreSecrets),"Blocked active-dictation restore changed data or connection");
        Context failData=new CommitFailureContext(context,2);
        reject(()->DriveBackup.restore(failData,saved,PASSWORD,previous,remote),"Failed data commit was accepted as a restore");
        require(beforeRestore.equals(userData()) && prefs.getAll().equals(beforeRestorePrefs) && BackupSecrets.snapshot(context).equals(beforeRestoreSecrets),"Restore failure did not roll back portable data, API key and cloud configuration");
        JSONObject changed=new JSONObject(remote.toString());changed.getJSONObject("settings").put("dictionary","injected=Injected");
        reject(()->DriveBackup.restore(context,saved,PASSWORD,previous,changed),"Restore accepted data different from the confirmed remote archive");
        require(beforeRestore.equals(userData()),"Changed restore selection mutated data");
        DriveBackup.restore(context,saved,PASSWORD,previous,remote);
        require(Store.get(context,"dictionary","").equals("jewel=Juel") && Store.history(context).length()==1 && Vault.get(context).equals("fixture-temporary-api"),"Valid cloud restore lost dictionary/history or replaced this phone's provider credential");
        require(EMAIL_A.equals(DriveBackup.accountEmail(context)) && "daily".equals(Store.get(context,"backup_frequency","")),"Restore replaced account identity or a previously opted-in local cadence");
        JobInfo job=backupJob();require(job!=null && job.isPersisted() && job.getIntervalMillis()==86400000L && job.getNetworkType()==JobInfo.NETWORK_TYPE_UNMETERED,"Daily Wi-Fi schedule has incorrect constraints");
        prefs.edit().putString("backup_frequency","weekly").putBoolean("backup_wifi",false).commit();DriveBackup.schedule(context);job=backupJob();
        require(job!=null && job.getIntervalMillis()==604800000L && job.getNetworkType()==JobInfo.NETWORK_TYPE_ANY,"Weekly any-network schedule has incorrect constraints");
        prefs.edit().putString("backup_frequency","off").commit();DriveBackup.schedule(context);require(backupJob()==null,"Off left a backup job scheduled");

        prefs.edit().putBoolean("backup_wifi",true).commit();DriveBackup.restore(context,validPayload("Legacy recovered words"));
        require("off".equals(Store.get(context,"backup_frequency","")) && Store.bool(context,"backup_wifi",false) && backupJob()==null,"Legacy archive imported an automatic cadence or changed local network consent");
        }else{
            prefs.edit().putString("backup_frequency","off").putBoolean("backup_wifi",true).commit();
            DriveBackup.restore(context,validPayload("Legacy recovered words"));
            progress("Follow-up: repeating remote failure preservation before busy/interruption");
        }

        String beforeFailure=userData();int deletions=server.deletes;server.failUpload=true;
        Exception transientFailure=reject(()->DriveBackup.backup(context,TOKEN_A),"Failed Drive upload was reported as successful");server.failUpload=false;
        require(DriveBackup.retryable(transientFailure) && !DriveBackup.needsReconnect(context),"Transient Drive failure demanded reconnect or suppressed retry");
        require(Arrays.equals(previousBytes,server.files.get(previous.id).bytes) && server.deletes==deletions && DriveBackup.lastSuccess(context)==success && beforeFailure.equals(userData()),"Failed upload replaced previous remote generation, success timestamp or user data");
        server.failExistingMedia=true;Exception mediaUnavailable=reject(()->DriveBackup.backup(context,TOKEN_A),"Failed latest-archive download was accepted");server.failExistingMedia=false;
        require(DriveBackup.retryable(mediaUnavailable) && DriveBackup.passwordReady(context) && !DriveBackup.needsReconnect(context),"A network failure while checking previous archive revoked password readiness or became permanent");
        require(Arrays.equals(previousBytes,server.files.get(previous.id).bytes) && DriveBackup.lastSuccess(context)==success && beforeFailure.equals(userData()),"Failed latest-archive download changed data or previous backup");
        server.failCommit=true;reject(()->DriveBackup.backup(context,TOKEN_A),"Failed remote commit was reported as successful");server.failCommit=false;
        require(server.files.containsKey(previous.id) && Arrays.equals(previousBytes,server.files.get(previous.id).bytes) && DriveBackup.lastSuccess(context)==success,"Failed commit destroyed a previous acknowledged backup");
        require(DriveBackup.discover(context,TOKEN_A).files.size()==1,"Unacknowledged remote data was offered for restore");
        server.corruptNewMedia=true;reject(()->DriveBackup.backup(context,TOKEN_A),"Unverified remote ciphertext was committed");server.corruptNewMedia=false;
        require(server.files.containsKey(previous.id) && DriveBackup.lastSuccess(context)==success && beforeFailure.equals(userData()),"Corrupt new media damaged previous backup or local data");

        prefs.edit().putString("backup_frequency","daily").commit();DriveBackup.schedule(context);
        String beforeCancellation=userData();Map<String,?> cancellationSecret=BackupSecrets.snapshot(context);int uploadCount=server.uploads;
        Exception validationCancelled;boolean interruptObserved;
        server.interruptLatestClose=true;
        try{validationCancelled=reject(()->DriveBackup.backup(context,TOKEN_A),"Latest-archive validation ignored cancellation");interruptObserved=Thread.currentThread().isInterrupted();}
        finally{server.interruptLatestClose=false;Thread.interrupted();}
        require(interruptObserved && validationCancelled instanceof java.io.InterruptedIOException && !DriveBackup.retryable(validationCancelled),"Latest-archive validation converted cancellation into a password error or retry");
        require(DriveBackup.passwordReady(context) && DriveBackup.automaticEnabled(context) && "daily".equals(Store.get(context,"backup_frequency","")) && backupJob()!=null && BackupSecrets.snapshot(context).equals(cancellationSecret),"Cancelled latest-password validation revoked readiness, consent, schedule or password");
        require(server.uploads==uploadCount && Arrays.equals(previousBytes,server.files.get(previous.id).bytes) && DriveBackup.lastSuccess(context)==success && beforeCancellation.equals(userData()),"Cancelled latest-password validation changed remote backup or local user data");
        BackupSecrets.set(context,WRONG_PASSWORD);
        Exception wrongLatest=reject(()->DriveBackup.backup(context,TOKEN_A),"An incorrect remembered password unlocked the latest archive");
        require(!DriveBackup.retryable(wrongLatest) && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context) && backupJob()==null && server.uploads==uploadCount,"Incorrect latest password was treated like transient cancellation or allowed an upload");
        BackupSecrets.restore(context,cancellationSecret);prefs.edit().putBoolean("backup_cloud_ready",true).putString("backup_frequency","off").commit();DriveBackup.schedule(context);

        server.uploadEntered=new java.util.concurrent.CountDownLatch(1);server.uploadRelease=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> interrupted=new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker=new Thread(()->{try{DriveBackup.backup(context,TOKEN_A);}catch(Throwable failure){interrupted.set(failure);}},"Drive-fixture-interruption");worker.start();
        try {
            awaitUpload(server.uploadEntered,worker,interrupted);
            long started=android.os.SystemClock.elapsedRealtime();reject(()->DriveBackup.configurePassword(context,saved,PASSWORD,previous),"Configuration ran during an upload");reject(()->DriveBackup.disconnect(context),"Disconnect ran during an upload");reject(()->DriveBackup.restore(context,remote),"Restore ran during an upload");
            require(android.os.SystemClock.elapsedRealtime()-started<2000,"Busy cloud operations blocked instead of reporting the active operation");
            worker.interrupt();server.uploadRelease.countDown();worker.join(15000);
            require(!worker.isAlive() && interrupted.get()!=null,"Interrupted upload was reported as successful");
            require(server.files.containsKey(previous.id) && Arrays.equals(previousBytes,server.files.get(previous.id).bytes) && DriveBackup.lastSuccess(context)==success && beforeFailure.equals(userData()),"Interrupted upload changed previous backup or local user data");
        } finally {server.uploadRelease.countDown();worker.interrupt();worker.join(15000);server.uploadEntered=null;server.uploadRelease=null;}

        progress("Testing reconnect, account isolation, disconnect, and older-archive recovery");
        prefs.edit().putString("backup_frequency","daily").commit();DriveBackup.schedule(context);
        server.forcedStatus=401;Exception expired=reject(()->DriveBackup.backup(context,TOKEN_A),"Expired Drive authorization was accepted");server.forcedStatus=0;
        require(!DriveBackup.retryable(expired) && DriveBackup.needsReconnect(context) && backupJob()==null,"Expired authorization did not pause automation and require foreground reconnect");
        DriveBackup.connect(context,DriveBackup.discover(context,TOKEN_A));require(!DriveBackup.needsReconnect(context) && backupJob()!=null,"Reconnect lost account/password/cadence or did not resume opt-in automation");
        server.forcedStatus=403;Exception denied=reject(()->DriveBackup.backup(context,TOKEN_A),"Denied Drive authorization was accepted");server.forcedStatus=0;
        require(!DriveBackup.retryable(denied) && DriveBackup.needsReconnect(context) && backupJob()==null,"Revoked Drive permission did not pause automation for reconnect");
        DriveBackup.connect(context,DriveBackup.discover(context,TOKEN_A));
        int uploadsBefore=server.uploads;
        reject(()->DriveBackup.backup(context,TOKEN_B),"Backup accepted an access token for a different Google account");
        require(server.uploads==uploadsBefore && EMAIL_A.equals(DriveBackup.accountEmail(context)) && server.files.containsKey(previous.id),"Wrong-account backup uploaded or changed the pinned account");
        DriveBackup.Connection another=DriveBackup.discover(context,TOKEN_B);
        DriveBackup.Connection otherReconnect=another;reject(()->DriveBackup.connect(context,otherReconnect),"Reconnect switched the configured account without disconnect");
        require(EMAIL_A.equals(DriveBackup.accountEmail(context)),"Wrong-account reconnect changed identity");
        int remoteCount=server.files.size(),deletesBefore=server.deletes;String history=Store.get(context,"history","");
        DriveBackup.disconnect(context);
        require(!DriveBackup.configured(context) && !DriveBackup.passwordReady(context) && backupJob()==null && BackupSecrets.snapshot(context).isEmpty(),"Disconnect retained an account/password/schedule");
        require(server.files.size()==remoteCount && server.deletes==deletesBefore && history.equals(Store.get(context,"history","")),"Disconnect deleted remote backup or local history");

        byte[] otherBytes=BackupArchive.encode(validPayload("Another account words"),WRONG_PASSWORD);server.files.put("account-b-existing",new RemoteFile(TOKEN_B,metadata("account-b-existing",1700000010000L,"committed",otherBytes.length),otherBytes));
        another=DriveBackup.discover(context,TOKEN_B);DriveBackup.Connection otherSession=another;DriveCloudClient.BackupFile otherFile=another.files.get(0);
        untouched=prefs.getAll();noSecret=BackupSecrets.snapshot(context);
        reject(()->DriveBackup.configurePassword(context,otherSession,PASSWORD,otherFile),"Different account's backup adopted the old account's password");
        reject(()->DriveBackup.read(context,otherSession,previous,WRONG_PASSWORD),"Restore selection from another account was accepted");
        require(prefs.getAll().equals(untouched) && BackupSecrets.snapshot(context).equals(noSecret),"Cross-account/password selection changed local data or account");
        JSONObject otherData=DriveBackup.read(context,otherSession,otherFile,WRONG_PASSWORD);DriveBackup.restore(context,otherSession,WRONG_PASSWORD,otherFile,otherData);
        require(EMAIL_B.equals(DriveBackup.accountEmail(context)) && Store.history(context).getJSONObject(0).getString("text").equals("Another account words") && !DriveBackup.automaticEnabled(context) && "off".equals(Store.get(context,"backup_frequency","")),"New-phone restore did not adopt account/history with automation off");
        byte[] newerLocked=BackupArchive.encode(validPayload("Latest locked words"),PASSWORD);server.files.put("account-b-locked",new RemoteFile(TOKEN_B,metadata("account-b-locked",1700000020000L,"committed",newerLocked.length),newerLocked));
        DriveBackup.Connection recoverOlder=DriveBackup.discover(context,TOKEN_B);JSONObject olderData=DriveBackup.read(context,recoverOlder,otherFile,WRONG_PASSWORD);
        DriveBackup.restore(context,recoverOlder,WRONG_PASSWORD,otherFile,olderData);
        require(Store.history(context).getJSONObject(0).getString("text").equals("Another account words") && !DriveBackup.passwordReady(context) && !DriveBackup.automaticEnabled(context) && backupJob()==null,"Older valid archive could not recover safely while a newer archive needs another password");
        reject(()->DriveBackup.configurePassword(context,recoverOlder,WRONG_PASSWORD,otherFile),"Older archive's password enabled uploads despite a locked latest generation");
        BackupSecrets.clear(context);prefs.edit().putString("backup_frequency","daily").commit();DriveBackup.schedule(context);
        require(!DriveBackup.automaticEnabled(context) && backupJob()==null,"Lost device password left automation enabled");
        Exception missingSecret=reject(()->DriveBackup.backup(context,TOKEN_B),"Backup proceeded without the remembered password");
        require(!DriveBackup.retryable(missingSecret),"Missing password was treated as a transient network error");
        require(!DriveBackup.retryable(new java.io.InterruptedIOException("cancelled")) && !DriveBackup.retryable(new DriveCloudClient.ProtocolFailure("bad metadata")),"Cancellation or malformed protocol was classified for recurring retry");
    }

    /** Crypto still uses the production cost; slow software emulation gets a bounded diagnostic wait. */
    private void awaitUpload(java.util.concurrent.CountDownLatch entered,Thread worker,java.util.concurrent.atomic.AtomicReference<Throwable> failure)throws Exception {
        long start=android.os.SystemClock.elapsedRealtime();boolean diagnosed=false;
        while(android.os.SystemClock.elapsedRealtime()-start<180000){
            if(entered.await(1,java.util.concurrent.TimeUnit.SECONDS)){checks++;return;}
            Throwable cause=failure.get();
            if(cause!=null || !worker.isAlive())throw new Exception("Backup worker ended before remote upload; state="+worker.getState(),cause);
            if(!diagnosed && android.os.SystemClock.elapsedRealtime()-start>=60000){diagnosed=true;progress("Upload wait exceeded former 60-second limit; "+workerDiagnostic(worker));}
        }
        throw new Exception("Backup did not reach remote upload within 180 seconds; "+workerDiagnostic(worker),failure.get());
    }
    private static String workerDiagnostic(Thread worker){StringBuilder text=new StringBuilder("worker state=").append(worker.getState());for(StackTraceElement frame:worker.getStackTrace())text.append("\n  at ").append(frame);return text.toString();}

    private String userData()throws Exception {JSONObject data=BackupData.snapshot(context);data.remove("created_at");return data.toString();}
    private JobInfo backupJob(){return ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID);}
    /** Pending JobInfo stays real, while unavailable network prevents live Google-auth jobs racing assertions. */
    private void isolateJobNetwork()throws Exception {
        wifiWasOn="1".equals(shell("settings get global wifi_on").trim());dataWasOn="1".equals(shell("settings get global mobile_data").trim());networkIsolated=true;
        shell("svc wifi disable");shell("svc data disable");
        android.app.UiAutomation automation=getUiAutomation();if(android.os.Build.VERSION.SDK_INT>=29)automation.adoptShellPermissionIdentity("android.permission.ACCESS_NETWORK_STATE");
        try {
            android.net.ConnectivityManager manager=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);long until=android.os.SystemClock.elapsedRealtime()+20000;boolean internet;
            do{internet=false;for(android.net.Network network:manager.getAllNetworks()){android.net.NetworkCapabilities caps=manager.getNetworkCapabilities(network);if(caps!=null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET))internet=true;}if(!internet)break;Thread.sleep(100);}while(android.os.SystemClock.elapsedRealtime()<until);
            require(!internet,"Disposable test device still has an Internet network; scheduled jobs could invoke real Google authorization");
        } finally {if(android.os.Build.VERSION.SDK_INT>=29)automation.dropShellPermissionIdentity();}
    }
    private void restoreJobNetwork()throws Exception {if(!networkIsolated)return;if(wifiWasOn)shell("svc wifi enable");if(dataWasOn)shell("svc data enable");networkIsolated=false;}
    private String shell(String command)throws Exception {try(android.os.ParcelFileDescriptor.AutoCloseInputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[256];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);return out.toString("UTF-8");}}
    /** Simulates Android's commit=false after in-memory mutation; subsequent rollback writes succeed. */
    private static final class CommitFailureContext extends android.content.ContextWrapper {
        int remaining;final SharedPreferences wrapped;
        CommitFailureContext(Context base,int failingCommit){super(base);remaining=failingCommit;SharedPreferences real=Store.prefs(base);
            wrapped=(SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                Object result=invoke(real,method,args);
                if(method.getName().equals("edit"))return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(edit,operation,values)->{
                    Object changed=invoke(result,operation,values);if(operation.getName().equals("commit") && --remaining==0)return false;return changed==result?edit:changed;
                });return result;
            });
        }
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return name.equals("voiceflow")?wrapped:super.getSharedPreferences(name,mode);}
        private static Object invoke(Object target,java.lang.reflect.Method method,Object[] args)throws Throwable {try{return method.invoke(target,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}}
    }

    private static JSONObject validPayload(String text)throws Exception {
        return new JSONObject().put("format","voiceflow-backup").put("schema",1).put("created_at",1700000000000L)
                .put("settings",new JSONObject().put("dictionary","jewel=Juel").put("engine","cloud").put("bubble_size","large").put("backup_frequency","weekly").put("backup_wifi",false))
                .put("history",new JSONArray().put(new JSONObject().put("id",1700000000000L).put("text",text).put("raw",text).put("duration",30000L).put("words",2))).put("api_key","fixture-portable-only").put("draft","recoverable fixture draft");
    }
    private static JSONObject metadata(String id,long when,String state,long size)throws Exception {
        String timestamp=java.time.Instant.ofEpochMilli(when).toString();
        return new JSONObject().put("id",id).put("name",FILE_NAME).put("mimeType","application/octet-stream").put("size",String.valueOf(size)).put("modifiedTime",timestamp).put("trashed",false)
                .put("parents",new JSONArray().put("appDataFolder")).put("appProperties",new JSONObject().put("app","voiceflow").put("schema","1").put("encrypted","vfb").put("state",state));
    }
    private void assertPrivateRequest(MockConnection request,String token)throws Exception {
        require(("Bearer "+token).equals(request.getRequestProperty("Authorization")),"Drive request omitted or changed its account token");
        require(!request.getURL().toString().contains(token),"Drive token was sent in a URL");
        require(!request.getInstanceFollowRedirects(),"Drive request permits redirects");
        require(request.getConnectTimeout()>0 && request.getReadTimeout()>0,"Drive request has unbounded timeouts");
    }
    private static void requireUrl(URL url)throws IOException {
        if(!"https".equals(url.getProtocol()) || !"www.googleapis.com".equals(url.getHost()) || !(url.getPath().startsWith("/drive/v3/") || url.getPath().startsWith("/upload/drive/v3/")))throw new IOException("Fixture blocked a non-Drive network endpoint");
    }
    private static String query(URL url,String key)throws IOException {
        String query=url.getQuery();if(query==null)return "";
        for(String part:query.split("&")){String[] pair=part.split("=",2);if(URLDecoder.decode(pair[0],"UTF-8").equals(key))return pair.length==2?URLDecoder.decode(pair[1],"UTF-8"):"";}
        return "";
    }
    private MockConnection last(){return requests.get(requests.size()-1);}
    private Exception reject(Attempt task,String message)throws Exception {try{task.run();}catch(Exception expected){checks++;return expected;}throw new Exception(message);}
    private void require(boolean valid,String message)throws Exception {checks++;if(!valid)throw new Exception(message);}
    private void progress(String text){Bundle state=new Bundle();state.putString("phase",text);sendStatus(0,state);}
    private interface Attempt{void run()throws Exception;}
    private interface Responder{Response respond(MockConnection request)throws Exception;}
    private static Response json(int status,JSONObject body){return Response.bytes(status,body.toString().getBytes(StandardCharsets.UTF_8));}

    private final class MockConnection extends HttpsURLConnection {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();Response response;boolean disconnected;
        MockConnection(URL url){super(url);}
        private Response response()throws IOException {if(response==null)try{if(responder==null)throw new IOException("Fixture response was not configured");response=responder.respond(this);}catch(Exception error){throw error instanceof IOException?(IOException)error:new IOException("Fixture server failed",error);}return response;}
        @Override public void connect(){} @Override public void disconnect(){disconnected=true;} @Override public boolean usingProxy(){return false;}
        @Override public String getCipherSuite(){return "fixture";} @Override public Certificate[] getLocalCertificates(){return null;} @Override public Certificate[] getServerCertificates(){return null;}
        @Override public OutputStream getOutputStream(){return out;} @Override public int getResponseCode()throws IOException{return response().status;}
        @Override public void setRequestMethod(String method){this.method=method;}
        @Override public long getContentLengthLong(){try{return response().declared;}catch(IOException error){return -1;}}
        @Override public int getContentLength(){long value=getContentLengthLong();return value>Integer.MAX_VALUE?-1:(int)value;}
        @Override public String getHeaderField(String name){try{return response().headers.get(name);}catch(IOException error){return null;}}
        @Override public InputStream getInputStream()throws IOException{return response().stream();}
        @Override public InputStream getErrorStream(){try{return response().stream();}catch(IOException error){return null;}}
    }
    private static final class Response {
        final int status;final byte[] bytes;final long length,declared;final Map<String,String> headers=new LinkedHashMap<>();long consumed;Runnable closeAction;
        Response(int status,byte[] bytes,long length,long declared){this.status=status;this.bytes=bytes;this.length=length;this.declared=declared;}
        static Response bytes(int status,byte[] body){return new Response(status,body,body.length,body.length);}
        static Response stream(int status,long length,long declared){return new Response(status,null,length,declared);}
        Response header(String key,String value){headers.put(key,value);return this;}
        Response onClose(Runnable action){closeAction=action;return this;}
        InputStream stream(){return new InputStream(){long position;boolean closed;public int read(){if(position>=length)return -1;consumed++;int value=bytes==null?'X':bytes[(int)position]&255;position++;return value;}public int read(byte[] target,int offset,int count){if(position>=length)return -1;int available=(int)Math.min(count,length-position);if(bytes==null)Arrays.fill(target,offset,offset+available,(byte)'X');else System.arraycopy(bytes,(int)position,target,offset,available);position+=available;consumed+=available;return available;}public void close(){if(!closed){closed=true;if(closeAction!=null)closeAction.run();}}};}
    }
    private static final class RemoteFile {
        final String token;final JSONObject metadata;final byte[] bytes;
        RemoteFile(String token,JSONObject metadata,byte[] bytes){this.token=token;this.metadata=metadata;this.bytes=bytes.clone();}
    }
    private final class FakeServer implements Responder {
        final Map<String,RemoteFile> files=new LinkedHashMap<>();int uploads,deletes,forcedStatus;boolean failUpload,failCommit,corruptNewMedia,failExistingMedia,interruptLatestClose;String overridePermission;
        java.util.concurrent.CountDownLatch uploadEntered,uploadRelease;
        @Override public Response respond(MockConnection request)throws Exception {
            String auth=request.getRequestProperty("Authorization"),token=auth==null?"":auth.replace("Bearer ","");
            if(!TOKEN_A.equals(token) && !TOKEN_B.equals(token))return Response.bytes(401,new byte[0]);
            if(forcedStatus!=0)return forcedStatus==403?json(403,new JSONObject().put("error",new JSONObject().put("errors",new JSONArray().put(new JSONObject().put("reason","insufficientPermissions"))))):Response.bytes(forcedStatus,new byte[0]);
            String path=request.getURL().getPath(),method=request.getRequestMethod();
            if(path.equals("/drive/v3/about"))return json(200,new JSONObject().put("user",new JSONObject().put("emailAddress",TOKEN_A.equals(token)?EMAIL_A:EMAIL_B).put("displayName",TOKEN_A.equals(token)?"Fixture A":"Fixture B").put("permissionId",overridePermission!=null?overridePermission:TOKEN_A.equals(token)?"fixture-permission-a":"fixture-permission-b")));
            if(path.equals("/drive/v3/files")){
                JSONArray listed=new JSONArray();for(RemoteFile file:files.values())if(token.equals(file.token) && "committed".equals(file.metadata.getJSONObject("appProperties").getString("state")))listed.put(file.metadata);
                return json(200,new JSONObject().put("files",listed));
            }
            if(path.equals("/upload/drive/v3/files") && method.equals("POST")){
                if(failUpload)return Response.bytes(503,new byte[0]);
                if(uploadEntered!=null){uploadEntered.countDown();try{if(!uploadRelease.await(90,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Fixture upload latch expired");}catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException("Fixture upload was interrupted");}}
                byte[] body=request.out.toByteArray();String text=new String(body,StandardCharsets.ISO_8859_1);int begin=text.indexOf('{');JSONObject descriptor=new JSONObject(firstJson(text,begin));
                int binary=indexOf(body,MAGIC),end=text.lastIndexOf("\r\n--");if(binary<0 || end<binary)throw new IOException("Fixture multipart archive missing");
                byte[] archive=Arrays.copyOfRange(body,binary,end);String id="uploaded-"+(++uploads);JSONObject meta=metadata(id,1700000000000L+uploads*1000L,"pending",archive.length).put("name",descriptor.getString("name")).put("appProperties",descriptor.getJSONObject("appProperties"));
                require(descriptor.getJSONArray("parents").getString(0).equals("appDataFolder"),"Upload omitted hidden app-data parent");files.put(id,new RemoteFile(token,meta,archive));return json(200,meta);
            }
            String id=path.substring(path.lastIndexOf('/')+1);RemoteFile file=files.get(id);
            if(file==null || !token.equals(file.token))return Response.bytes(404,new byte[0]);
            if(method.equals("GET")){
                if("media".equals(query(request.getURL(),"alt"))){if(failExistingMedia && "committed".equals(file.metadata.getJSONObject("appProperties").getString("state")))throw new IOException("Fixture network interruption");byte[] body=file.bytes.clone();if(corruptNewMedia && "pending".equals(file.metadata.getJSONObject("appProperties").getString("state")))body[body.length-1]^=1;Response reply=Response.bytes(200,body);if(interruptLatestClose && "committed".equals(file.metadata.getJSONObject("appProperties").getString("state")))reply.onClose(()->Thread.currentThread().interrupt());return reply;}return json(200,file.metadata);
            }
            if(method.equals("PATCH")){
                if(failCommit)return Response.bytes(503,new byte[0]);
                JSONObject patch=new JSONObject(request.out.toString("UTF-8"));JSONObject properties=patch.getJSONObject("appProperties");java.util.Iterator<String> keys=properties.keys();while(keys.hasNext()){String key=keys.next();file.metadata.getJSONObject("appProperties").put(key,properties.get(key));}return json(200,file.metadata);
            }
            if(method.equals("DELETE")){deletes++;files.remove(id);return Response.bytes(204,new byte[0]);}
            throw new IOException("Unhandled fixture method "+method);
        }
    }
    private static int indexOf(byte[] source,byte[] needle){outer:for(int i=0;i<=source.length-needle.length;i++){for(int n=0;n<needle.length;n++)if(source[i+n]!=needle[n])continue outer;return i;}return -1;}
    private static String firstJson(String source,int start)throws IOException {
        int depth=0;boolean quoted=false,escaped=false;for(int i=start;i<source.length();i++){char c=source.charAt(i);if(quoted){if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false;}else if(c=='"')quoted=true;else if(c=='{')depth++;else if(c=='}' && --depth==0)return source.substring(start,i+1);}throw new IOException("Fixture metadata JSON incomplete");
    }
    @SuppressWarnings("unchecked") private static void restorePrefs(SharedPreferences prefs,Map<String,?> saved){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:saved.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);}edit.commit();}
}
