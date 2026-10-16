package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Actual preference-backed cache checks; synthetic days only, no microphone, provider, or user data. */
public final class ThoughtDailySummaryCacheTest extends Instrumentation {
    private static final String HISTORY="history",THOUGHT="thought";
    private static final String MODEL="cache-fixture-model-a",ENDPOINT="https://cache-fixture.invalid/openai/v1";
    private static final TimeZone UTC=TimeZone.getTimeZone("UTC");
    private static final long DAY=24L*60*60*1000;
    private SharedPreferences isolated;
    private Context context;
    private int checks;

    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle report=new Bundle();
        isolated=getTargetContext().getSharedPreferences("thought-daily-summary-cache-test",Context.MODE_PRIVATE);
        context=new ContextWrapper(getTargetContext()){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return isolated;}
        };
        try{
            scopeIsolation();
            fingerprintChanges();
            scopedPruning();
            scopedEntryInvalidation();
            concurrentDayWrites();
            report.putString("result","PASS: "+checks+" actual preference-backed checks for same-date history/thought isolation, fingerprint changes, scoped pruning/deletion and concurrent day writes; no provider or capture.");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{isolated.edit().clear().commit();}
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void scopeIsolation()throws Exception{
        reset();
        HistoryDays.Day history=day(base(),"The same words may belong to either category.",UTC);
        HistoryDays.Day thought=day(base(),"The same words may belong to either category.",UTC);
        require(history.key.equals(thought.key),"Fixture does not share the same calendar date.");
        DailySummaryCache.put(context,history,MODEL,ENDPOINT,"History recap");
        DailySummaryCache.put(context,THOUGHT,thought,MODEL,ENDPOINT,"Thought recap");
        require("History recap".equals(DailySummaryCache.get(context,history,MODEL,ENDPOINT)),"Thought put replaced the default history recap.");
        require("History recap".equals(get(HISTORY,history)),"Explicit history scope differs from the existing default API.");
        require("Thought recap".equals(get(THOUGHT,thought)),"History and thought with identical day inputs collided.");
        require("History recap".equals(new JSONObject(Store.get(context,"history_summaries","{}")).getJSONObject(history.key).getString("text")),"History recap is not in its own preferences bucket.");
        require("Thought recap".equals(new JSONObject(Store.get(context,"thought_summaries","{}")).getJSONObject(thought.key).getString("text")),"Thought recap is not in its own preferences bucket.");
    }

    private void fingerprintChanges()throws Exception{
        reset();
        HistoryDays.Day original=day(base(),"Buy milk. Maybe call Juel.",UTC);
        HistoryDays.Day edited=day(base(),"Buy oat milk. Maybe call Juel.",UTC);
        HistoryDays.Day changedSource=HistoryDays.group(Collections.singletonList(new HistoryDays.Entry(
                base(),"Buy milk. Maybe call Juel.",6,"The original source has different words.")),UTC).get(0);
        HistoryDays.Day added=HistoryDays.group(Arrays.asList(
                new HistoryDays.Entry(base(),"Buy milk. Maybe call Juel.",6),
                new HistoryDays.Entry(base()+1000,"Another saved thought.",3)),UTC).get(0);
        HistoryDays.Day otherZone=day(base(),"Buy milk. Maybe call Juel.",TimeZone.getTimeZone("America/New_York"));
        HistoryDays.Day otherDate=day(base()+DAY,"Buy milk. Maybe call Juel.",UTC);
        require(original.key.equals(otherZone.key),"Timezone fixture must retain the same date key to isolate timezone identity.");
        require(!original.key.equals(otherDate.key),"Date fixture must move to a different calendar day.");
        for(String scope:new String[]{HISTORY,THOUGHT}){
            DailySummaryCache.put(context,scope,original,MODEL,ENDPOINT,scope+" original");
            require(get(scope,edited).isEmpty(),scope+" reused a recap after an entry text edit.");
            if(THOUGHT.equals(scope))require(get(scope,changedSource).isEmpty(),"Thought recap ignored an original-source change.");
            else require((scope+" original").equals(get(scope,changedSource)),"Default history cache compatibility changed for raw-only differences.");
            require(get(scope,added).isEmpty(),scope+" reused a recap after a new same-day entry.");
            require(DailySummaryCache.get(context,scope,original,"cache-fixture-model-b",ENDPOINT).isEmpty(),scope+" ignored a model change.");
            require(DailySummaryCache.get(context,scope,original,MODEL,"https://other-cache-fixture.invalid/openai/v1").isEmpty(),scope+" ignored an endpoint change.");
            require(get(scope,otherZone).isEmpty(),scope+" ignored a timezone change with the same date key.");
            require(get(scope,otherDate).isEmpty(),scope+" reused yesterday's recap for a different date.");
            require((scope+" original").equals(get(scope,original)),scope+" mismatch checks destroyed an otherwise valid original recap.");
        }
    }

    private void scopedPruning()throws Exception{
        reset();
        HistoryDays.Day first=day(base(),"First calendar day.",UTC),second=day(base()+DAY,"Second calendar day.",UTC);
        for(String scope:new String[]{HISTORY,THOUGHT}){
            DailySummaryCache.put(context,scope,first,MODEL,ENDPOINT,scope+" first");
            DailySummaryCache.put(context,scope,second,MODEL,ENDPOINT,scope+" second");
        }
        DailySummaryCache.prune(context,HISTORY,Collections.singletonList(first));
        require("history first".equals(get(HISTORY,first))&&get(HISTORY,second).isEmpty(),"History prune did not keep exactly its active date.");
        require("thought first".equals(get(THOUGHT,first))&&"thought second".equals(get(THOUGHT,second)),"History pruning removed thought dates.");
        DailySummaryCache.prune(context,THOUGHT,Collections.singletonList(second));
        require(get(THOUGHT,first).isEmpty()&&"thought second".equals(get(THOUGHT,second)),"Thought prune did not keep exactly its active date.");
        require("history first".equals(get(HISTORY,first)),"Thought pruning removed the surviving history date.");
        DailySummaryCache.prune(context,Collections.<HistoryDays.Day>emptyList());
        require(get(HISTORY,first).isEmpty(),"Default prune did not clear inactive history.");
        require("thought second".equals(get(THOUGHT,second)),"Default history prune touched thought summaries.");
    }

    private void scopedEntryInvalidation()throws Exception{
        reset();
        HistoryDays.Day shared=day(base(),"Shared timestamp across independent categories.",UTC);
        HistoryDays.Day unrelated=day(base()+DAY,"Unrelated saved entry.",UTC);
        for(String scope:new String[]{HISTORY,THOUGHT}){
            DailySummaryCache.put(context,scope,shared,MODEL,ENDPOINT,scope+" shared");
            DailySummaryCache.put(context,scope,unrelated,MODEL,ENDPOINT,scope+" unrelated");
        }
        DailySummaryCache.invalidateEntry(context,THOUGHT,base());
        require(get(THOUGHT,shared).isEmpty(),"Thought deletion kept its own stale recap.");
        require("history shared".equals(get(HISTORY,shared)),"Thought deletion invalidated a matching history ID.");
        require("thought unrelated".equals(get(THOUGHT,unrelated)),"Thought deletion removed an unrelated thought date.");
        DailySummaryCache.put(context,THOUGHT,shared,MODEL,ENDPOINT,"thought restored");
        DailySummaryCache.invalidateEntry(context,base());
        require(get(HISTORY,shared).isEmpty(),"Default entry deletion kept its history recap.");
        require("thought restored".equals(get(THOUGHT,shared)),"Default history deletion invalidated a matching thought ID.");
        DailySummaryCache.invalidateEntry(context,HISTORY,base()+17*DAY);
        DailySummaryCache.invalidateEntry(context,THOUGHT,base()+17*DAY);
        require("history unrelated".equals(get(HISTORY,unrelated))&&"thought unrelated".equals(get(THOUGHT,unrelated)),"Deleting an absent ID removed unrelated recaps.");
    }

    private void concurrentDayWrites()throws Exception{
        reset();
        final int count=8,rounds=4;
        CountDownLatch ready=new CountDownLatch(count),start=new CountDownLatch(1),done=new CountDownLatch(count);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        List<Thread> writers=new ArrayList<>();
        try{
            for(int writer=0;writer<count;writer++){
                final int index=writer;
                Thread thread=new Thread(()->{
                    ready.countDown();
                    try{
                        if(!start.await(10,TimeUnit.SECONDS))throw new AssertionError("Concurrent fixture did not start.");
                        for(int round=0;round<rounds;round++){
                            int slot=index*rounds+round;
                            HistoryDays.Day day=day(base()+slot*DAY,"Concurrent saved entry "+slot,UTC);
                            DailySummaryCache.put(context,HISTORY,day,MODEL,ENDPOINT,"history "+slot);
                            DailySummaryCache.put(context,THOUGHT,day,MODEL,ENDPOINT,"thought "+slot);
                        }
                    }catch(Throwable error){failure.compareAndSet(null,error);}
                    finally{done.countDown();}
                },"summary-cache-fixture-"+writer);
                writers.add(thread);thread.start();
            }
            require(ready.await(10,TimeUnit.SECONDS),"Concurrent fixture writers did not become ready.");
            start.countDown();
            require(done.await(20,TimeUnit.SECONDS),"Concurrent cache writes did not finish.");
            if(failure.get()!=null)throw new Exception("Concurrent cache writer failed",failure.get());
            for(int slot=0;slot<count*rounds;slot++){
                HistoryDays.Day day=day(base()+slot*DAY,"Concurrent saved entry "+slot,UTC);
                require(("history "+slot).equals(get(HISTORY,day)),"Concurrent writes lost history day "+slot);
                require(("thought "+slot).equals(get(THOUGHT,day)),"Concurrent writes lost thought day "+slot);
            }
        }finally{
            start.countDown();
            for(Thread writer:writers){writer.interrupt();writer.join(500);}
        }
    }

    private String get(String scope,HistoryDays.Day day){return DailySummaryCache.get(context,scope,day,MODEL,ENDPOINT);}
    private void reset(){require(isolated.edit().clear().commit(),"Could not reset isolated cache preferences.");}
    private void require(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
    private static long base(){Calendar calendar=Calendar.getInstance(UTC);calendar.clear();calendar.set(2026,Calendar.OCTOBER,15,12,0,0);return calendar.getTimeInMillis();}
    private static HistoryDays.Day day(long time,String text,TimeZone zone){return HistoryDays.group(Collections.singletonList(new HistoryDays.Entry(time,text,Cleaner.words(text))),zone).get(0);}
}
