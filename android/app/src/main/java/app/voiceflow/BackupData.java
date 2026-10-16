package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only portable user data is exported; credentials, grants, jobs and caches stay local. */
final class BackupData {
    private static final String FORMAT="voiceflow-backup";
    private static final int SCHEMA=1;
    private static final String[] STRING_KEYS={"engine","language","transcribe_model","polish_model","notes_transcribe_model","notes_polish_model","translate_transcribe_model","translate_model","dictionary","api_base","bubble_size","backup_frequency"};
    private static final String[] STRING_DEFAULTS={"cloud","en-US",CloudApi.DEFAULT_TRANSCRIBE,CloudApi.DEFAULT_POLISH,"whisper-large-v3","openai/gpt-oss-120b","whisper-large-v3","openai/gpt-oss-120b","",CloudApi.DEFAULT_BASE,"small","off"};
    private static final String[] BOOL_KEYS={"clean","ai_polish","bubble","save_history","daily_summaries","typing_visible","backup_wifi","haptic_feedback","romanize","daily_recaps_opt_in","verbatim","spoken_punctuation","quick_thoughts"};
    private static final boolean[] BOOL_DEFAULTS={true,false,true,true,true,false,true,true,false,false,false,true,false};
    private static final String[] OPTIONAL_INTS={"rail_anchor_x","rail_anchor_y"};

