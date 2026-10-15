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
            invalid(data->data.getJSONObject("settings").put("language","\nen-US"),"Invalid language accepted");
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
            JSONObject offline=valid();offline.getJSONObject("settings").put("engine","offline");require(BackupData.validate(offline).getJSONObject("settings").getString("engine").equals("offline"),"On-device choice changed during validation");
            require(before.equals(Store.prefs(getTargetContext()).getAll()),"Invalid backup or validation modified preferences");
            result.putString("result","PASS: "+checks+" encrypted archive and schema checks; authenticated random archives, password/tamper/version/size rejection, Unicode, immutable validated copies, unsafe settings/endpoint rejection, bounded unique history, and no preference mutation");
        } catch(Throwable error){result.putString("failure",error.toString());}
        finally{Arrays.fill(password,'\0');}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private JSONObject valid() throws Exception {
        return new JSONObject().put("format","voiceflow-backup").put("schema",1).put("created_at",1700000000000L).put("settings",new JSONObject().put("dictionary","jewel=Juel").put("engine","cloud").put("bubble_size","large").put("typing_visible",true).put("backup_frequency","weekly").put("backup_wifi",false)).put("history",new JSONArray().put(new JSONObject().put("id",1700000000000L).put("text","আমার কথা 😀").put("raw","আমার কথা 😀").put("duration",30000L).put("words",3))).put("api_key","fixture-only-backup-key").put("draft","recoverable draft");
    }
    private void invalid(Edit action,String message) throws Exception {JSONObject data=valid();action.run(data);reject(()->BackupData.validate(data),message);}
    private void reject(Task task,String message) throws Exception {boolean rejected=false;try{task.run();}catch(Exception expected){rejected=true;}require(rejected,message);}
    private void require(boolean pass,String message) throws Exception {checks++;if(!pass)throw new Exception(message);}
    private interface Task{void run() throws Exception;}
    private interface Edit{void run(JSONObject data) throws Exception;}
}
