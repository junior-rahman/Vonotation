package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local storage/restore checks. Uses generated WAV sources, no microphone, provider, or real credentials. */
public final class ThoughtStorageTest extends Instrumentation {
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();Context app=getTargetContext();Map<String,?> before=Store.prefs(app).getAll();ArrayList<File> cleanup=new ArrayList<>();boolean mutated=false;
        try{
            require(!Dictation.isWorking()&&!VoiceTake.updateBusy()&&!LineWire.updateBusy()&&!Notes.updateBusy(),"Run this isolated fixture after all capture and writing work has finished.");
            mutated=true;
            Store.prefs(app).edit().clear().putBoolean("quick_thoughts",true).putString("engine","cloud").commit();
            require(Vault.get(app).isEmpty(),"Fixture must not use a provider key.");
            long pendingId=Notes.nextThoughtId(app);File segment=new File(Notes.sourceDirectory(app),pendingId+"-0.wav");wave(segment);cleanup.add(segment);
            require(Notes.keepThoughtAudio(app,pendingId,Collections.singletonList(segment),1000,false)==pendingId,"First finalized segment was not retained.");
            require(Notes.keepThoughtAudio(app,pendingId,Collections.singletonList(segment),1000,true)==pendingId,"Physical Finish did not commit the pending source.");
            JSONObject pending=find(app,pendingId);require(pending.optBoolean("pending")&&!pending.optBoolean("recording"),"Completed no-key thought is not pending.");
            require(pending.optString("raw").isEmpty()&&pending.optString("text").isEmpty(),"Source-only thought invented words.");
            require(Notes.hasPendingThoughts(app),"Saved source is not eligible for later retry.");
            require(new File(Notes.sourceDirectory(app),pending.getString("audio")).isFile(),"Source reference is missing.");
            int count=notes(app).length();require(Notes.keepThoughtAudio(app,pendingId,Collections.singletonList(segment),1000,true)==pendingId&&notes(app).length()==count,"Repeated completion duplicated a thought.");
            File external=File.createTempFile("thought-source-",".wav",app.getCacheDir());wave(external);cleanup.add(external);
            String raw="Maybe buy milk. Call Juel.";long ready=Notes.keepThought(app,Notes.nextThoughtId(app),raw,0,external);require(ready>0,"Retried transcript/source was not saved.");
            JSONObject saved=find(app,ready);File retained=new File(Notes.sourceDirectory(app),saved.getString("audio"));cleanup.add(retained);
            require(external.isFile()&&retained.isFile()&&!external.getCanonicalFile().equals(retained.getCanonicalFile()),"Source adoption consumed the retry input or failed to copy it.");
            require(saved.getLong("duration")>0,"Retained source duration was lost.");
            require(saved.getString("raw").equals(raw)&&!saved.getString("summary").isEmpty(),"Raw transcript or fallback summary missing.");
            long revision=saved.getLong("revision");require(Notes.editThought(app,ready,"Maybe buy oat milk."),"Thought edit failed.");
            saved=find(app,ready);require(saved.getString("raw").equals(raw)&&saved.getLong("revision")>revision,"Edit changed the original source or failed to supersede old formatting.");
            require(saved.getString("summary").equals("Maybe buy oat milk."),"Edit kept a stale summary.");
            JSONObject snapshot=BackupData.snapshot(app);JSONArray portable=snapshot.getJSONArray("thoughts");require(portable.length()==1,"Source-only pending thought blocked or entered a text archive.");
            JSONObject text=portable.getJSONObject(0);require(!text.has("audio")&&!text.has("parts")&&text.getString("summary").equals(saved.getString("summary")),"Text archive leaked source paths or lost the summary.");
            BackupData.restore(app,snapshot);require(find(app,ready).getString("audio").equals(retained.getName()),"Matching source transcript lost its local recording on restore.");
            require(find(app,pendingId)!=null,"Text-only restore removed this phone's unrelated pending source.");
            JSONObject conflict=new JSONObject(snapshot.toString());conflict.getJSONArray("thoughts").getJSONObject(0).put("text","Different imported words.").put("raw","Different imported words.").put("summary","Different imported words.");
            BackupData.restore(app,conflict);require(find(app,ready).optString("audio").isEmpty(),"An ID collision attached unrelated local audio to imported text.");
            boolean retainedSeparate=false;JSONArray merged=notes(app);for(int i=0;i<merged.length();i++){JSONObject item=merged.getJSONObject(i);if(item.optString("raw").equals(raw)&&item.optString("audio").equals(retained.getName())&&item.optLong("id")!=ready)retainedSeparate=true;}
            require(retainedSeparate,"Conflicting text restore orphaned the original local source.");
            Notes.discardThoughtCapture(app,pendingId);require(find(app,pendingId)==null&&!segment.exists()&&Notes.thoughtDeleted(app,pendingId),"Deleting a pending thought did not remove its source and remember deletion.");
            require(Notes.keepThoughtAudio(app,pendingId,Collections.singletonList(segment),1000,true)<0&&find(app,pendingId)==null,"Late source callback recreated a deleted thought.");
            result.putString("result","PASS: "+checks+" no-key source, idempotent completion, retry copy, original/edit revision, text-only backup, source-safe restore and deletion checks.");
        }catch(Throwable error){result.putString("failure",error.toString());}
        finally{for(File file:cleanup)file.delete();if(mutated){restore(Store.prefs(app),before);ThoughtJob.schedule(app);}}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private static JSONArray notes(Context app)throws Exception{return new JSONArray(Store.get(app,"notes","[]"));}
    private static JSONObject find(Context app,long id)throws Exception{JSONArray notes=notes(app);for(int i=0;i<notes.length();i++){JSONObject note=notes.getJSONObject(i);if(note.optLong("id")==id)return note;}return null;}
    private void require(boolean value,String message)throws Exception{checks++;if(!value)throw new Exception(message);}
    private static void wave(File file)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){int frames=8000,bytes=frames*2;out.write("RIFF".getBytes("US-ASCII"));little(out,36+bytes,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,1,2);little(out,8000,4);little(out,16000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,bytes,4);for(int i=0;i<frames;i++)little(out,0,2);out.getFD().sync();}}
    private static void little(FileOutputStream out,int value,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.write((value>>>(8*i))&255);}
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs,Map<String,?> before){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:before.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,new java.util.HashSet<>((java.util.Set<String>)value));}edit.commit();}
}
