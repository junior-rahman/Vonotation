package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit local recording transfer. It never reads or writes cloud backup data. */
public final class RecordingTransferActivity extends Activity {
    private static final int SAVE = 910, OPEN = 911;
    private static final String FORMAT = "vonotation-recordings";
    private static final String[] TEXT_FIELDS = {"title", "text", "raw", "summary", "translation", "translationLang", "lang", "heard", "who", "wave", "capture_error", "transcription_error"};
    private static final String[] BOOL_FIELDS = {"call", "pending", "partial_transcript", "source_interrupted", "source_missing"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private TextView feedback;
    private MediaPlayer player;
    private File exportFile;
    private Prepared prepared;
    private volatile boolean destroyed;
    private boolean busy;
    private int visibleCount = 100;

    /** Existing ordinary voice/call records remain owned by the original installation. */
    public static boolean hasLegacy(Context context) {
        JSONArray records = local(context);
        for (int i=0; i<records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item != null && !item.optBoolean("thought")) return true;
        }
        return false;
    }
    private boolean receiving() { return "app.vonotation.notes".equals(getPackageName()); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this); root.setBackground(new Daybook.Backdrop(this));
        root.setPadding(Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,20),Ui.dp(this,16));
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            if (android.os.Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
                view.setPadding(bars.left+Ui.dp(this,20),bars.top+Ui.dp(this,20),bars.right+Ui.dp(this,20),bars.bottom+Ui.dp(this,16));
            }
            return insets;
        });
        root.addView(Daybook.button(this,"Back",false,v -> { if (!busy) finish(); })); Ui.space(root,12);
        root.addView(Daybook.text(this,receiving()?"Import recordings":"Previous recordings",24,Daybook.ink(this),true)); Ui.space(root,10);
        feedback=Daybook.text(this,"",13,Daybook.muted(this),false); root.addView(feedback); Ui.space(root,10);
        ScrollView scroll=new ScrollView(this); body=Ui.column(this); scroll.addView(body); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root); showList();
    }
    private void showList() {
        stopPlayback(); body.removeAllViews();
        if (receiving()) {
            body.addView(Daybook.text(this,"Choose a recording transfer saved from VonoTation. Voice notes and calls are added to this app. Existing recordings are kept.",15,Daybook.ink(this),false)); Ui.space(body,10);
            body.addView(Daybook.text(this,"This imports recording audio and text only. It does not transfer Quick Thoughts, dictations, Clipboard, API keys or settings.",13,Daybook.muted(this),false)); Ui.space(body,16);
            body.addView(Daybook.button(this,"Choose recording transfer",true,v -> chooseImport())); return;
        }
        body.addView(Daybook.text(this,"Your earlier voice notes and calls remain on this phone. You can read and play them here, or save a copy to import into Vono Notes. This screen cannot delete or edit them.",14,Daybook.muted(this),false)); Ui.space(body,14);
        Button export=Daybook.button(this,"Save recording transfer",true,v -> confirmExport()); export.setEnabled(hasLegacy(this)); body.addView(export); Ui.space(body,18);
        JSONArray records=local(this); int shown=0,total=0;
        for (int i=0; i<records.length(); i++) {
            JSONObject item=records.optJSONObject(i); if(item==null||item.optBoolean("thought"))continue; total++;
            if (shown++>=visibleCount) continue;
            LinearLayout card=Daybook.card(this);
            TextView title=Daybook.text(this,title(item),17,Daybook.ink(this),true); card.addView(title);
            card.addView(Daybook.text(this,when(item)+(item.optBoolean("call")?" · Call":" · Voice note"),12,Daybook.muted(this),false));
            card.setOnClickListener(v -> showDetail(item)); body.addView(card); Ui.space(body,8);
        }
        if (total==0) body.addView(Daybook.text(this,"No previous voice notes or calls are stored in this installation.",15,Daybook.muted(this),false));
        if (total>visibleCount) body.addView(Daybook.button(this,"Show older recordings",false,v -> { visibleCount+=100; showList(); }));
    }
    private void showDetail(JSONObject item) {
        stopPlayback(); body.removeAllViews(); body.addView(Daybook.button(this,"All previous recordings",false,v -> showList())); Ui.space(body,14);
        body.addView(Daybook.text(this,title(item),22,Daybook.ink(this),true));
        body.addView(Daybook.text(this,when(item),12,Daybook.muted(this),false));
        addText("Summary",item.optString("summary")); addText("Full transcript",item.optString("text"));
        if(!item.optString("raw").equals(item.optString("text")))addText("Original transcription",item.optString("raw"));
        addText("Translation",item.optString("translation"));
        List<File> audio=sourceFiles(this,item); Ui.space(body,12);
        if(audio.isEmpty())body.addView(Daybook.text(this,"No source audio is available for this recording.",13,Daybook.muted(this),false));
        for(int i=0;i<audio.size();i++) {
            final File file=audio.get(i); final int part=i+1;
            Button play=Daybook.button(this,audio.size()==1?"Play source audio":"Play source audio · Part "+part,false,v -> {
                stopPlayback();
                try {
                    player=new MediaPlayer(); player.setDataSource(file.getAbsolutePath());
                    player.setOnPreparedListener(p -> { if (!destroyed && player==p && hasWindowFocus()) { p.start(); feedback.setText("Playing source audio"+(audio.size()>1?" · Part "+part:"")); } });
                    player.setOnCompletionListener(p -> { stopPlayback(); feedback.setText(""); });
                    player.setOnErrorListener((p,what,extra) -> { stopPlayback(); feedback.setText("This source audio could not be played."); return true; });
                    player.prepareAsync();
                } catch(Exception error) { stopPlayback(); feedback.setText("This source audio could not be played."); }
            }); body.addView(play); Ui.space(body,7);
        }
        if(!audio.isEmpty())body.addView(Daybook.button(this,"Stop playback",false,v -> { stopPlayback(); feedback.setText(""); }));
    }
    private void addText(String heading,String value) {
        if(value==null||value.isEmpty())return; Ui.space(body,14); body.addView(Daybook.text(this,heading,12,Daybook.muted(this),true));
        TextView text=Daybook.text(this,value,16,Daybook.ink(this),false);text.setTextIsSelectable(true);body.addView(text);
    }
    private void confirmExport() {
        if(busy)return;
        new AlertDialog.Builder(this).setTitle("Save a copy of previous recordings?")
            .setMessage("The transfer includes voice/call audio and text. It is a local, unencrypted file, so choose a private location. Your originals stay here. Active recordings are not exported.")
            .setNegativeButton("Cancel",null).setPositiveButton("Prepare transfer",(d,w) -> prepareExport()).show();
    }
    private void prepareExport() {
        if(!ready())return; setBusy(true,"Preparing a local recording transfer…");
        worker.execute(() -> {
            try {
                File output=buildExport(getApplicationContext());
                runOnUiThread(() -> {
                    if(destroyed){output.delete();return;}
                    exportFile=output; setBusy(false,"");
                    try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"VonoTation-recordings.zip"),SAVE); }
                    catch(RuntimeException unavailable){exportFile.delete();exportFile=null;feedback.setText("No file picker is available to save the transfer.");}
                });
            } catch(Exception error) { problem("Could not prepare the recording transfer",error); }
        });
    }
    private void chooseImport() {
        if(!ready())return;
        discardPrepared();
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),OPEN); }
        catch(RuntimeException unavailable){feedback.setText("No file picker is available to choose a transfer.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==SAVE&&exportFile!=null){exportFile.delete();exportFile=null;}return;}
        Uri uri=data.getData();
        if(request==SAVE&&exportFile!=null) {
            final File file=exportFile; exportFile=null; setBusy(true,"Saving the recording transfer…");
            worker.execute(() -> {
                try(InputStream input=new FileInputStream(file);OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                    if(output==null)throw new IOException("The chosen location is not writable.");copy(input,output,null);output.flush();
                    runOnUiThread(() -> { if(!destroyed)setBusy(false,"Recording transfer saved. Your originals remain here."); });
                }catch(Exception error){problem("Could not save the recording transfer",error);}
                finally{file.delete();}
            });
        } else if(request==OPEN&&receiving()) {
            setBusy(true,"Checking the recording transfer…");
            worker.execute(() -> {
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    if(input==null)throw new IOException("The chosen transfer is not readable.");
                    Prepared resultFile=readArchive(getApplicationContext(),input);
                    runOnUiThread(() -> {
                        if(destroyed){resultFile.close();return;} prepared=resultFile;setBusy(false,"");
                        new AlertDialog.Builder(this).setTitle("Add "+resultFile.entries.length()+" recordings?")
                            .setMessage("Voice notes and calls will be added to Vono Notes. Existing recordings are not replaced. Original files in VonoTation are not changed. Imported audio remains local until you choose Transcribe or Again.")
                            .setNegativeButton("Cancel",(d,w) -> discardPrepared()).setOnCancelListener(d -> discardPrepared())
                            .setPositiveButton("Import recordings",(d,w) -> commitImport()).show();
                    });
                }catch(Exception error){problem("Could not open the recording transfer",error);}
            });
        }
    }
    private void commitImport() {
        if(prepared==null||!ready())return;final Prepared transfer=prepared;prepared=null;setBusy(true,"Adding recordings…");
        worker.execute(() -> {
            try { int count=importPrepared(getApplicationContext(),transfer);runOnUiThread(() -> {if(!destroyed)setBusy(false,count+" recordings added. Original recordings were kept.");}); }
            catch(Exception error){problem("Could not import the recordings",error);}
            finally{transfer.close();}
        });
    }
    private boolean ready() {
        if(busy)return false;
        if(Dictation.isWorking()||VoiceTake.updateBusy()||LineWire.updateBusy()||Notes.updateBusy()||Translate.going()) { feedback.setText("Finish recording or processing audio before transferring recordings.");return false; }
        return true;
    }
    private void setBusy(boolean value,String message){busy=value;feedback.setText(message);body.setEnabled(!value);}
    private void problem(String heading,Exception failure){runOnUiThread(() -> {if(!destroyed)setBusy(false,heading+". "+(failure instanceof IOException?failure.getMessage():"The transfer is not supported or could not be saved."));});}
    private void stopPlayback(){MediaPlayer previous=player;player=null;if(previous!=null){try{previous.stop();}catch(RuntimeException ignored){}try{previous.release();}catch(RuntimeException ignored){}}}
    private void discardPrepared(){if(prepared!=null){prepared.close();prepared=null;}}
    @Override protected void onPause(){stopPlayback();super.onPause();}
    @Override protected void onDestroy(){destroyed=true;stopPlayback();discardPrepared();if(exportFile!=null)exportFile.delete();worker.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(busy){feedback.setText("Please wait until the local transfer finishes.");return;}super.onBackPressed();}

    private static JSONArray local(Context context){try{return new JSONArray(Store.get(context,"notes","[]"));}catch(Exception ignored){return new JSONArray();}}
    private static String title(JSONObject item){String title=item.optString("title").trim();if(title.isEmpty())title=item.optBoolean("call")?item.optString("who","Call recording"):"Voice note";return title.isEmpty()?"Call recording":title;}
    private static String when(JSONObject item){return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(item.optLong("created",item.optLong("id"))));}
    private static File source(Context context,String name)throws Exception {
        if(!RecordingTransferPolicy.audioName(name))throw new IOException("A recording has an unsupported source filename.");
        File folder=Notes.sourceDirectory(context).getCanonicalFile(),file=new File(folder,name).getCanonicalFile();
        if(!folder.equals(file.getParentFile()))throw new IOException("A recording source is outside this app's storage.");return file;
    }
    private static List<File> sourceFiles(Context context,JSONObject note) {
        List<File> result=new ArrayList<>();Set<String> seen=new HashSet<>();JSONArray parts=note.optJSONArray("parts");
        if(parts==null||parts.length()==0)parts=new JSONArray().put(note.optString("audio"));
        for(int i=0;i<parts.length();i++)try{String name=parts.getString(i);if(!name.isEmpty()&&seen.add(name)){File file=source(context,name);if(file.isFile()&&file.length()>0)result.add(file);}}catch(Exception ignored){}
        return result;
    }
    private static JSONObject clean(JSONObject source)throws Exception {
        JSONObject out=new JSONObject();
        if(source.optBoolean("thought"))throw new IOException("Quick Thoughts cannot be included in a recording transfer.");
        long id=source.optLong("id",-1),created=source.optLong("created",id),duration=source.optLong("duration",0);
        if(id<=0||created<=0||duration<0)throw new IOException("A recording has invalid metadata.");
        out.put("id",id).put("created",created).put("duration",duration);
        for(String key:TEXT_FIELDS) {
            Object value=source.opt(key);if(value!=null&&value!=JSONObject.NULL&&!(value instanceof String))throw new IOException("A recording contains invalid text.");
            String text=source.optString(key,"");int limit=(key.equals("title")?256:key.equals("lang")||key.equals("heard")||key.equals("translationLang")?64:key.equals("wave")?32768:1000000);
            if(text.length()>limit)throw new IOException("A recording's text is too large.");out.put(key,text);
        }
        for(String key:BOOL_FIELDS){Object value=source.opt(key);if(value!=null&&value!=JSONObject.NULL&&!(value instanceof Boolean))throw new IOException("A recording contains invalid status.");out.put(key,source.optBoolean(key));}
        return out;
    }
    private static File buildExport(Context context)throws Exception {
        JSONArray all; synchronized(Store.class){all=local(context);}
        JSONArray entries=new JSONArray();Map<String,File> audio=new LinkedHashMap<>();long manifestBytes=256;
        for(int i=0;i<all.length();i++) {
            JSONObject item=all.optJSONObject(i);if(item==null||item.optBoolean("thought")||item.optBoolean("recording"))continue;
            if(entries.length()>=RecordingTransferPolicy.MAX_RECORDINGS)throw new IOException("This transfer supports up to 1,000 recordings. Your recordings have not been changed.");
            JSONArray declared=item.optJSONArray("parts");if(declared!=null&&declared.length()>RecordingTransferPolicy.MAX_PARTS)throw new IOException("A recording contains too many source parts for one transfer.");
            JSONObject saved=clean(item);JSONArray parts=new JSONArray();List<File> files=sourceFiles(context,item);
            for(File file:files){if(file.length()>RecordingTransferPolicy.MAX_AUDIO)throw new IOException("A source recording is too large for one transfer.");parts.put(file.getName());audio.put(file.getName(),file);if(audio.size()>RecordingTransferPolicy.MAX_PARTS)throw new IOException("The transfer contains too many source files.");}
            boolean missing=declared!=null&&declared.length()>files.size();
            if(declared==null&&!item.optString("audio").isEmpty()&&files.isEmpty())missing=true;
            saved.put("parts",parts).put("audio",parts.optString(0,"")).put("source_missing",missing||saved.optBoolean("source_missing"));
            long bytes=saved.toString().getBytes(StandardCharsets.UTF_8).length+1L;
            if(bytes>RecordingTransferPolicy.MAX_MANIFEST-manifestBytes)throw new IOException("The recording transfer text is too large. Your recordings have not been changed.");
            manifestBytes+=bytes;entries.put(saved);
        }
        if(entries.length()==0)throw new IOException("No completed voice notes or calls are available to export.");
        byte[] manifest=new JSONObject().put("format",FORMAT).put("schema",1).put("created_at",System.currentTimeMillis()).put("entries",entries).toString().getBytes(StandardCharsets.UTF_8);
        File output=File.createTempFile("recordings-transfer-",".zip",context.getCacheDir());boolean complete=false;
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(output))) {
            RecordingTransferPolicy.Budget budget=new RecordingTransferPolicy.Budget();budget.begin(RecordingTransferPolicy.MANIFEST);budget.add(manifest.length);
            zip.putNextEntry(new ZipEntry(RecordingTransferPolicy.MANIFEST));zip.write(manifest);zip.closeEntry();
            for(Map.Entry<String,File> part:audio.entrySet()) {
                String name="audio/"+part.getKey();budget.begin(name);zip.putNextEntry(new ZipEntry(name));
                try(InputStream in=new FileInputStream(part.getValue())){copy(in,zip,budget);}zip.closeEntry();
            }
            zip.finish(); complete=true;
        }finally{if(!complete)output.delete();}
        return output;
    }
    private static final class Prepared implements AutoCloseable {
        final File folder;final JSONArray entries;final Map<String,File> audio;
        Prepared(File folder,JSONArray entries,Map<String,File> audio){this.folder=folder;this.entries=entries;this.audio=audio;}
        @Override public void close(){remove(folder);}
    }
    private static Prepared readArchive(Context context,InputStream input)throws Exception {
        File folder=new File(context.getCacheDir(),"recording-import-"+UUID.randomUUID());if(!folder.mkdir())throw new IOException("There is not enough storage to open the transfer.");
        boolean ready=false;
        try(ZipInputStream zip=new ZipInputStream(input)) {
            Map<String,File> audio=new HashMap<>();byte[] manifest=null;RecordingTransferPolicy.Budget budget=new RecordingTransferPolicy.Budget();ZipEntry entry;
            while((entry=zip.getNextEntry())!=null) {
                if(entry.isDirectory())throw new IOException("The transfer contains an unsupported directory.");
                String name=entry.getName();budget.begin(name);
                if(RecordingTransferPolicy.MANIFEST.equals(name)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(zip,bytes,budget);manifest=bytes.toByteArray();}
                else {String fileName=name.substring(6);File file=new File(folder,fileName);try(OutputStream out=new FileOutputStream(file)){copy(zip,out,budget);}if(file.length()==0)throw new IOException("A recording source is empty.");audio.put(fileName,file);}
                zip.closeEntry();
            }
            if(manifest==null)throw new IOException("This file is not a VonoTation recording transfer.");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(manifest)).toString();BackupJsonBounds.check(text);JSONObject root=new JSONObject(text);
            if(!FORMAT.equals(root.optString("format"))||root.optInt("schema")!=1||root.optLong("created_at")<=0)throw new IOException("This recording-transfer version is not supported.");
            Set<String> rootKeys=new HashSet<>();java.util.Collections.addAll(rootKeys,"format","schema","created_at","entries");for(Iterator<String> keys=root.keys();keys.hasNext();)if(!rootKeys.contains(keys.next()))throw new IOException("The transfer contains unsupported data.");
            JSONArray incoming=root.optJSONArray("entries");if(incoming==null||incoming.length()==0||incoming.length()>RecordingTransferPolicy.MAX_RECORDINGS)throw new IOException("The transfer contains an unsupported number of recordings.");
            JSONArray checked=new JSONArray();Set<Long> ids=new HashSet<>();Set<String> referenced=new HashSet<>();
            Set<String> allowed=new HashSet<>();java.util.Collections.addAll(allowed,"id","created","duration","parts","audio");java.util.Collections.addAll(allowed,TEXT_FIELDS);java.util.Collections.addAll(allowed,BOOL_FIELDS);
            for(int i=0;i<incoming.length();i++) {
                JSONObject item=incoming.getJSONObject(i);for(Iterator<String> keys=item.keys();keys.hasNext();)if(!allowed.contains(keys.next()))throw new IOException("The transfer contains data other than recording audio and text.");
                JSONObject safe=clean(item);if(!ids.add(safe.getLong("id")))throw new IOException("The transfer contains duplicate recording identifiers.");
                JSONArray parts=item.optJSONArray("parts");if(parts==null||parts.length()>RecordingTransferPolicy.MAX_PARTS)throw new IOException("A recording's source list is not valid.");
                JSONArray names=new JSONArray();Set<String> unique=new HashSet<>();
                for(int j=0;j<parts.length();j++){String name=parts.getString(j);if(!RecordingTransferPolicy.audioName(name)||!unique.add(name)||!audio.containsKey(name))throw new IOException("A recording source is missing or duplicated.");names.put(name);referenced.add(name);}
                if(!item.optString("audio","").equals(names.optString(0,"")))throw new IOException("A recording's source metadata does not match its audio.");
                safe.put("parts",names).put("audio",names.optString(0,""));checked.put(safe);
            }
            if(!referenced.equals(audio.keySet()))throw new IOException("The transfer contains audio that is not attached to a recording.");
            ready=true;return new Prepared(folder,checked,audio);
        }finally{if(!ready)remove(folder);}
    }
    private static int importPrepared(Context context,Prepared prepared)throws Exception {
        if(!"app.vonotation.notes".equals(context.getPackageName()))throw new IOException("Recordings can only be imported into Vono Notes.");
        List<File> copied=new ArrayList<>();JSONArray added=new JSONArray();boolean committed=false,keepFiles=false;
        RecordingTransferPolicy.Budget copyBudget=new RecordingTransferPolicy.Budget();
        try {
            for(int i=0;i<prepared.entries.length();i++) {
                JSONObject item=new JSONObject(prepared.entries.getJSONObject(i).toString());JSONArray original=item.getJSONArray("parts"),parts=new JSONArray();
                for(int j=0;j<original.length();j++) {
                    File from=prepared.audio.get(original.getString(j));String name="import-"+UUID.randomUUID()+".m4a";File dest=source(context,name);copyBudget.begin("audio/"+name);copied.add(dest);
                    try(InputStream in=new FileInputStream(from);FileOutputStream out=new FileOutputStream(dest)){copy(in,out,copyBudget);out.getFD().sync();}parts.put(name);
                }
                boolean pending=item.optBoolean("pending")||item.optBoolean("partial_transcript");
                item.put("parts",parts).put("audio",parts.optString(0,"")).put("recording",false).put("pending",false).put("revision",1L).put("retry_after",0L);
                if(pending)item.put("transcription_error","Imported source audio is saved. Choose Again to finish transcription.");
                item.put("transfer_source_id",item.getLong("id")).put("transfer_imported_at",System.currentTimeMillis());added.put(item);
            }
            synchronized(Store.class) {
                if(Thread.currentThread().isInterrupted()||VoiceTake.updateBusy()||LineWire.updateBusy()||Notes.updateBusy())throw new IOException("Finish processing audio before importing recordings.");
                String before=Store.get(context,"notes","[]");JSONArray existing=new JSONArray(before),merged=new JSONArray();Set<Long> used=new HashSet<>();List<Object> ordered=new ArrayList<>();
                for(int i=0;i<existing.length();i++){Object value=existing.get(i);ordered.add(value);if(value instanceof JSONObject)used.add(((JSONObject)value).optLong("id"));}
                long candidate=System.currentTimeMillis();for(int i=0;i<added.length();i++){JSONObject item=added.getJSONObject(i);long id=RecordingTransferPolicy.nextId(used,candidate);if(id<Long.MAX_VALUE)candidate=id+1;item.put("id",id);ordered.add(item);}
                // Stable sorting keeps existing identities and their relative order when dates match.
                java.util.Collections.sort(ordered,(a,b)->Long.compare(created(b),created(a)));
                for(Object item:ordered)merged.put(item);
                if(!Store.prefs(context).edit().putString("notes",merged.toString()).commit()) {
                    keepFiles=!Store.prefs(context).edit().putString("notes",before).commit();
                    throw new IOException(keepFiles?"The phone could not finish saving. Original recordings are retained; check available storage before retrying.":"The new recordings could not be saved. Previous recordings were kept.");
                }
                committed=true;
            }
            return added.length();
        }finally{if(!committed&&!keepFiles)for(File file:copied)file.delete();}
    }
    private static long created(Object value){if(!(value instanceof JSONObject))return 0L;JSONObject item=(JSONObject)value;return item.optLong("created",item.optLong("id"));}
    private static void copy(InputStream input,OutputStream output,RecordingTransferPolicy.Budget budget)throws IOException {
        byte[] buffer=new byte[16384];int read;
        while((read=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("The local transfer was interrupted.");if(budget!=null)budget.add(read);output.write(buffer,0,read);}
    }
    private static void remove(File file){if(file==null)return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);}file.delete();}
}
