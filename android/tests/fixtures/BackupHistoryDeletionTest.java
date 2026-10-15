package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Deleted history must not return through the retained draft in a newly made archive. */
public final class BackupHistoryDeletionTest extends Instrumentation {
    private int checks;
    private SharedPreferences isolated;
    private Context context;

    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart() {
        Bundle report=new Bundle();String oldDraft=MainActivity.currentDraft;
        isolated=getTargetContext().getSharedPreferences("backup-history-deletion-test",Context.MODE_PRIVATE);
        context=new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return isolated;}
        };
        try {
            seed("Deleted clean sentinel","Deleted raw sentinel");
            DraftState.set("Deleted clean sentinel");
            Store.delete(context,101L);
            JSONObject deleted=newArchive();
            require(!deleted.toString().contains("Deleted clean sentinel") && !deleted.toString().contains("Deleted raw sentinel"),
                    "Deleted text returned in the new encrypted backup");
            require(deleted.getJSONArray("history").length()==1 && !deleted.has("draft"),
                    "Deleting the draft's entry did not preserve only the unrelated saved entry");
            require(!isolated.getString("history_summaries","{}").contains("Deleted clean sentinel"),
                    "Deleted entry's cached recap was retained");

            seed("Deleted clean sentinel","Deleted raw sentinel");
            DraftState.set("Deleted raw sentinel");
            Store.delete(context,101L);
            require(!BackupData.snapshot(context).toString().contains("Deleted raw sentinel"),"Deleting a raw-text draft kept deleted content");

            seed("Deleted clean sentinel","Deleted raw sentinel");
            DraftState.set("Unrelated unsaved draft sentinel");
            Store.delete(context,101L);
            JSONObject unrelated=BackupData.snapshot(context);
            require(unrelated.getString("draft").equals("Unrelated unsaved draft sentinel"),"Deleting an older entry removed an unrelated new draft");
            require(!unrelated.toString().contains("Deleted clean sentinel") && !unrelated.toString().contains("Deleted raw sentinel"),
                    "Deleted history remained when the independent draft was preserved");

            seed("Clear clean sentinel","Clear raw sentinel");
            DraftState.set("Clear clean sentinel");
            Store.clearHistory(context);
            JSONObject cleared=newArchive();
            require(cleared.getJSONArray("history").length()==0 && !cleared.has("draft"),"Clearing history retained its stale draft");
            require(!cleared.toString().contains("Clear clean sentinel") && !cleared.toString().contains("Clear raw sentinel")
                    && !cleared.toString().contains("Other saved sentinel"),"Cleared content returned in a newly created encrypted backup");
            require(!isolated.contains("history_summaries"),"Clearing history retained daily recaps");

            seed("Clear clean sentinel","Clear raw sentinel");
            DraftState.set("Unrelated unsaved draft sentinel");
            Store.clearHistory(context);
            JSONObject preserved=BackupData.snapshot(context);
            require(preserved.getJSONArray("history").length()==0 && preserved.getString("draft").equals("Unrelated unsaved draft sentinel"),
                    "Clearing saved history removed unrelated unsaved text");

            seed("Unchanged saved sentinel","Unchanged raw sentinel");
            DraftState.set("Unchanged saved sentinel");
            Store.delete(context,999L);
            JSONObject absent=BackupData.snapshot(context);
            require(absent.getJSONArray("history").length()==2 && absent.getString("draft").equals("Unchanged saved sentinel"),
                    "Deleting an absent entry changed the draft or saved history");
            report.putString("result","PASS: "+checks+" synthetic history/draft deletion checks using actual snapshot and encrypted archive round trips; no Google authorization or Drive request");
        } catch(Throwable failure) {report.putString("failure",failure.toString());}
        finally {DraftState.set(oldDraft);isolated.edit().clear().commit();}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void seed(String text,String raw) throws Exception {
        JSONArray entries=new JSONArray().put(entry(101L,text,raw)).put(entry(102L,"Other saved sentinel","Other raw sentinel"));
        JSONObject summaries=new JSONObject().put("fixture-day",new JSONObject().put("text",text+" recap")
                .put("entry_ids",new JSONArray().put(101L)));
        require(isolated.edit().clear().putString("history",entries.toString()).putString("history_summaries",summaries.toString()).commit(),
                "Could not seed isolated fixture data");
    }
    private JSONObject entry(long id,String text,String raw) throws Exception {
        return new JSONObject().put("id",id).put("text",text).put("raw",raw).put("duration",1000L).put("words",Cleaner.words(text));
    }
    private JSONObject newArchive() throws Exception {
        char[] password="fixture archive password".toCharArray();byte[] encrypted=null;
        try {encrypted=BackupArchive.encode(BackupData.snapshot(context),password);return BackupData.validate(BackupArchive.decode(encrypted,password));}
        finally {Arrays.fill(password,'\0');if(encrypted!=null)Arrays.fill(encrypted,(byte)0);}
    }
    private void require(boolean pass,String message) throws Exception {checks++;if(!pass)throw new Exception(message);}
}
