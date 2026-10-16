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
            // Upgrading an old transcript-like summary is a summary-only operation, including on edited notes.
            String editedText="The kitchen walls look worn, and repainting the kitchen is the main project for this weekend. "
                +"Blue paint matches the cabinets that are already in the kitchen and keeps the room bright. "
                +"Saturday is for measuring the walls, buying paint, moving the furniture, and covering the floor. "
                +"Sunday is for painting the kitchen after all the preparation is complete and the room is ready.";
            String title=saved.getString("title"),audioName=saved.getString("audio"),parts=saved.getJSONArray("parts").toString();long revision=saved.getLong("revision"),duration=saved.getLong("duration");
            JSONArray oldSummaries=notes(app);for(int i=0;i<oldSummaries.length();i++){JSONObject item=oldSummaries.getJSONObject(i);if(item.optLong("id")==ready)item.put("text",editedText).put("user_edited",true).put("summary",editedText).put("summary_version",2);}
            require(Store.prefs(app).edit().putString("notes",oldSummaries.toString()).commit(),"Could not stage an old summary.");
            java.lang.reflect.Method upgrade=Notes.class.getDeclaredMethod("upgradeThoughtSummary",Context.class,JSONObject.class);upgrade.setAccessible(true);upgrade.invoke(null,app,find(app,ready));
            saved=find(app,ready);require(saved.getInt("summary_version")==QuickThoughtPolicy.SUMMARY_VERSION&&saved.optBoolean("summary_local"),"Old copied summary was not upgraded locally.");
            require(!saved.getString("summary").isEmpty()&&TranscriptGuard.words(saved.getString("summary")).size()<TranscriptGuard.words(editedText).size()/2,"Old copied summary did not become concise.");
            require(saved.getString("raw").equals(raw)&&saved.getString("text").equals(editedText)&&saved.getString("title").equals(title)&&saved.getLong("revision")==revision,"Summary upgrade rewrote source text, edits, title or revision.");
            require(saved.getString("audio").equals(audioName)&&saved.getJSONArray("parts").toString().equals(parts)&&saved.getLong("duration")==duration&&retained.isFile(),"Summary upgrade changed retained source audio.");
            java.lang.reflect.Method summarySnapshot=Notes.class.getDeclaredMethod("thoughtSnapshot",JSONObject.class,long.class,String.class,String.class,String.class,String.class);summarySnapshot.setAccessible(true);
            require((Boolean)summarySnapshot.invoke(null,saved,revision,raw,editedText,parts,audioName),"Current summary snapshot was refused.");
            JSONObject changed=new JSONObject(saved.toString()).put("revision",revision+1);require(!(Boolean)summarySnapshot.invoke(null,changed,revision,raw,editedText,parts,audioName),"A summary response can overwrite a newer edit.");
            require(!(Boolean)summarySnapshot.invoke(null,(Object)null,revision,raw,editedText,parts,audioName),"A deleted thought can accept a late summary response.");
            changed=new JSONObject(saved.toString()).put("raw","Restored source words.");require(!(Boolean)summarySnapshot.invoke(null,changed,revision,raw,editedText,parts,audioName),"A restored source can accept the old summary response.");
            changed=new JSONObject(saved.toString()).put("parts",new JSONArray().put("different-source.wav"));require(!(Boolean)summarySnapshot.invoke(null,changed,revision,raw,editedText,parts,audioName),"A source replacement can accept the old summary response.");
            require(Notes.editThought(app,ready,"Maybe buy oat milk."),"Thought edit failed.");
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
            partialPendingRestore(app,cleanup);
            Notes.discardThoughtCapture(app,pendingId);require(find(app,pendingId)==null&&!segment.exists()&&Notes.thoughtDeleted(app,pendingId),"Deleting a pending thought did not remove its source and remember deletion.");
            require(Notes.keepThoughtAudio(app,pendingId,Collections.singletonList(segment),1000,true)<0&&find(app,pendingId)==null,"Late source callback recreated a deleted thought.");
            result.putString("result","PASS: "+checks+" no-key source, idempotent completion, retry copy, original/edit revision, concise legacy-summary upgrades, stale/deleted/restored response guards, text-only backup, source-safe and partial-pending restore, cached-segment retry, restored-edit protection and deletion checks.");
        }catch(Throwable error){result.putString("failure",error.toString());}
        finally{for(File file:cleanup)file.delete();if(mutated){restore(Store.prefs(app),before);ThoughtJob.schedule(app);}}
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    /** A saved partial transcript must not turn its unfinished local audio into a completed note. */
    private void partialPendingRestore(Context app,ArrayList<File> cleanup)throws Exception{
        long id=Notes.nextThoughtId(app);File first=new File(Notes.sourceDirectory(app),id+"-partial-0.wav"),second=new File(Notes.sourceDirectory(app),id+"-partial-1.wav");
        wave(first);wave(second);cleanup.add(first);cleanup.add(second);
        String raw="First retained source segment.",configuration="en\nwhisper-large-v3\n"+CloudApi.DEFAULT_BASE;
        long retryAfter=System.currentTimeMillis()+60000L;
        JSONArray sources=new JSONArray().put(first.getName()).put(second.getName());
        JSONArray segments=new JSONArray().put(new JSONObject().put("source",first.getName()).put("bytes",first.length()).put("text",raw).put("heard","en").put("complete",true))
            .put(new JSONObject().put("source",second.getName()).put("text","").put("complete",false).put("error","Fixture second segment offline"));
        JSONObject partial=new JSONObject().put("id",id).put("created",id).put("thought",true).put("raw",raw).put("text",raw).put("title","Partial source")
            .put("summary","First segment").put("audio",first.getName()).put("parts",sources).put("wave","2,3,4").put("duration",2000L).put("lang","en")
            .put("recording",false).put("pending",true).put("partial_transcript",true).put("segment_transcripts",segments).put("transcription_config",configuration)
            .put("retry_after",retryAfter).put("retry_count",3).put("replacement_pending",false).put("transcription_error","Fixture second segment offline")
            .put("thought_capture_complete",true).put("source_interrupted",false).put("minimum_thought_lines",2).put("heard","en").put("revision",7L);
        JSONArray staged=notes(app).put(partial);require(Store.prefs(app).edit().putString("notes",staged.toString()).commit(),"Could not stage partial thought.");
        JSONObject archive=BackupData.snapshot(app),portable=null;JSONArray exported=archive.getJSONArray("thoughts");
        for(int i=0;i<exported.length();i++)if(exported.getJSONObject(i).getLong("id")==id)portable=exported.getJSONObject(i);
        require(portable!=null&&portable.getString("raw").equals(raw),"Partial transcript did not enter its text-only archive.");
        require(!portable.has("parts")&&!portable.has("segment_transcripts")&&!portable.has("transcription_config"),"Local retry/source state entered the portable archive.");
        BackupData.restore(app,archive);JSONObject restored=find(app,id);
        require(restored.optBoolean("pending")&&restored.optBoolean("partial_transcript")&&!restored.optBoolean("recording"),"Restoring its own partial thought stopped pending source work.");
        require(restored.getString("raw").equals(raw)&&restored.getString("text").equals(raw)&&restored.getString("title").equals("Partial source")&&restored.getString("summary").equals("First segment"),"Partial restore rewrote archived text.");
        require(restored.getJSONArray("parts").toString().equals(sources.toString())&&restored.getString("audio").equals(first.getName())&&restored.getString("wave").equals("2,3,4")&&first.isFile()&&second.isFile(),"Partial restore changed local source references.");
        require(restored.getJSONArray("segment_transcripts").toString().equals(segments.toString())&&restored.getString("transcription_config").equals(configuration),"Partial restore lost successful segment cache/configuration.");
        require(restored.getLong("retry_after")==retryAfter&&restored.getInt("retry_count")==3&&restored.getString("retry_language").equals("en")&&restored.getString("transcription_error").equals("Fixture second segment offline"),"Partial restore changed retry language, cooldown or diagnostic state.");
        require(restored.optBoolean("thought_capture_complete")&&!restored.optBoolean("source_interrupted")&&restored.getInt("minimum_thought_lines")==2,"Partial restore lost source completeness safeguards.");
        require(NoteWorkPolicy.eligible(restored.optBoolean("pending"),restored.optBoolean("recording"),restored.optLong("retry_after"),retryAfter),"Restored partial source cannot become eligible for catch-up.");

        // With no key, an attempted ASR request fails before opening any connection.
        // The first successful cache entry must survive while only the second fails.
        require(Vault.get(app).isEmpty(),"Partial retry fixture must not use a provider key.");
        java.lang.reflect.Method transcribe=Notes.class.getDeclaredMethod("transcribeNote",Context.class,JSONObject.class,String.class);transcribe.setAccessible(true);
        Object attempt=transcribe.invoke(null,app,restored,restored.getString("retry_language"));Class<?> transcription=attempt.getClass();
        java.lang.reflect.Field rawField=transcription.getDeclaredField("raw"),completeField=transcription.getDeclaredField("complete"),segmentsField=transcription.getDeclaredField("segments");
        rawField.setAccessible(true);completeField.setAccessible(true);segmentsField.setAccessible(true);JSONArray retried=(JSONArray)segmentsField.get(attempt);
        require(rawField.get(attempt).equals(raw)&&!completeField.getBoolean(attempt)&&retried.getJSONObject(0).getBoolean("complete")&&!retried.getJSONObject(1).getBoolean("complete"),"Restored retry re-requested or lost the successful source segment.");

        // An archive can contain an edited body for the same original transcript.
        // Resuming the local partial source must keep that explicitly restored body.
        String edited="Keep this restored thought edit.";portable.put("text",edited).put("title","Restored edited title").put("summary","Restored edited summary");
        BackupData.restore(app,archive);restored=find(app,id);
        require(restored.getString("text").equals(edited)&&restored.optBoolean("user_edited")&&restored.optBoolean("pending"),"A restored edited body was not protected during pending source recovery.");
        String completeRaw=raw+"\nSecond recovered source segment.";
        rawField.set(attempt,completeRaw);completeField.setBoolean(attempt,true);
        java.lang.reflect.Field errorField=transcription.getDeclaredField("error");errorField.setAccessible(true);errorField.set(attempt,"");
        retried.getJSONObject(1).put("bytes",second.length()).put("text","Second recovered source segment.").put("complete",true).remove("error");
        java.lang.reflect.Method save=Notes.class.getDeclaredMethod("saveThoughtTranscript",Context.class,JSONObject.class,transcription);save.setAccessible(true);
        require(((Integer)save.invoke(null,app,restored,attempt))==1,"Completed retry could not update the restored pending thought.");
        restored=find(app,id);
        require(restored.getString("raw").equals(completeRaw)&&restored.getString("text").equals(edited)&&restored.getString("title").equals("Restored edited title")&&restored.getString("summary").equals("Restored edited summary"),"Completed retry replaced restored edits instead of extending only the original transcript.");
        require(!restored.optBoolean("pending")&&!restored.optBoolean("partial_transcript")&&first.isFile()&&second.isFile(),"Completed retry lost audio or remained pending.");
    }
    private static JSONArray notes(Context app)throws Exception{return new JSONArray(Store.get(app,"notes","[]"));}
    private static JSONObject find(Context app,long id)throws Exception{JSONArray notes=notes(app);for(int i=0;i<notes.length();i++){JSONObject note=notes.getJSONObject(i);if(note.optLong("id")==id)return note;}return null;}
    private void require(boolean value,String message)throws Exception{checks++;if(!value)throw new Exception(message);}
    private static void wave(File file)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){int frames=8000,bytes=frames*2;out.write("RIFF".getBytes("US-ASCII"));little(out,36+bytes,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,1,2);little(out,8000,4);little(out,16000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,bytes,4);for(int i=0;i<frames;i++)little(out,0,2);out.getFD().sync();}}
    private static void little(FileOutputStream out,int value,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.write((value>>>(8*i))&255);}
    @SuppressWarnings("unchecked") private static void restore(SharedPreferences prefs,Map<String,?> before){SharedPreferences.Editor edit=prefs.edit().clear();for(Map.Entry<String,?> entry:before.entrySet()){Object value=entry.getValue();String key=entry.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set)edit.putStringSet(key,new java.util.HashSet<>((java.util.Set<String>)value));}edit.commit();}
}