    static JSONObject snapshot(Context context) throws Exception {
        synchronized(Store.class) {
            SharedPreferences prefs=Store.prefs(context);JSONObject settings=new JSONObject();
            for(int i=0;i<STRING_KEYS.length;i++)settings.put(STRING_KEYS[i],prefs.getString(STRING_KEYS[i],STRING_DEFAULTS[i]));
            for(int i=0;i<BOOL_KEYS.length;i++)settings.put(BOOL_KEYS[i],prefs.getBoolean(BOOL_KEYS[i],BOOL_DEFAULTS[i]));
            for(String key:OPTIONAL_INTS)if(prefs.contains(key))settings.put(key,prefs.getInt(key,0));
            if(prefs.contains("typing_keyboard"))settings.put("typing_keyboard",prefs.getString("typing_keyboard",""));
            JSONArray history;
            try {history=new JSONArray(prefs.getString("history","[]"));}
            catch(org.json.JSONException error) {throw invalid("Saved history could not be read. Your existing backup has been kept.");}
            JSONObject data=new JSONObject().put("format",FORMAT).put("schema",SCHEMA).put("created_at",System.currentTimeMillis()).put("settings",settings).put("history",history);
            JSONArray thoughts=new JSONArray();
            JSONArray notes=new JSONArray(prefs.getString("notes","[]"));
            for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null && note.optBoolean("thought")&&!note.optBoolean("recording")&&!note.optString("raw").trim().isEmpty()&&!note.optString("text").trim().isEmpty())thoughts.put(new JSONObject().put("id",note.optLong("id")).put("created",note.optLong("created",note.optLong("id"))).put("text",note.optString("text")).put("raw",note.optString("raw")).put("title",note.optString("title")).put("summary",note.optString("summary")).put("duration",note.optLong("duration")).put("lang",note.optString("lang","en")));}
            data.put("thoughts",thoughts); // Text only. Microphone recordings never enter an archive.
            String draft=MainActivity.currentDraft;if(draft!=null && !draft.isEmpty())data.put("draft",draft);
            return validate(data);
        }
    }

    /** Validate before confirmation and again before mutation. The returned tree shares no objects. */
    static JSONObject validate(JSONObject data) throws Exception {
        if(data==null || data.toString().getBytes(StandardCharsets.UTF_8).length>BackupArchive.MAX_BYTES-64)throw invalid("The backup data is missing or too large.");
        BackupJsonBounds.check(data.toString());
        if(!FORMAT.equals(string(data,"format",64,false)))throw invalid("This file does not contain VonoTation data.");
        if(integer(data,"schema",1,Integer.MAX_VALUE)!=SCHEMA)throw invalid("This backup version is not supported by this app.");
        long created=integer(data,"created_at",1,Long.MAX_VALUE);
        Object settingsValue=data.opt("settings"),historyValue=data.opt("history");
        if(!(settingsValue instanceof JSONObject) || !(historyValue instanceof JSONArray))throw invalid("The backup settings or history are not valid.");
        JSONObject source=(JSONObject)settingsValue,settings=new JSONObject();HashSet<String> allowed=new HashSet<>();
        for(String key:STRING_KEYS)allowed.add(key);for(String key:BOOL_KEYS)allowed.add(key);for(String key:OPTIONAL_INTS)allowed.add(key);allowed.add("typing_keyboard");
        Iterator<String> keys=source.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw invalid("The backup contains an unsupported setting.");
        for(int i=0;i<STRING_KEYS.length;i++) {
            String key=STRING_KEYS[i],value=source.has(key)?string(source,key,key.equals("dictionary")?100000:key.equals("api_base")?2048:256,true):STRING_DEFAULTS[i];
            if(key.equals("engine") && !oneOf(value,"system","offline","cloud"))throw invalid("The speech mode is not valid.");
            if(key.equals("engine"))value="cloud"; // Older Android/on-device choices restore as Cloud.
            if(key.equals("language"))value="en-US"; // English only.
            if(key.equals("bubble_size") && !oneOf(value,"small","medium","large"))throw invalid("The bubble size is not valid.");
            if(key.equals("backup_frequency") && !oneOf(value,"off","daily","weekly"))throw invalid("The backup schedule is not valid.");
            if(key.equals("language") && !value.matches("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))throw invalid("The speech language is not valid.");
            if(key.endsWith("_model") && (value.trim().isEmpty() || value.contains("\r") || value.contains("\n")))throw invalid("A saved model name is not valid.");
            if(key.equals("api_base"))value=CloudApi.validateBase(value);
            settings.put(key,value);
        }
        for(int i=0;i<BOOL_KEYS.length;i++) {
            String key=BOOL_KEYS[i];Object value=source.has(key)?source.opt(key):Boolean.valueOf(BOOL_DEFAULTS[i]);
            if(!(value instanceof Boolean))throw invalid("A saved switch value is not valid.");if(key.equals("bubble"))value=Boolean.TRUE;else if(key.equals("romanize"))value=Boolean.FALSE;settings.put(key,value);
        }
        for(String key:OPTIONAL_INTS)if(source.has(key))settings.put(key,(int)integer(source,key,0,1000000));
        if(source.has("typing_keyboard")) {
            String id=string(source,"typing_keyboard",1024,true);
            if(!id.isEmpty() && !id.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))throw invalid("The saved keyboard is not valid.");settings.put("typing_keyboard",id);
        }
        JSONArray old=(JSONArray)historyValue,history=new JSONArray();if(old.length()>BackupJsonBounds.MAX_HISTORY)throw invalid("The backup contains too many dictations.");HashSet<Long> ids=new HashSet<>();
        for(int i=0;i<old.length();i++) {
            Object value=old.opt(i);if(!(value instanceof JSONObject))throw invalid("A saved dictation is not valid.");JSONObject entry=(JSONObject)value;
            long id=integer(entry,"id",1,Long.MAX_VALUE);if(!ids.add(id))throw invalid("The backup contains duplicate dictations.");
            String text=string(entry,"text",1000000,false),raw=string(entry,"raw",1000000,true);if(text.trim().isEmpty())throw invalid("A saved dictation has no text.");
            long duration=integer(entry,"duration",0,Long.MAX_VALUE);integer(entry,"words",0,1000000);
            history.put(new JSONObject().put("id",id).put("text",text).put("raw",raw).put("duration",duration).put("words",Cleaner.words(text)));
        }
        // Schema 1 archives from earlier previews may contain an API key. Accept a valid
        // legacy field for compatibility, but never carry credentials into the restore tree.
        if(data.has("api_key")) {
            String legacy=string(data,"api_key",8192,true);
            if(legacy.contains("\r") || legacy.contains("\n"))throw invalid("The saved API connection is not valid.");
        }
        JSONObject checked=new JSONObject().put("format",FORMAT).put("schema",SCHEMA).put("created_at",created).put("settings",settings).put("history",history);
        JSONArray thoughts=new JSONArray();
        if(data.has("thoughts")){
            if(!(data.opt("thoughts") instanceof JSONArray))throw invalid("Saved thoughts are not valid.");
            JSONArray sourceThoughts=data.getJSONArray("thoughts");if(sourceThoughts.length()>BackupJsonBounds.MAX_THOUGHTS)throw invalid("The backup contains too many thoughts.");HashSet<Long> thoughtIds=new HashSet<>();
            for(int i=0;i<sourceThoughts.length();i++){
                JSONObject item=sourceThoughts.optJSONObject(i);if(item==null)throw invalid("A saved thought is not valid.");
                long id=integer(item,"id",1,Long.MAX_VALUE);if(!thoughtIds.add(id))throw invalid("The backup contains duplicate thoughts.");
                String text=string(item,"text",1000000,false),raw=string(item,"raw",1000000,false);if(text.trim().isEmpty() || raw.trim().isEmpty())throw invalid("A saved thought has no words.");
                thoughts.put(new JSONObject().put("id",id).put("created",integer(item,"created",1,Long.MAX_VALUE)).put("text",text).put("raw",raw).put("title",string(item,"title",256,true)).put("summary",item.has("summary")?string(item,"summary",1000000,true):"").put("duration",integer(item,"duration",0,Long.MAX_VALUE)).put("lang",string(item,"lang",32,true)));
            }
        }
        boolean thoughtsIncluded=data.has("thoughts");
        if(data.has("thoughts_included")){if(!(data.opt("thoughts_included") instanceof Boolean))throw invalid("The thought backup marker is not valid.");thoughtsIncluded=data.getBoolean("thoughts_included");}
        if(!thoughtsIncluded && thoughts.length()>0)throw invalid("The thought backup marker conflicts with its contents.");
        checked.put("thoughts",thoughts).put("thoughts_included",thoughtsIncluded);
        if(data.has("draft"))checked.put("draft",string(data,"draft",1000000,true));return checked;
    }

    /** Hash the exact portable data, excluding only the archive's creation timestamp.
     * Sorted keys make status independent of a JSON implementation's map order. */
    static String fingerprint(JSONObject payload) throws Exception {
        JSONObject checked=validate(payload);checked.remove("created_at");
        StringBuilder canonical=new StringBuilder();appendCanonical(canonical,checked);
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte value:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return hex.toString();
    }
    private static void appendCanonical(StringBuilder out,Object value) throws Exception {
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;java.util.TreeSet<String> keys=new java.util.TreeSet<>();Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());
            out.append('{');boolean first=true;for(String key:keys){if(!first)out.append(',');first=false;out.append(JSONObject.quote(key)).append(':');appendCanonical(out,object.get(key));}out.append('}');
        } else if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;out.append('[');for(int i=0;i<array.length();i++){if(i>0)out.append(',');appendCanonical(out,array.get(i));}out.append(']');
        } else if(value instanceof String)out.append(JSONObject.quote((String)value));
        else out.append(String.valueOf(value));
    }

    static void restore(Context context,JSONObject payload) throws Exception {
        JSONObject checked=validate(payload);
        synchronized(Store.class) {
            SharedPreferences prefs=Store.prefs(context);Map<String,?> before=prefs.getAll();SharedPreferences.Editor editor=prefs.edit();
            JSONObject settings=checked.getJSONObject("settings");
            // A local key belongs to the complete API base, including its path. Retain it
            // only for the same validated provider connection; clear it in the same commit
            // as a changed endpoint so cloud requests cannot send it to another provider.
            boolean sameConnection=false;
            try {sameConnection=CloudApi.validateBase(prefs.getString("api_base",CloudApi.DEFAULT_BASE)).equals(settings.getString("api_base"));}
            catch(Exception invalidLocalBase) {/* The restored valid endpoint requires a new key. */}
            if(!sameConnection)editor.remove("api_secret").remove("api_iv");
            for(String key:STRING_KEYS)editor.remove(key);for(String key:BOOL_KEYS)editor.remove(key);for(String key:OPTIONAL_INTS)editor.remove(key);editor.remove("typing_keyboard");
            Iterator<String> keys=settings.keys();while(keys.hasNext()) {
                String key=keys.next();Object value=settings.get(key);
                if(key.equals("typing_keyboard") && !enabledKeyboard(context,(String)value))continue;
                if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else editor.putInt(key,((Number)value).intValue());
            }
            editor.putString("history",checked.getJSONArray("history").toString()).remove("history_summaries").remove("thought_summaries");
            // Restore portable thought text; retain this phone's unrelated recordings.
            JSONArray restoredThoughts=checked.getJSONArray("thoughts"),mergedNotes=new JSONArray(),existingNotes=new JSONArray(prefs.getString("notes","[]"));
            HashSet<Long> mergedIds=new HashSet<>(),attachedSources=new HashSet<>();
            for(int i=0;i<restoredThoughts.length();i++){
                JSONObject thought=new JSONObject(restoredThoughts.getJSONObject(i).toString());long id=thought.getLong("id");mergedIds.add(id);
                thought.put("thought",true).put("audio","").put("pending",false).put("recording",false).put("revision",System.nanoTime());
                // Source audio is local-only. Never attach a recording merely because an ID collided.
                for(int j=0;j<existingNotes.length();j++){JSONObject local=existingNotes.optJSONObject(j);if(local!=null&&local.optBoolean("thought")&&local.optLong("id")==id&&!local.optString("raw").isEmpty()&&local.optString("raw").equals(thought.optString("raw"))){attachLocalThoughtSource(thought,local);attachedSources.add(id);break;}}
                mergedNotes.put(thought);
            }
            for(int i=0;i<existingNotes.length();i++){JSONObject note=existingNotes.optJSONObject(i);if(note==null)continue;boolean localSource=!note.optString("audio").isEmpty()||(note.optJSONArray("parts")!=null&&note.optJSONArray("parts").length()>0);boolean retain=!note.optBoolean("thought")||!checked.optBoolean("thoughts_included")||(localSource&&!attachedSources.contains(note.optLong("id")));if(retain){if(mergedIds.contains(note.optLong("id")))note.put("id",Store.nextId(mergedNotes,System.currentTimeMillis()));mergedIds.add(note.optLong("id"));mergedNotes.put(note);}}
            editor.putString("notes",mergedNotes.toString());
            DraftState.invalidate(editor);
            if(!editor.commit()) {
                // SharedPreferences changes memory even when disk writes fail; restore its old map too.
                SharedPreferences.Editor rollback=prefs.edit().clear();for(Map.Entry<String,?> item:before.entrySet())writePreference(rollback,item.getKey(),item.getValue());
                boolean rolledBack=rollback.commit();throw invalid(rolledBack?"Could not save the restored data. Your previous data has been kept.":"The phone could not save restore changes. Check available storage before trying again.");
            }
            DraftState.set(checked.optString("draft",""));
        }
    }

    /** Local source work stays local, including successful segments awaiting a later retry. */
    private static void attachLocalThoughtSource(JSONObject thought,JSONObject local) throws Exception {
        thought.put("audio",local.optString("audio"));
        if(local.has("parts"))thought.put("parts",local.getJSONArray("parts"));
        if(local.has("wave"))thought.put("wave",local.optString("wave"));
        if(!local.optBoolean("pending"))return;
        boolean hasSource=!local.optString("audio").isEmpty() || (local.optJSONArray("parts")!=null&&local.optJSONArray("parts").length()>0);
        if(!hasSource)return;
        thought.put("pending",true).put("duration",Math.max(thought.optLong("duration"),local.optLong("duration")));
        String[] workKeys={"partial_transcript","segment_transcripts","transcription_config","retry_after","retry_count","retry_language","replacement_pending","transcription_error","thought_capture_complete","source_interrupted","minimum_thought_lines","capture_error","heard"};
        for(String key:workKeys)if(local.has(key))thought.put(key,local.get(key));
        // The cached configuration belongs to the local source's language, even if
        // the archive's portable language is older. Preserve it for this retry only.
        if(!local.has("retry_language"))thought.put("retry_language",local.optString("lang","auto"));
        // A resumed transcription may add missing source words, but cannot replace
        // an archived edit or a matching local edit with the recognizer's body.
        if(local.optBoolean("user_edited") || !thought.optString("text").equals(local.optString("text")))thought.put("user_edited",true);
    }

    static String describe(JSONObject payload) {
        if(payload==null)return "VonoTation backup";
        JSONArray history=payload.optJSONArray("history");int count=history==null?0:history.length();
        String date=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(payload.optLong("created_at")));
        JSONArray thoughts=payload.optJSONArray("thoughts");int thoughtCount=thoughts==null?0:thoughts.length();
        return date+" · "+count+" "+(count==1?"dictation":"dictations")+(thoughtCount>0?" · "+thoughtCount+" thoughts":"")+" · settings and dictionary";
    }
    private static boolean enabledKeyboard(Context context,String id) {
        if(id.isEmpty())return false;
        try {InputMethodManager manager=(InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE);if(manager!=null)for(InputMethodInfo info:manager.getEnabledInputMethodList())if(id.equals(info.getId()))return true;}
        catch(RuntimeException ignored){}return false;
    }
    private static void writePreference(SharedPreferences.Editor edit,String key,Object value) {
        if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof java.util.Set){@SuppressWarnings("unchecked")java.util.Set<String> set=(java.util.Set<String>)value;edit.putStringSet(key,set);}
    }
    private static String string(JSONObject object,String key,int limit,boolean empty) throws IOException {
        Object value=object.opt(key);if(!(value instanceof String))throw invalid("A backup field is not valid.");String text=(String)value;if(text.length()>limit || (!empty && text.isEmpty()))throw invalid("A backup field is missing or too long.");return text;
    }
    private static long integer(JSONObject object,String key,long min,long max) throws IOException {
        Object value=object.opt(key);if(!(value instanceof Integer) && !(value instanceof Long))throw invalid("A backup number is not valid.");long number=((Number)value).longValue();if(number<min || number>max)throw invalid("A backup number is out of range.");return number;
    }
    private static boolean oneOf(String value,String... allowed){for(String item:allowed)if(item.equals(value))return true;return false;}
    private static IOException invalid(String message){return new IOException(message);}
    private BackupData(){}
}
