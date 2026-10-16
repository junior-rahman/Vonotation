package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Crypto authenticity and strict portable-schema checks, with no network or real credentials. */
public final class BackupArchiveTest extends Instrumentation {
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart() {
        Bundle result=new Bundle();char[] password="  fixture backup pass  ".toCharArray();
        try {
            Map<String,?> before=Store.prefs(getTargetContext()).getAll();
            JSONObject source=valid();byte[] first=BackupArchive.encode(source,password),second=BackupArchive.encode(source,password);
            require(!Arrays.equals(first,second),"Separate backups reused their encryption randomness");
            require(!new String(first,StandardCharsets.ISO_8859_1).contains("fixture-only-backup-key"),"Archive exposes the key in cleartext");
            JSONObject decoded=BackupArchive.decode(first,password);require(decoded.getString("api_key").equals("fixture-only-backup-key"),"Legacy encrypted archive could not be decoded");
            require(!BackupData.validate(decoded).has("api_key"),"Legacy archive credentials entered the validated restore tree");
            require(decoded.getJSONArray("history").getJSONObject(0).getString("text").equals("আমার কথা 😀"),"Unicode dictation was not preserved");
            JSONObject mixed=valid();JSONArray thoughts=new JSONArray();
            for(int i=0;i<300;i++)thoughts.put(thought(i+1));mixed.put("thoughts",thoughts);
            JSONArray mixedHistory=mixed.getJSONArray("history");for(int i=1;i<100;i++)mixedHistory.put(new JSONObject(mixedHistory.getJSONObject(0).toString()).put("id",1700000000000L+i));
            JSONObject roundTrip=BackupData.validate(BackupArchive.decode(BackupArchive.encode(BackupData.validate(mixed),password),password));
            require(roundTrip.getJSONArray("thoughts").length()==300 && roundTrip.getJSONArray("history").length()==100,"A realistic mixed backup cannot restore its own encrypted snapshot");
            require(roundTrip.getJSONArray("thoughts").getJSONObject(299).getString("raw").equals("A recorded thought 😀"),"Thought source transcript was changed during round-trip");
            String fingerprint=BackupData.fingerprint(mixed);
            JSONObject changedDate=new JSONObject(mixed.toString()).put("created_at",1700000000001L).put("api_key","different-local-credential");
            require(fingerprint.equals(BackupData.fingerprint(changedDate)),"Archive creation time or a legacy credential changed portable freshness");
            JSONObject changedThought=new JSONObject(mixed.toString());changedThought.getJSONArray("thoughts").getJSONObject(0).put("summary","Edited summary");
            require(!fingerprint.equals(BackupData.fingerprint(changedThought)),"Edited Quick Thoughts summary was omitted from freshness");
            JSONObject changedSetting=new JSONObject(mixed.toString());changedSetting.getJSONObject("settings").put("quick_thoughts",true);
            require(!fingerprint.equals(BackupData.fingerprint(changedSetting)),"Quick Thoughts switch was omitted from freshness");
            JSONObject changedDraft=new JSONObject(mixed.toString()).put("draft","Edited draft");
            require(!fingerprint.equals(BackupData.fingerprint(changedDraft)),"Draft was omitted from freshness");
            JSONObject modelData=valid();String[] modelKeys={"notes_transcribe_model","notes_polish_model","translate_transcribe_model","translate_model"};
            for(String key:modelKeys)modelData.getJSONObject("settings").put(key,"fixture-"+key);
            JSONObject modelRoundTrip=BackupData.validate(BackupArchive.decode(BackupArchive.encode(modelData,password),password));
            for(String key:modelKeys){require(modelRoundTrip.getJSONObject("settings").getString(key).equals("fixture-"+key),"A Notes or Translation model choice was lost");invalid(data->data.getJSONObject("settings").put(key,"invalid\nmodel"),"A Notes or Translation model accepted a newline");}
            JSONObject oldSettings=BackupData.validate(valid()).getJSONObject("settings");
            require(oldSettings.getString("notes_transcribe_model").equals("whisper-large-v3") && oldSettings.getString("translate_model").equals("openai/gpt-oss-120b"),"Older backups did not receive compatible Notes and Translation model defaults");
            JSONObject maximum=valid();JSONArray maximumThoughts=new JSONArray();for(int i=0;i<BackupJsonBounds.MAX_THOUGHTS;i++)maximumThoughts.put(thought(i+1));maximum.put("thoughts",maximumThoughts);
            require(BackupData.validate(maximum).getJSONArray("thoughts").length()==10000,"Supported maximum thought count failed parser/schema validation");
            maximumThoughts.put(thought(10001));reject(()->BackupData.validate(maximum),"Too many saved thoughts were accepted");
            reject(()->BackupArchive.decode(first,"fixture backup pass".toCharArray()),"Password spaces were silently trimmed");
            byte[] changed=first.clone();changed[changed.length-1]^=1;reject(()->BackupArchive.decode(changed,password),"Changed ciphertext was accepted");
            byte[] salt=first.clone();salt[16]^=1;reject(()->BackupArchive.decode(salt,password),"Changed authenticated salt was accepted");
            byte[] magic=first.clone();magic[0]^=1;reject(()->BackupArchive.decode(magic,password),"Invalid magic was accepted");
            byte[] version=first.clone();ByteBuffer.wrap(version).putInt(8,2);reject(()->BackupArchive.decode(version,password),"Unsupported version was accepted");
            byte[] iterations=first.clone();ByteBuffer.wrap(iterations).putInt(12,Integer.MAX_VALUE);reject(()->BackupArchive.decode(iterations,password),"Untrusted work factor was accepted");
            byte[] length=first.clone();ByteBuffer.wrap(length).putInt(44,16);reject(()->BackupArchive.decode(length,password),"Inconsistent ciphertext length was accepted");
            reject(()->BackupArchive.decode(Arrays.copyOf(first,first.length-1),password),"Truncated backup was accepted");
            reject(()->BackupArchive.decode(new byte[BackupArchive.MAX_BYTES+1],password),"Oversized archive was accepted");
            reject(()->BackupArchive.checkPassword("1234567".toCharArray()),"Short password was accepted");
            BackupArchive.checkPassword("12345678".toCharArray());checks++;
            JSONObject cleaned=BackupData.validate(source);source.getJSONObject("settings").put("dictionary","changed");source.getJSONArray("history").getJSONObject(0).put("text","changed");
            require(cleaned.getJSONObject("settings").getString("dictionary").equals("jewel=Juel"),"Validated settings share mutable source objects");
            require(cleaned.getJSONArray("history").getJSONObject(0).getString("text").equals("আমার কথা 😀"),"Validated history shares mutable source objects");
            invalid(data->data.put("schema",2),"Future schema accepted");
            invalid(data->data.put("format","other-app"),"Other app's data accepted");
            invalid(data->data.put("created_at",1.5),"Fractional timestamp accepted");
            invalid(data->data.getJSONObject("settings").put("api_secret","injected"),"Device-bound wrapped key injected");
            invalid(data->data.getJSONObject("settings").put("backup_uri","content://injected"),"Backup capability injected");
            invalid(data->data.getJSONObject("settings").put("bubble","yes"),"String switch value accepted");
            invalid(data->data.getJSONObject("settings").put("engine","invalid"),"Unknown speech engine accepted");
            invalid(data->data.getJSONObject("settings").put("backup_frequency","hourly"),"Unknown backup schedule accepted");
            JSONObject otherLanguage=valid();otherLanguage.getJSONObject("settings").put("language","bn-BD");require(BackupData.validate(otherLanguage).getJSONObject("settings").getString("language").equals("en-US"),"Legacy language did not normalize to English-only dictation");
            invalid(data->data.getJSONObject("settings").put("api_base","http://example.com"),"HTTP API endpoint accepted");
            invalid(data->data.getJSONObject("settings").put("api_base","https://user:password@example.com"),"URL credentials accepted");
            invalid(data->data.getJSONObject("settings").put("api_base","https://example.com?q=key"),"URL query accepted");
            invalid(data->data.getJSONObject("settings").put("polish_model","model\nheader"),"Model newline accepted");
            invalid(data->data.put("api_key","fixture\ninvalid"),"API key newline accepted");
            invalid(data->data.getJSONArray("history").put(new JSONObject(data.getJSONArray("history").getJSONObject(0).toString())),"Duplicate history ID accepted");
            invalid(data->data.getJSONArray("history").getJSONObject(0).put("id",0),"Zero history ID accepted");
            invalid(data->data.getJSONArray("history").getJSONObject(0).put("duration",-1),"Negative duration accepted");
            invalid(data->data.getJSONArray("history").getJSONObject(0).put("words","3"),"String word count accepted");
            invalid(data->data.getJSONArray("history").getJSONObject(0).remove("raw"),"Missing source text accepted");
            invalid(data->data.getJSONArray("history").getJSONObject(0).put("text","  "),"Blank dictation accepted");
            invalid(data->{JSONArray history=data.getJSONArray("history");for(int i=1;i<101;i++)history.put(new JSONObject(history.getJSONObject(0).toString()).put("id",1700000000000L+i));},"Too many saved dictations accepted");
            JSONObject offline=valid();offline.getJSONObject("settings").put("engine","offline");require(BackupData.validate(offline).getJSONObject("settings").getString("engine").equals("cloud"),"Legacy on-device choice did not normalize to supported Cloud dictation");
            require(before.equals(Store.prefs(getTargetContext()).getAll()),"Invalid backup or validation modified preferences");
            result.putString("result","PASS: "+checks+" encrypted archive and schema checks; authenticated random archives, password/tamper/version/size rejection, Unicode, immutable validated copies, unsafe settings/endpoint rejection, bounded unique history, and no preference mutation");
        } catch(Throwable error){result.putString("failure",error.toString());}
        finally{Arrays.fill(password,'\0');}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private JSONObject valid() throws Exception {
        return new JSONObject().put("format","voiceflow-backup").put("schema",1).put("created_at",1700000000000L).put("settings",new JSONObject().put("dictionary","jewel=Juel").put("engine","cloud").put("bubble_size","large").put("typing_visible",true).put("backup_frequency","weekly").put("backup_wifi",false)).put("history",new JSONArray().put(new JSONObject().put("id",1700000000000L).put("text","আমার কথা 😀").put("raw","আমার কথা 😀").put("duration",30000L).put("words",3))).put("api_key","fixture-only-backup-key").put("draft","recoverable draft");
    }
    private JSONObject thought(long id) throws Exception {return new JSONObject().put("id",id).put("created",1700000000000L).put("text","A recorded thought 😀").put("raw","A recorded thought 😀").put("title","Thought title").put("summary","A clear thought summary").put("duration",1000).put("lang","en");}
    private void invalid(Edit action,String message) throws Exception {JSONObject data=valid();action.run(data);reject(()->BackupData.validate(data),message);}
    private void reject(Task task,String message) throws Exception {boolean rejected=false;try{task.run();}catch(Exception expected){rejected=true;}require(rejected,message);}
    private void require(boolean pass,String message) throws Exception {checks++;if(!pass)throw new Exception(message);}
    private interface Task{void run() throws Exception;}
    private interface Edit{void run(JSONObject data) throws Exception;}
}
