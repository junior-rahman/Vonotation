package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Generated local audio and injected parser results; no recorder, provider, or real preferences. */
public final class ThoughtMinimumLengthTest extends Instrumentation {
    private static final int UNCHANGED=0,SAVED=1,FILTERED=2;
    private static final int WIDTH=320;
    private static final float TEXT_SIZE=17f;
    private SharedPreferences isolated;
    private Context context;
    private File directory,files,cache;
    private Method saveTranscript;
    private Object oldRetryAction,oldRetryPrefs;
    private boolean hooksCaptured;
    private int checks;
    private String oneLine,twoLines;

    private static final class Capture {
        final long id;final List<File> sources;final JSONObject snapshot;
        Capture(long id,List<File> sources,JSONObject snapshot){this.id=id;this.sources=sources;this.snapshot=snapshot;}
    }

    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle report=new Bundle();
        try{
            require(!Dictation.isWorking()&&!VoiceTake.updateBusy()&&!LineWire.updateBusy()&&!Notes.updateBusy(),"Run this fixture only after capture and note processing are idle.");
            isolated=getTargetContext().getSharedPreferences("thought-minimum-length-test",Context.MODE_PRIVATE);
            require(isolated.edit().clear().putBoolean("quick_thoughts",true).commit(),"Could not reset isolated preferences.");
            directory=File.createTempFile("thought-minimum-","",getTargetContext().getCacheDir());
            require(directory.delete()&&directory.mkdirs(),"Could not create the private fixture directory.");
            files=new File(directory,"files");cache=new File(directory,"cache");
            require(files.mkdirs()&&cache.mkdirs(),"Could not create isolated files/cache directories.");
            context=isolatedContext(getTargetContext());
            require(Vault.get(context).isEmpty(),"Fixture must not have a provider key.");
            oldRetryAction=field("thoughtRetryAction").get(null);oldRetryPrefs=field("thoughtRetryPrefs").get(null);hooksCaptured=true;
            saveTranscript=Notes.class.getDeclaredMethod("saveThoughtTranscript",Context.class,JSONObject.class,String.class,String.class,boolean.class);
            saveTranscript.setAccessible(true);
            layoutBoundaries();
            completedShortAndLateCallback();
            qualifyingRawSurvives();
            offlinePendingSurvives();
            legacyAndEditedSurvive();
            incompleteSourcesSurvive();
            uncertainTranscriptsSurvive();
            malformedProviderRepliesSurvive();
            configurationDoesNotChangeThreshold();
            report.putString("result","PASS: "+checks+" native layout and guarded-storage checks; generated WAVs/injected ASR results only, no microphone or provider.");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{
            try{restoreRetryHooks();}catch(Exception failure){report.putString("cleanup_failure",failure.toString());}
            if(isolated!=null)isolated.edit().clear().commit();
            deleteTree(directory);
        }
        finish(report.containsKey("failure")||report.containsKey("cleanup_failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void layoutBoundaries(){
        TextPaint paint=referencePaint();
        require(paint.measureText("W")>0,"Canonical font did not measure a visible glyph.");
        StringBuilder fit=new StringBuilder("W");
        while(fit.length()<1000&&paint.measureText(fit.toString()+"W")<=WIDTH)fit.append('W');
        oneLine=fit.toString();twoLines=oneLine+"W";
        require(paint.measureText(oneLine)<=WIDTH&&paint.measureText(twoLines)>WIDTH,"Fixture did not derive the real font wrap boundary.");
        require(referenceLines(oneLine)==1&&referenceLines(twoLines)==2,"Independent native layout did not straddle one/two lines.");
        require(ThoughtLength.lineCount(oneLine)==1&&!ThoughtLength.minimumMet(oneLine),"One native line unexpectedly meets the minimum.");
        require(ThoughtLength.lineCount(twoLines)==2&&ThoughtLength.minimumMet(twoLines),"Two native lines unexpectedly fail the minimum.");
        require(ThoughtLength.lineCount(" \n\t\u00a0\u2003 ")==0&&!ThoughtLength.minimumMet(" \n\t\u00a0\u2003 "),"Whitespace-only padding became content.");
        require(ThoughtLength.lineCount(" \n\tok\u00a0\u2003\r\n")==1&&!ThoughtLength.minimumMet(" \n\tok\u00a0\u2003\r\n"),"Whitespace padding made a tiny transcript qualify.");
        require(!ThoughtLength.minimumMet("\u200bok\u2060\ufeff"),"Invisible formatting padded a tiny transcript into a note.");
        String unicode="বাংলা ক্\u200dষ অক্ষর 😀";
        while(referenceLines(unicode)<2)unicode+=" বাংলা ক্\u200dষ অক্ষর 😀";
        require(ThoughtLength.lineCount(unicode)==referenceLines(unicode)&&ThoughtLength.minimumMet(unicode),"Unicode shaping/joiners changed canonical line measurement.");
    }

    private void completedShortAndLateCallback()throws Exception{
        Capture capture=capture(context,true,true,2);
        require(capture.snapshot.optInt("minimum_thought_lines")==2&&capture.snapshot.optBoolean("thought_capture_complete"),"New normally finished source was not eligible for the policy.");
        require(commit(context,capture.snapshot,oneLine,true)==FILTERED,"Complete successful one-line transcript was not filtered.");
        require(find(capture.id)==null&&Notes.thoughtDeleted(context,capture.id),"Filtered thought kept metadata or missed its tombstone.");
        for(File source:capture.sources)require(!source.exists(),"Filtered thought retained a source segment.");
        File late=new File(Notes.sourceDirectory(context),capture.id+"-late.wav");wave(late);
        require(Notes.keepThoughtAudio(context,capture.id,java.util.Collections.singletonList(late),1000,true)<0&&find(capture.id)==null,"A late valid source callback recreated a filtered thought.");
        require(late.isFile(),"Rejected late callback consumed the caller's source.");
    }

    private void qualifyingRawSurvives()throws Exception{
        Capture capture=capture(context,true,true,1);String raw="\t"+twoLines+"\n";
        require(commit(context,capture.snapshot,raw,true)==SAVED,"Qualifying transcript was not saved.");
        JSONObject saved=find(capture.id);
        require(saved!=null&&raw.equals(saved.optString("raw")),"Saving changed the original qualifying transcript.");
        require(!saved.optBoolean("pending")&&!Notes.thoughtDeleted(context,capture.id)&&capture.sources.get(0).isFile(),"Qualifying thought lost its completed state or source.");
        require(commit(context,capture.snapshot,"ok",true)==UNCHANGED&&capture.sources.get(0).isFile(),"A stale short response deleted an already saved thought.");
    }

    private void offlinePendingSurvives()throws Exception{
        Capture capture=capture(context,true,true,1);String before=Store.get(context,"notes","");
        Notes.writeUpThought(context,capture.id);
        require(before.equals(Store.get(context,"notes","")),"Offline retry changed pending metadata before ASR succeeded.");
        require(find(capture.id).optBoolean("pending")&&capture.sources.get(0).isFile()&&!Notes.thoughtDeleted(context,capture.id),"Offline pending audio was filtered or deleted.");
    }

    private void legacyAndEditedSurvive()throws Exception{
        Capture legacy=capture(context,false,true,1);
        require(!legacy.snapshot.has("minimum_thought_lines"),"Legacy complete-only source acquired the new capture marker.");
        require(commit(context,legacy.snapshot,"ok",true)==SAVED,"Markerless pending source did not retain its transcript.");
        retained(legacy,"Legacy markerless source was deleted.");
        Capture edited=capture(context,true,true,1);
        require(Notes.editThought(context,edited.id,"Keep this manual edit."),"Could not edit the pending fixture thought.");
        require(commit(context,edited.snapshot,"ok",true)==UNCHANGED,"Late ASR superseded a user edit.");
        require("Keep this manual edit.".equals(find(edited.id).optString("text")),"Late short ASR replaced edited text.");retained(edited,"Edited thought was deleted.");
        Capture revised=capture(context,true,true,1);JSONObject newer=new JSONObject(revised.snapshot.toString()).put("revision",revised.snapshot.optLong("revision",1)+1);replace(newer);
        require(commit(context,revised.snapshot,"ok",true)==UNCHANGED,"A superseded revision was allowed to filter the current thought.");retained(revised,"Revision-changed thought was deleted.");
    }

    private void incompleteSourcesSurvive()throws Exception{
        Capture failed=capture(context,true,false,1);
        require(!failed.snapshot.optBoolean("thought_capture_complete"),"Failed capture was marked normally complete.");
        require(commit(context,failed.snapshot,"ok",true)!=FILTERED,"A failed capture's short sealed subset was filtered.");retained(failed,"Failed capture lost its source.");
        Capture recovered=capture(context,true,true,1);JSONObject interrupted=new JSONObject(recovered.snapshot.toString()).put("source_interrupted",true);replace(interrupted);
        require(commit(context,interrupted,"ok",true)!=FILTERED,"Interrupted/recovered source was filtered.");retained(recovered,"Recovered source was deleted.");
        Capture missing=capture(context,true,true,2);require(missing.sources.get(1).delete(),"Could not remove the declared fixture segment.");
        require(commit(context,missing.snapshot,"ok",true)!=FILTERED,"A missing declared part was treated as a complete short source.");
        require(find(missing.id)!=null&&!Notes.thoughtDeleted(context,missing.id)&&missing.sources.get(0).isFile(),"Missing-part source metadata or surviving audio was deleted.");
        Capture empty=capture(context,true,true,1);try(FileOutputStream out=new FileOutputStream(empty.sources.get(0))){out.getFD().sync();}
        require(commit(context,empty.snapshot,"ok",true)!=FILTERED&&find(empty.id)!=null&&!Notes.thoughtDeleted(context,empty.id),"Empty declared source was treated as proof of a short complete capture.");
        for(boolean missingAtFinish:new boolean[]{true,false}){
            Capture shortened=incompleteAtFinish(missingAtFinish);
            require(!shortened.snapshot.optBoolean("thought_capture_complete"),missingAtFinish?"Missing final callback source was marked complete.":"Omitting an earlier declared part from the final callback was marked complete.");
            require(commit(context,shortened.snapshot,"ok",true)!=FILTERED,"An incomplete final callback enabled short-source deletion.");
            require(find(shortened.id)!=null&&!Notes.thoughtDeleted(context,shortened.id)&&shortened.sources.get(missingAtFinish?0:1).isFile(),"Incomplete final callback lost metadata or surviving source audio.");
        }
    }

    private void uncertainTranscriptsSurvive()throws Exception{
        for(String partial:new String[]{"ok",""}){
            Capture capture=capture(context,true,true,1);
            require(commit(context,capture.snapshot,partial,false)!=FILTERED,"Error-derived/uncertain partial ASR triggered the minimum filter.");
            retained(capture,"Uncertain partial transcript lost its source.");
        }
    }

    private void malformedProviderRepliesSurvive()throws Exception{
        Capture capture=capture(context,true,true,1);String before=Store.get(context,"notes","");long bytes=capture.sources.get(0).length();
        JSONObject[] malformed={null,new JSONObject(),new JSONObject().put("text",JSONObject.NULL),new JSONObject().put("text",7),new JSONObject().put("text",true),new JSONObject().put("text",new JSONObject()),new JSONObject().put("text",new JSONArray())};
        for(JSONObject reply:malformed){
            boolean rejected=false;try{String raw=CloudApi.transcript(reply,false);commit(context,capture.snapshot,raw,true);}catch(IOException expected){rejected=true;}
            require(rejected,"Wrong-schema successful provider reply was accepted as an empty transcript.");
            require(before.equals(Store.get(context,"notes",""))&&capture.sources.get(0).length()==bytes&&!Notes.thoughtDeleted(context,capture.id),"Malformed provider reply changed pending metadata/audio.");
        }
        JSONObject uncertain=new JSONObject().put("text","ok").put("segments",new JSONArray().put(new JSONObject().put("text","ok").put("avg_logprob",-2)));
        boolean rejected=false;try{CloudApi.transcript(uncertain,false);}catch(IOException expected){rejected=true;}
        require(rejected&&before.equals(Store.get(context,"notes","")),"An uncertain model result changed pending data.");
        Capture silence=capture(context,true,true,1);String empty=CloudApi.transcript(new JSONObject().put("text",""),false);
        require(empty.isEmpty()&&commit(context,silence.snapshot,empty,true)==FILTERED,"Actual successful empty-string ASR did not use the zero-line policy.");
        require(find(silence.id)==null&&Notes.thoughtDeleted(context,silence.id)&&!silence.sources.get(0).exists(),"Successful zero-line capture was not removed atomically.");
    }

    private void configurationDoesNotChangeThreshold()throws Exception{
        for(int mode=0;mode<2;mode++){
            Configuration configuration=new Configuration(getTargetContext().getResources().getConfiguration());
            configuration.densityDpi=mode==0?160:640;configuration.fontScale=mode==0?1f:2.5f;configuration.screenWidthDp=mode==0?320:1000;
            Context configured=isolatedContext(getTargetContext().createConfigurationContext(configuration));
            require(configured.getResources().getConfiguration().densityDpi==configuration.densityDpi&&configured.getResources().getConfiguration().fontScale==configuration.fontScale,"Fixture configuration override did not apply.");
            Capture shortCapture=capture(configured,true,true,1),longCapture=capture(configured,true,true,1);
            require(commit(configured,shortCapture.snapshot,oneLine,true)==FILTERED,"Density/font scale changed the canonical one-line decision.");
            require(commit(configured,longCapture.snapshot,twoLines,true)==SAVED,"Density/font scale changed the canonical two-line decision.");
            retained(longCapture,"Configured large-screen capture lost a qualifying source.");
        }
    }

    private Capture capture(Context app,boolean marked,boolean normalFinish,int count)throws Exception{
        long id=Notes.nextThoughtId(app);List<File> sources=new ArrayList<>();
        for(int i=0;i<count;i++){File source=new File(Notes.sourceDirectory(app),id+"-"+i+".wav");wave(source);sources.add(source);}
        if(marked)require(Notes.keepThoughtAudio(app,id,sources,1000,false)==id,"Could not commit the new recording placeholder.");
        require(Notes.keepThoughtAudio(app,id,sources,1000,true,normalFinish)==id,"Could not commit finalized fixture source.");
        JSONObject snapshot=find(id);require(snapshot!=null&&snapshot.optBoolean("pending")&&!snapshot.optBoolean("recording"),"Finalized no-key source did not remain pending.");
        return new Capture(id,sources,new JSONObject(snapshot.toString()));
    }
    private Capture incompleteAtFinish(boolean missing)throws Exception{
        long id=Notes.nextThoughtId(context);List<File> sources=new ArrayList<>();
        for(int i=0;i<2;i++){File source=new File(Notes.sourceDirectory(context),id+"-"+i+".wav");wave(source);sources.add(source);}
        require(Notes.keepThoughtAudio(context,id,sources,1000,false)==id,"Could not create the two-part placeholder.");
        List<File> finalSources;
        if(missing){require(sources.get(1).delete(),"Could not remove the source before finalization.");finalSources=sources;}
        else finalSources=java.util.Collections.singletonList(sources.get(1));
        require(Notes.keepThoughtAudio(context,id,finalSources,1000,true,true)==id,"Could not finalize the surviving source subset.");
        JSONObject snapshot=find(id);require(snapshot!=null&&snapshot.optBoolean("pending"),"Surviving source subset did not remain pending.");
        return new Capture(id,sources,new JSONObject(snapshot.toString()));
    }
    private int commit(Context app,JSONObject captured,String raw,boolean complete)throws Exception{
        try{return ((Integer)saveTranscript.invoke(null,app,captured,raw,"en",complete)).intValue();}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}
    }
    private JSONObject find(long id)throws Exception{JSONArray notes=new JSONArray(Store.get(context,"notes","[]"));for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id)return note;}return null;}
    private void replace(JSONObject changed)throws Exception{JSONArray before=new JSONArray(Store.get(context,"notes","[]")),after=new JSONArray();for(int i=0;i<before.length();i++){JSONObject note=before.getJSONObject(i);after.put(note.optLong("id")==changed.optLong("id")?changed:note);}require(Store.prefs(context).edit().putString("notes",after.toString()).commit(),"Could not update isolated fixture metadata.");}
    private void retained(Capture capture,String message)throws Exception{require(find(capture.id)!=null&&!Notes.thoughtDeleted(context,capture.id)&&capture.sources.get(0).isFile(),message);}
    private Context isolatedContext(Context base){return new ContextWrapper(base){
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return isolated;}
        @Override public File getFilesDir(){return files;}
        @Override public File getCacheDir(){return cache;}
        @Override public Object getSystemService(String name){if(Context.CONNECTIVITY_SERVICE.equals(name)||Context.JOB_SCHEDULER_SERVICE.equals(name))return null;return super.getSystemService(name);}
    };}
    private void restoreRetryHooks()throws Exception{
        if(!hooksCaptured)return;
        Field action=field("thoughtRetryAction"),prefs=field("thoughtRetryPrefs");
        if(oldRetryAction==null){Object created=action.get(null);if(created instanceof Runnable)((Handler)field("tick").get(null)).removeCallbacks((Runnable)created);action.set(null,null);}
        if(oldRetryPrefs==null){Object created=prefs.get(null);if(created instanceof SharedPreferences.OnSharedPreferenceChangeListener)isolated.unregisterOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener)created);prefs.set(null,null);}
    }
    private static Field field(String name)throws Exception{Field field=Notes.class.getDeclaredField(name);field.setAccessible(true);return field;}
    private static TextPaint referencePaint(){TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(TEXT_SIZE);return paint;}
    private static int referenceLines(String text){return StaticLayout.Builder.obtain(text,0,text.length(),referencePaint(),WIDTH).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().getLineCount();}
    private void require(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static void wave(File file)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){int frames=8000,bytes=frames*2;out.write("RIFF".getBytes("US-ASCII"));little(out,36+bytes,4);out.write("WAVEfmt ".getBytes("US-ASCII"));little(out,16,4);little(out,1,2);little(out,1,2);little(out,8000,4);little(out,16000,4);little(out,2,2);little(out,16,2);out.write("data".getBytes("US-ASCII"));little(out,bytes,4);for(int i=0;i<frames;i++)little(out,0,2);out.getFD().sync();}}
    private static void little(FileOutputStream out,int value,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.write((value>>>(8*i))&255);}
    private static void deleteTree(File file){if(file==null)return;File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);file.delete();}
}
