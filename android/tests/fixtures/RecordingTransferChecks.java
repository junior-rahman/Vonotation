package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic production-code transfer checks. Only run on the fresh disposable fixture app. */
final class RecordingTransferChecks {
    private static int checks;
    static int run(Context context, boolean notesProduct) throws Exception {
        checks=0;
        SharedPreferences prefs=Store.prefs(context);boolean hadNotes=prefs.contains("notes");String before=Store.get(context,"notes","[]");
        check(new JSONArray(before).length()==0,"Recording transfer fixture requires fresh app data");
        List<File> owned=new ArrayList<>();Object prepared=null;File archive=null;
        try {
            String prefix="qa-transfer-"+UUID.randomUUID();
            File voice=source(context,prefix+"-voice.m4a",owned);
            File call=source(context,prefix+"-call.m4a",owned);
            File thought=source(context,prefix+"-thought.m4a",owned);
            JSONObject voiceNote=note(100,voice,false,false).put("pending",true).put("api_key","DO_NOT_TRANSFER_THIS_SECRET");
            JSONObject callNote=note(200,call,true,false).put("translation","Preserved attached translation").put("who","Synthetic caller");
            JSONObject quickThought=note(300,thought,false,true).put("raw","EXCLUDED_QUICK_THOUGHT");
            JSONObject active=note(400,voice,false,false).put("recording",true).put("raw","EXCLUDED_ACTIVE_RECORDING");
            String seeded=new JSONArray().put(voiceNote).put(callNote).put(quickThought).put(active).toString();
            check(prefs.edit().putString("notes",seeded).commit(),"Could not seed transfer fixture");
            Method export=method("buildExport",Context.class); archive=(File)export.invoke(null,context);
            check(archive.isFile()&&archive.length()>0,"Production exporter did not write an archive");
            check(Store.get(context,"notes","").equals(seeded),"Export changed original notes");
            Set<String> zipped=new HashSet<>();String manifest="";
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))) {
                ZipEntry entry;while((entry=zip.getNextEntry())!=null) {
                    zipped.add(entry.getName());ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(zip,bytes);
                    if(entry.getName().equals("recordings.json"))manifest=new String(bytes.toByteArray(),StandardCharsets.UTF_8);zip.closeEntry();
                }
            }
            check(new JSONObject(manifest).getJSONArray("entries").length()==2,"Transfer must contain only ordinary voice/call records");
            check(!manifest.contains("DO_NOT_TRANSFER_THIS_SECRET")&&!manifest.contains("EXCLUDED_QUICK_THOUGHT")&&!manifest.contains("EXCLUDED_ACTIVE_RECORDING"),"Transfer exported excluded data");
            check(zipped.size()==3&&zipped.contains("audio/"+voice.getName())&&zipped.contains("audio/"+call.getName())&&!zipped.contains("audio/"+thought.getName()),"Transfer audio selection was not isolated");
            check(voice.length()==4096&&call.length()==4096&&thought.length()==4096,"Export changed original source audio");
            Method read=method("readArchive",Context.class,InputStream.class);
            try(InputStream in=new FileInputStream(archive)){prepared=read.invoke(null,context,in);}
            check(prepared!=null,"Production transfer reader rejected valid export");
            rejected(read,context,zip("audio/../escaped.m4a",new byte[]{1}),"Zip slip was accepted");
            rejected(read,context,zip("settings.json","{\"api_key\":\"bad\"}".getBytes(StandardCharsets.UTF_8)),"Nonrecording data was accepted");
            rejected(read,context,zip("recordings.json","{}".getBytes(StandardCharsets.UTF_8)),"Missing transfer envelope was accepted");
            Method importer=method("importPrepared",Context.class,prepared.getClass());
            if(!notesProduct) {
                try { importer.invoke(null,context,prepared);throw new Exception("Original app accepted a recording import"); }
                catch(InvocationTargetException rejected){check(rejected.getCause() instanceof java.io.IOException,"Original app returned an unexpected import error");}
                check(Store.get(context,"notes","").equals(seeded),"Rejected original-app import changed notes");
            } else {
                long collision=System.currentTimeMillis();JSONObject existing=new JSONObject().put("id",collision).put("created",collision).put("text","KEEP_EXISTING_RECORDING");
                check(prefs.edit().putString("notes",new JSONArray().put(existing).toString()).commit(),"Could not seed existing Notes recording");
                int imported=(Integer)importer.invoke(null,context,prepared);JSONArray result=new JSONArray(Store.get(context,"notes","[]"));
                check(imported==2&&result.length()==3,"Import did not add recordings without replacement");
                Set<Long> ids=new HashSet<>();boolean kept=false;int added=0;
                for(int i=0;i<result.length();i++) {
                    JSONObject entry=result.getJSONObject(i);check(ids.add(entry.getLong("id")),"Imported recording IDs collided");
                    if("KEEP_EXISTING_RECORDING".equals(entry.optString("text"))){kept=entry.getLong("id")==collision;continue;}
                    added++;check(!entry.optBoolean("thought")&&!entry.optBoolean("recording")&&!entry.optBoolean("pending"),"Import enabled unsupported capture or automatic transcription");
                    JSONArray parts=entry.getJSONArray("parts");check(parts.length()==1,"Imported recording lost its source");
                    String name=parts.getString(0);check(name.startsWith("import-")&&!name.equals(voice.getName())&&!name.equals(call.getName()),"Import reused original source names");
                    File file=new File(Notes.sourceDirectory(context),name);owned.add(file);check(file.isFile()&&file.length()==4096,"Imported source bytes are missing");
                    check(digest(file).equals(digest(entry.optBoolean("call")?call:voice)),"Imported source bytes do not match their recording");
                }
                check(kept&&added==2,"Import replaced existing data");
                check(voice.length()==4096&&call.length()==4096&&thought.length()==4096,"Import moved or removed original source audio");
            }
        } finally {
            if(prepared instanceof AutoCloseable)((AutoCloseable)prepared).close();
            if(archive!=null)archive.delete();for(File file:owned)file.delete();
            SharedPreferences.Editor restore=prefs.edit();if(hadNotes)restore.putString("notes",before);else restore.remove("notes");
            check(restore.commit()&&Store.get(context,"notes","[]").equals(before),"Transfer fixture did not restore original preference state");
        }
        return checks;
    }
    private static JSONObject note(long id,File audio,boolean call,boolean thought)throws Exception {
        return new JSONObject().put("id",id).put("created",id).put("duration",1000).put("call",call).put("thought",thought)
            .put("raw","Synthetic recording source").put("text","Synthetic recording text").put("summary","Synthetic concise summary")
            .put("audio",audio.getName()).put("parts",new JSONArray().put(audio.getName()));
    }
    private static File source(Context context,String name,List<File> owned)throws Exception {
        File file=new File(Notes.sourceDirectory(context),name);check(!file.exists(),"Fixture filename collision");
        byte[] bytes=new byte[4096];java.util.Arrays.fill(bytes,(byte)(name.contains("-voice")?17:name.contains("-call")?34:51));
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}owned.add(file);return file;
    }
    private static Method method(String name,Class<?>...types)throws Exception {Method result=RecordingTransferActivity.class.getDeclaredMethod(name,types);result.setAccessible(true);return result;}
    private static void rejected(Method read,Context context,byte[] archive,String message)throws Exception {
        try {Object result=read.invoke(null,context,new ByteArrayInputStream(archive));if(result instanceof AutoCloseable)((AutoCloseable)result).close();throw new Exception(message);}
        catch(InvocationTargetException error){check(error.getCause() instanceof java.io.IOException||error.getCause() instanceof org.json.JSONException,message+": unexpected exception");}
    }
    private static byte[] zip(String name,byte[] bytes)throws Exception {ByteArrayOutputStream result=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(result)){zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}return result.toByteArray();}
    private static String digest(File file)throws Exception {java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}StringBuilder value=new StringBuilder();for(byte b:digest.digest())value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();}
    private static void copy(InputStream input,ByteArrayOutputStream output)throws Exception {byte[] bytes=new byte[8192];int count;while((count=input.read(bytes))!=-1)output.write(bytes,0,count);}
    private static void check(boolean result,String message)throws Exception {checks++;if(!result)throw new Exception(message);}
}
