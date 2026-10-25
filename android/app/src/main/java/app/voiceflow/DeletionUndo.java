package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local 24-hour undo journal; removal and snapshot share one durable preference commit. */
final class DeletionUndo {
    static final long KEEP=24*60*60*1000L;
    private static final String KEY="recent_deletions";
    static JSONArray list(Context c){synchronized(Store.class){
        JSONArray kept=new JSONArray();long now=System.currentTimeMillis();
        try{JSONArray old=new JSONArray(Store.get(c,KEY,"[]"));for(int i=0;i<old.length();i++){JSONObject row=old.getJSONObject(i);if(now-row.optLong("deleted_at")<KEEP)kept.put(row);else if("thought".equals(row.optString("kind")))Notes.purgeUndoAudio(c,row.getJSONObject("entry"));}
            if(kept.length()!=old.length())Store.prefs(c).edit().putString(KEY,kept.toString()).commit();
        }catch(Exception ignored){}return kept;
    }}
    static boolean delete(Context c,String kind,long id){synchronized(Store.class){
        String key="dictation".equals(kind)?"history":"notes";
        try{JSONArray old=new JSONArray(Store.get(c,key,"[]")),next=new JSONArray(),undo=list(c);JSONObject found=null;
            for(int i=0;i<old.length();i++){JSONObject row=old.getJSONObject(i);if(row.optLong("id")==id)found=row;else next.put(row);}
            if(found==null||("thought".equals(kind)&&(!found.optBoolean("thought")||found.optBoolean("recording"))))return false;
            undo.put(new JSONObject().put("kind",kind).put("id",id).put("deleted_at",System.currentTimeMillis()).put("entry",found));
            String[] rollbackKeys={key,KEY,"thought_deletions","note_deletions","draft_epoch"};String[] before=new String[rollbackKeys.length];for(int i=0;i<before.length;i++)before[i]=Store.prefs(c).getString(rollbackKeys[i],null);
            android.content.SharedPreferences.Editor edit=Store.prefs(c).edit().putString(key,next.toString()).putString(KEY,undo.toString());
            if("thought".equals(kind)){edit.putString("thought_deletions",ids(c,"thought_deletions",id,true).toString()).putString("note_deletions",ids(c,"note_deletions",id,true).toString());}
            else DraftState.invalidate(edit);
            boolean ok=commit(c,edit,rollbackKeys,before);if(ok){if("thought".equals(kind))DailySummaryCache.invalidateEntry(c,"thought",id);else{DailySummaryCache.invalidateEntry(c,id);if(found.optString("text").equals(MainActivity.currentDraft)||found.optString("raw").equals(MainActivity.currentDraft))DraftState.set("");}}return ok;
        }catch(Exception failure){return false;}
    }}
    static boolean restore(Context c,String kind,long id){synchronized(Store.class){
        if("clipboard".equals(kind))return ClipboardArchive.get(c).undo(id,System.currentTimeMillis());
        try{JSONArray undo=list(c),remaining=new JSONArray();JSONObject entry=null;
            for(int i=0;i<undo.length();i++){JSONObject row=undo.getJSONObject(i);if(kind.equals(row.optString("kind"))&&id==row.optLong("id"))entry=row.getJSONObject("entry");else remaining.put(row);}
            if(entry==null)return false;String key="dictation".equals(kind)?"history":"notes";JSONArray current=new JSONArray(Store.get(c,key,"[]")),next=new JSONArray();
            for(int i=0;i<current.length();i++)if(current.getJSONObject(i).optLong("id")==id)return false;
            if("thought".equals(kind))entry.put("revision",entry.optLong("revision",1)+1).put("recording",false);
            next.put(entry);for(int i=0;i<current.length();i++)next.put(current.get(i));
            String[] rollbackKeys={key,KEY,"thought_deletions","note_deletions","draft_epoch"};String[] before=new String[rollbackKeys.length];for(int i=0;i<before.length;i++)before[i]=Store.prefs(c).getString(rollbackKeys[i],null);
            android.content.SharedPreferences.Editor edit=Store.prefs(c).edit().putString(key,next.toString()).putString(KEY,remaining.toString());
            if("thought".equals(kind))edit.putString("thought_deletions",ids(c,"thought_deletions",id,false).toString()).putString("note_deletions",ids(c,"note_deletions",id,false).toString());
            return commit(c,edit,rollbackKeys,before);
        }catch(Exception failure){return false;}
    }}
    private static boolean commit(Context c,android.content.SharedPreferences.Editor edit,String[] keys,String[] before){
        if(edit.commit())return true;
        android.content.SharedPreferences.Editor rollback=Store.prefs(c).edit();for(int i=0;i<keys.length;i++){if(before[i]==null)rollback.remove(keys[i]);else rollback.putString(keys[i],before[i]);}rollback.commit();return false;
    }
    private static JSONArray ids(Context c,String key,long id,boolean add)throws Exception{JSONArray result=new JSONArray(),old=new JSONArray(Store.get(c,key,"[]"));if(add)result.put(id);for(int i=0;i<old.length();i++)if(old.optLong(i)!=id)result.put(old.optLong(i));return result;}
    static void offer(Activity activity,String kind,long id,Runnable refresh){
        new AlertDialog.Builder(activity).setTitle("Deleted").setMessage("Undo is available for 24 hours in Settings → Recently deleted. Clipboard copies still expire on their original date.").setNegativeButton("Close",null).setPositiveButton("Undo",(d,w)->{boolean ok=restore(activity,kind,id);android.widget.Toast.makeText(activity,ok?"Restored.":"Could not restore. It may have expired.",android.widget.Toast.LENGTH_SHORT).show();refresh.run();}).show();
    }
    private DeletionUndo(){}
}
