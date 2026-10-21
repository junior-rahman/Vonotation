package app.voiceflow;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.IdentityHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Silent, persisted cleanup. Reads hide expired copies even when Android defers this job. */
public final class ClipboardExpiryJob extends JobService {
    static final int JOB_ID=73023;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor();
    private final IdentityHashMap<JobParameters,Run> runs=new IdentityHashMap<>();
    private static final class Run{boolean stopped;}
    static void schedule(Context context){if(!AppProduct.isNotes()&&context!=null){Context app=context.getApplicationContext();WORK.execute(()->scheduleNow(app));}}
    private static void scheduleNow(Context app){
        try{
            long expiry=ClipList.nextExpiry(app);
            JobScheduler scheduler=(JobScheduler)app.getSystemService(Context.JOB_SCHEDULER_SERVICE);if(scheduler==null)return;
            if(expiry==0){scheduler.cancel(JOB_ID);return;}
            JobInfo previous=scheduler.getPendingJob(JOB_ID);
            if(previous!=null&&previous.getExtras().getLong("expiry")==expiry)return;
            android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putLong("expiry",expiry);
            long delay=Math.max(0,expiry-System.currentTimeMillis());
            scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(app,ClipboardExpiryJob.class)).setPersisted(true).setMinimumLatency(delay).setOverrideDeadline(delay+15*60*1000L).setExtras(extras).build());
        }catch(RuntimeException unavailable){android.util.Log.w("VonoTation","Clipboard cleanup will retry when VonoTation is opened.");}
    }
    @Override public boolean onStartJob(JobParameters parameters){
        if(AppProduct.isNotes())return false;
        Run run=new Run();synchronized(runs){if(runs.containsKey(parameters))return true;runs.put(parameters,run);}
        Context app=getApplicationContext();WORK.execute(()->{
            try{ synchronized(runs){if(run.stopped)return;}ClipList.expire(app); }
            finally{ synchronized(runs){if(runs.remove(parameters)==run&&!run.stopped)jobFinished(parameters,false);}schedule(app); }
        });return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){synchronized(runs){Run run=runs.remove(parameters);if(run!=null)run.stopped=true;}return true;}
    @Override public void onDestroy(){synchronized(runs){for(Run run:runs.values())run.stopped=true;runs.clear();}super.onDestroy();}
}
