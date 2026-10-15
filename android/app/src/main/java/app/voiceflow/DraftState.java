package app.voiceflow;

import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;

/** The live process owns its draft; an Activity Bundle only recovers a lost process. */
final class DraftState {
    private static final String SESSION=java.util.UUID.randomUUID().toString();
    private static long revision;
    private static boolean authoritative;
    private static final String EPOCH="draft_epoch";

    /** Invalidate older Activity state in the same preferences mutation as user data. */
    static void invalidate(SharedPreferences.Editor editor) {
        editor.putString(EPOCH,java.util.UUID.randomUUID().toString());
    }

    static void set(String text) {
        synchronized(Store.class) {
            MainActivity.currentDraft=text==null?"":text;
            revision++;
            authoritative=true;
        }
    }

    static void save(Context context,Bundle state) {
        synchronized(Store.class) {
            state.putString("draft",MainActivity.currentDraft);
            state.putString("draft_session",SESSION);
            state.putLong("draft_revision",revision);
            state.putString(EPOCH,Store.prefs(context).getString(EPOCH,""));
        }
    }

    static void restore(Context context,Bundle state) {
        if(state==null || !state.containsKey("draft"))return;
        synchronized(Store.class) {
            // Restore/delete/clear may have happened while this Activity was stopped,
            // followed by process death. The persisted epoch still revokes its old
            // Bundle even though this process has a new session and empty memory.
            // Legacy Bundles are accepted only before any such local invalidation.
            if(!Store.prefs(context).getString(EPOCH,"").equals(state.getString(EPOCH,"")))return;
            // In the same process only the matching revision may be reapplied. An
            // older Bundle must never undo a restore, deletion or new dictation.
            if(SESSION.equals(state.getString("draft_session",""))) {
                if(state.getLong("draft_revision",-1)!=revision)return;
                MainActivity.currentDraft=state.getString("draft","");
                authoritative=true;
                return;
            }
            // A new process may recover its Bundle once. New work completed before
            // this Activity opens takes precedence over any older process's Bundle.
            // Missing session metadata supports saved state from earlier previews.
            if(!authoritative) {
                MainActivity.currentDraft=state.getString("draft","");
                revision++;
                authoritative=true;
            }
        }
    }

    private DraftState(){}
}
