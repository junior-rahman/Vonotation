package app.voiceflow;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

final class Store {
    static void removeStaleAudio(Context c) { long cutoff=System.currentTimeMillis()-24*60*60*1000L;java.io.File[] files=c.getCacheDir().listFiles();if(files!=null)for(java.io.File file:files)if(file.getName().startsWith("voiceflow-") && file.getName().endsWith(".m4a") && file.lastModified()<cutoff)file.delete(); }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("voiceflow", Context.MODE_PRIVATE); }
    static String get(Context c, String key, String fallback) { return prefs(c).getString(key, fallback); }
    /**
     * Cloud (auto-detect) is the only transcription engine from 1.9.15 build 26. Older saved
     * values ("system" Android speech, "offline" on-device) and restored backups are treated
     * as cloud; the dormant Android/on-device code paths are never selected.
     */
    static String engine(Context c) { return "cloud"; }
    /** The floating bubble is always on (the old "Show bubble" switch was removed). */
    static boolean bubble(Context c) { return true; }
    /** Optional no-input capture; disabling hides the feature and retains saved thoughts. */
    static boolean quickThoughts(Context c) { return bool(c,"quick_thoughts",false); }
    /** English only: Cloud transcription is sent language=en; English-letter conversion is retired. */
    static String language(Context c) { return "en"; }
    static boolean romanize(Context c) { return false; }
    /**
     * Rewrites retired preferences so saved settings and backups agree with the fixed
     * behaviour: Cloud engine, bubble on, English, no English-letter conversion.
     */
    static void migrate(Context c) {
        android.content.SharedPreferences p = prefs(c);
        if (!"cloud".equals(p.getString("engine", null)) || !p.getBoolean("bubble", true) || p.getBoolean("romanize", false) || !"en-US".equals(p.getString("language", "en-US")))
            p.edit().putString("engine", "cloud").putBoolean("bubble", true).putBoolean("romanize", false).putString("language", "en-US").apply();
    }
    static boolean bool(Context c, String key, boolean fallback) { return prefs(c).getBoolean(key, fallback); }
    static JSONArray history(Context c) {
        try { return new JSONArray(get(c, "history", "[]")); } catch (Exception e) { return new JSONArray(); }
    }
    static synchronized void save(Context c, String raw, String text, long duration) {
        if (text.trim().isEmpty() || !bool(c, "save_history", true)) return;
        try {
            JSONArray old = history(c), next = new JSONArray();
            next.put(new JSONObject().put("id", nextId(old,System.currentTimeMillis())).put("text", text).put("raw", raw).put("duration", duration).put("words", Cleaner.words(text)));
            for (int i = 0; i < Math.min(old.length(), 99); i++) next.put(old.get(i));
            prefs(c).edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) {}
    }
    static long nextId(JSONArray history,long now){
        java.util.HashSet<Long> used=new java.util.HashSet<>();
        for(int i=0;i<history.length();i++){JSONObject entry=history.optJSONObject(i);if(entry!=null)used.add(entry.optLong("id"));}
        while(used.contains(now))now++;
        return now;
    }
    static synchronized void delete(Context c, long id) {
        DailySummaryCache.invalidateEntry(c,id);
        JSONArray old = history(c), next = new JSONArray();boolean removed=false,discard=false;
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item == null) continue;
            if (item.optLong("id") == id){removed=true;discard|=draftMatches(item);}
            else next.put(item);
        }
        android.content.SharedPreferences.Editor editor=prefs(c).edit().putString("history",next.toString());
        if(removed)DraftState.invalidate(editor);editor.apply();
        if(discard)DraftState.set("");
    }
    static synchronized void clearHistory(Context c) {
        JSONArray old=history(c);boolean discard=false;
        for(int i=0;i<old.length();i++)discard|=draftMatches(old.optJSONObject(i));
        android.content.SharedPreferences.Editor editor=prefs(c).edit().remove("history").remove("history_summaries");
        DraftState.invalidate(editor);editor.apply();if(discard)DraftState.set("");
    }
    private static boolean draftMatches(JSONObject entry) {
        if(entry==null)return false;
        String draft=MainActivity.currentDraft;
        // The retained draft is the fallback copy of a completed dictation. Remove
        // that duplicate with its history entry, while keeping unrelated unsaved text.
        return draft!=null && !draft.isEmpty()
                && (draft.equals(entry.optString("text")) || draft.equals(entry.optString("raw")));
    }
    static boolean verbatim(Context c) { return bool(c, "verbatim", false); }
    /** Verbatim mode returns the recognizer text unchanged, including whitespace; no cleanup, commands or dictionary. */
    static String clean(Context c, String text) { return verbatim(c) ? Cleaner.verbatim(text) : Cleaner.clean(text, bool(c, "clean", true), bool(c, "spoken_punctuation", true), get(c, "dictionary", "")); }
}
