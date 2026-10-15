package app.voiceflow;

import android.content.Context;
import org.json.JSONObject;
import java.util.*;

/** Cached recaps are valid only for the current day contents, model, endpoint and timezone. */
final class DailySummaryCache {
    private static JSONObject read(Context context){try{return new JSONObject(Store.get(context,"history_summaries","{}"));}catch(Exception ignored){return new JSONObject();}}
    static String get(Context context,HistoryDays.Day day,String model,String endpoint){JSONObject item=read(context).optJSONObject(day.key);return item!=null && item.optString("fingerprint").equals(HistoryDays.fingerprint(day,model,endpoint))?item.optString("text",""):"";}
    static void put(Context context,HistoryDays.Day day,String model,String endpoint,String text){try{JSONObject cache=read(context);org.json.JSONArray ids=new org.json.JSONArray();for(HistoryDays.Entry entry:day.entries)ids.put(entry.id);cache.put(day.key,new JSONObject().put("fingerprint",HistoryDays.fingerprint(day,model,endpoint)).put("model",model).put("entry_ids",ids).put("text",text));Store.prefs(context).edit().putString("history_summaries",cache.toString()).apply();}catch(Exception ignored){}}
    static void invalidateEntry(Context context,long id){JSONObject cache=read(context);List<String> removed=new ArrayList<>();Iterator<String> keys=cache.keys();while(keys.hasNext()){String key=keys.next();JSONObject item=cache.optJSONObject(key);org.json.JSONArray ids=item==null?null:item.optJSONArray("entry_ids");if(ids==null){removed.add(key);continue;}for(int i=0;i<ids.length();i++)if(ids.optLong(i)==id){removed.add(key);break;}}for(String key:removed)cache.remove(key);if(!removed.isEmpty())Store.prefs(context).edit().putString("history_summaries",cache.toString()).apply();}
    static void prune(Context context,List<HistoryDays.Day> days){JSONObject cache=read(context);Set<String> active=new HashSet<>();for(HistoryDays.Day day:days)active.add(day.key);List<String> removed=new ArrayList<>();Iterator<String> keys=cache.keys();while(keys.hasNext()){String key=keys.next();if(!active.contains(key))removed.add(key);}for(String key:removed)cache.remove(key);if(!removed.isEmpty())Store.prefs(context).edit().putString("history_summaries",cache.toString()).apply();}
    private DailySummaryCache(){}
}
