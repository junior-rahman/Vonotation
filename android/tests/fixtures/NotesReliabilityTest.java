package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONArray;
import org.json.JSONObject;

/** Isolated, generated local sources and injected segment results. No recorder, network, or real settings. */
public final class NotesReliabilityTest extends Instrumentation {
    private Context context;private SharedPreferences preferences;private File temporary,files,cache;private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle report=new Bundle();
        try{
            require(!Notes.updateBusy()&&!VoiceTake.updateBusy()&&!LineWire.updateBusy(),"Finish all capture/writing before this isolated fixture.");
            preferences=getTargetContext().getSharedPreferences("notes-reliability-fixture",Context.MODE_PRIVATE);require(preferences.edit().clear().commit(),"Reset failed.");
            temporary=File.createTempFile("notes-reliability-","",getTargetContext().getCacheDir());require(temporary.delete()&&temporary.mkdirs(),"No temporary directory.");
            files=new File(temporary,"files");cache=new File(temporary,"cache");require(files.mkdirs()&&cache.mkdirs(),"No isolated directories.");
            context=new ContextWrapper(getTargetContext()){
                public Context getApplicationContext(){return this;}
                public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
                public File getFilesDir(){return files;}public File getCacheDir(){return cache;}
                public Object getSystemService(String name){if(CONNECTIVITY_SERVICE.equals(name)||JOB_SCHEDULER_SERVICE.equals(name))return null;return super.getSystemService(name);}
            };
            snapshotAndDeletion();partialSegmentCache();callSourceMerge();retention();
            report.putString("result","PASS: "+checks+" guarded note writes, deletion tombstone, partial transcript/cache, owned call-source merge and retention checks. No provider or microphone.");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{if(preferences!=null)preferences.edit().clear().commit();removeTree(temporary);}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void snapshotAndDeletion()throws Exception{
        JSONObject note=note(41,"old transcript");put(new JSONArray().put(note));
        Method update=Notes.class.getDeclaredMethod("updateCaptured",Context.class,JSONObject.class,Notes.NoteMutation.class);update.setAccessible(true);
        require((Boolean)update.invoke(null,context,note,(Notes.NoteMutation)item->item.put("summary","new summary")),"Unchanged snapshot was rejected.");
        require("new summary".equals(find(41).optString("summary")),"Accepted update did not save.");
        require(!(Boolean)update.invoke(null,context,note,(Notes.NoteMutation)item->item.put("text","stale overwrite")),"A stale revision replaced newer work.");
        JSONObject latest=find(41);Method remove=Notes.class.getDeclaredMethod("remove",Context.class,long.class);remove.setAccessible(true);remove.invoke(null,context,41L);
        require(find(41)==null,"Delete kept metadata.");
        require(!(Boolean)update.invoke(null,context,latest,(Notes.NoteMutation)item->item.put("text","ghost"))&&find(41)==null,"Late provider response recreated deleted recording.");
        File source=source("41-wire-0.m4a");require(!Notes.keepCallFiles(context,41,java.util.Collections.singletonList(source),true)&&find(41)==null,"Late call-source callback bypassed deletion tombstone.");
    }
    private void partialSegmentCache()throws Exception{
        File first=source("51-0.m4a");String secondName="51-1.m4a";
        JSONObject seed=note(51,"").put("pending",true).put("parts",new JSONArray().put(first.getName()).put(secondName));
        String config="auto\nwhisper-large-v3\n"+CloudApi.DEFAULT_BASE;String arabic="مرحبا كيف حالك اليوم";
        JSONArray cached=new JSONArray().put(new JSONObject().put("source",first.getName()).put("bytes",first.length()).put("complete",true).put("text",arabic).put("heard","ar"))
            .put(new JSONObject().put("source",secondName).put("bytes",3).put("complete",true).put("text","Second piece").put("heard","en"));
        seed.put("transcription_config",config).put("segment_transcripts",cached);
        Method transcribe=Notes.class.getDeclaredMethod("transcribeNote",Context.class,JSONObject.class,String.class);transcribe.setAccessible(true);
        Object partial=transcribe.invoke(null,context,seed,"auto");
        require(arabic.equals(value(partial,"raw")),"A missing segment dropped recognized Arabic or changed Auto language.");
        require(!((Boolean)value(partial,"complete")),"Missing source was reported complete.");
        Class<?> resultType=Class.forName("app.voiceflow.Notes$Transcription");Method apply=Notes.class.getDeclaredMethod("applyTranscription",JSONObject.class,resultType,String.class,String.class,String.class);apply.setAccessible(true);
        JSONObject saved=new JSONObject(seed.toString());apply.invoke(null,saved,partial,"auto",arabic,arabic);
        require(saved.optBoolean("pending")&&saved.optBoolean("partial_transcript")&&saved.optLong("retry_after")>System.currentTimeMillis(),"Partial ASR lost its retry/cooldown state.");
        require(saved.getJSONArray("segment_transcripts").getJSONObject(0).getBoolean("complete"),"Retry state discarded a successful segment.");
        JSONObject previous=note(52,"Keep my complete original.").put("summary","Keep my good summary.");Field replacement=resultType.getDeclaredField("replacement");replacement.setAccessible(true);replacement.setBoolean(partial,true);
        apply.invoke(null,previous,partial,"bn",arabic,arabic);
        require("Keep my complete original.".equals(previous.optString("raw"))&&"Keep my complete original.".equals(previous.optString("text"))&&"Keep my good summary.".equals(previous.optString("summary")),"Failed retranscription erased a previous usable transcript/summary.");
        require(previous.optBoolean("replacement_pending")&&previous.optBoolean("pending")&&"bn".equals(previous.optString("retry_language")),"Replacement did not retain its independent retry target.");
        java.lang.reflect.Constructor<?> constructor=resultType.getDeclaredConstructor();constructor.setAccessible(true);Object empty=constructor.newInstance();replacement.setBoolean(empty,true);
        apply.invoke(null,previous,empty,"bn","","");
        require("Keep my complete original.".equals(previous.optString("raw"))&&"Keep my complete original.".equals(previous.optString("text"))&&"Keep my good summary.".equals(previous.optString("summary")),"A successful empty replacement erased previously saved words.");
        require(previous.optBoolean("pending")&&!previous.optString("transcription_error").isEmpty(),"Empty replacement lost its actionable retry state.");


        source(secondName);Object complete=transcribe.invoke(null,context,seed,"auto");
        require((Boolean)value(complete,"complete"),"All cached source pieces did not complete.");
        require((arabic+"\nSecond piece").equals(value(complete,"raw")),"Retry duplicated or reordered source text.");
        apply.invoke(null,saved,complete,"auto",value(complete,"raw"),"");require(!saved.optBoolean("pending")&&!saved.optBoolean("partial_transcript"),"Successful retry kept a false partial state.");
    }
    private void callSourceMerge()throws Exception{
        File wire=source("61-wire-0.m4a"),mic=source("61-0.m4a");
        require(Notes.keepCallFiles(context,61,java.util.Collections.singletonList(wire),false),"Wire source was not saved.");
        require(Notes.keepCallFiles(context,61,java.util.Collections.singletonList(mic),false),"Fallback source was not merged.");
        JSONObject saved=find(61);require(saved.optBoolean("call")&&saved.optBoolean("recording")&&saved.getJSONArray("parts").length()==2,"Call fallback split/lost source identity.");
        require(saved.getJSONArray("parts").getString(0).equals(wire.getName())&&saved.getJSONArray("parts").getString(1).equals(mic.getName()),"Call source order changed.");
        Notes.tagCall(context,61,"Exact caller");require("Exact caller".equals(find(61).optString("who")),"Caller tag lost its session id.");
        Notes.tagCall(context,999,"Unrelated caller");require("Exact caller".equals(find(61).optString("who")),"Unrelated caller changed this session.");
        require(Notes.keepCallFiles(context,61,java.util.Collections.singletonList(mic),true),"Final call source was not saved.");
        saved=find(61);long revision=saved.optLong("revision");require(saved.optBoolean("pending")&&!saved.optBoolean("recording")&&saved.getJSONArray("parts").length()==2,"Call completion dropped source or pending state.");
        require(Notes.keepCallFiles(context,61,java.util.Collections.singletonList(mic),true)&&find(61).optLong("revision")==revision,"Repeated final callback invalidated the active snapshot.");
    }
    private void retention()throws Exception{
        File evicted=source("evicted.m4a"),shared=source("shared.m4a");
        JSONArray previous=new JSONArray().put(note(71,"").put("audio",evicted.getName())).put(note(72,"").put("audio",shared.getName())).put(note(73,"").put("audio",shared.getName()));
        JSONArray retained=new JSONArray().put(previous.getJSONObject(2));put(previous);
        Method commit=Notes.class.getDeclaredMethod("commitNotes",Context.class,JSONArray.class,JSONArray.class);commit.setAccessible(true);
        require((Boolean)commit.invoke(null,context,previous,retained),"Retention metadata did not commit.");
        require(!evicted.exists()&&shared.exists(),"Retention orphaned private audio or deleted a retained shared source.");
        JSONArray existing=new JSONArray().put(note(81,"").put("pending",true));for(int i=0;i<101;i++)existing.put(note(100+i,"ready"));
        JSONArray next=new JSONArray();Method append=Notes.class.getDeclaredMethod("appendRetained",JSONArray.class,JSONArray.class,long.class);append.setAccessible(true);append.invoke(null,next,existing,-1L);
        require(next.length()==101&&next.getJSONObject(0).optBoolean("pending"),"Retention evicted an unfinished source or removed the ready-note limit.");
    }
    private JSONObject note(long id,String text)throws Exception{return new JSONObject().put("id",id).put("created",id).put("revision",1).put("text",text).put("raw",text).put("summary","").put("lang","auto").put("audio","").put("parts",new JSONArray()).put("pending",false);}
    private void put(JSONArray notes){require(preferences.edit().putString("notes",notes.toString()).commit(),"Could not seed metadata.");}
    private JSONObject find(long id)throws Exception{JSONArray notes=new JSONArray(Store.get(context,"notes","[]"));for(int i=0;i<notes.length();i++){JSONObject note=notes.getJSONObject(i);if(note.optLong("id")==id)return note;}return null;}
    private File source(String name)throws Exception{File file=new File(Notes.sourceDirectory(context),name);try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2,3});out.getFD().sync();}return file;}
    private static Object value(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void removeTree(File file){if(file==null)return;File[] children=file.listFiles();if(children!=null)for(File child:children)removeTree(child);file.delete();}
}
