package app.voiceflow;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A silent network retry for completed thoughts whose source audio is already saved.
 * This job never opens a recorder, activity, notification, or a different provider.
 * Android may defer the job according to network, battery, and background quotas.
 */
public final class ThoughtJob extends JobService {
    static final int JOB_ID = 73022;
    private static final long INITIAL_BACKOFF = 5 * 60 * 1000L;
    private static final long MAX_OBSERVATION = 4 * 60 * 1000L;
    private static final Object SCHEDULE_GATE = new Object();
    private static final AtomicInteger ACTIVE_RUNS = new AtomicInteger();
    private static final AtomicLong SCHEDULE_REQUEST = new AtomicLong();
    private final IdentityHashMap<JobParameters, Run> runs = new IdentityHashMap<>();

    private static final class Run {
        volatile boolean stopped;
        Thread observer;
    }

    /** Existing queued/active jobs retain their backoff and schedule. */
    static void schedule(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        long request = SCHEDULE_REQUEST.incrementAndGet();
        try {
            // Read note/key state before the scheduling lock: Notes may call
            // this after a Store commit, so never reverse Store's lock order.
            boolean hasWork = eligible(app);
            synchronized (SCHEDULE_GATE) {
                // A newer completed-note/key event supersedes this snapshot.
                // In particular an older empty snapshot cannot cancel new work.
                if (request != SCHEDULE_REQUEST.get()) return;
                // Notes.catchUp also calls schedule. It must not replace or stop
                // the job whose observer is currently waiting for that drain.
                if (ACTIVE_RUNS.get() > 0) return;
                JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (scheduler == null) return;
                if (!hasWork) { scheduler.cancel(JOB_ID); return; }
                ComponentName component = new ComponentName(app, ThoughtJob.class);
                JobInfo old = scheduler.getPendingJob(JOB_ID);
                if (old != null && old.getService().equals(component) && old.isPersisted()
                        && !old.isPeriodic() && old.getNetworkType() == JobInfo.NETWORK_TYPE_ANY
                        && old.getBackoffPolicy() == JobInfo.BACKOFF_POLICY_EXPONENTIAL
                        && old.getInitialBackoffMillis() == INITIAL_BACKOFF) return;
                JobInfo job = new JobInfo.Builder(JOB_ID, component).setPersisted(true)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setBackoffCriteria(INITIAL_BACKOFF, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                        .build();
                if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS)
                    Log.w("VonoTation", "Thought retry could not be scheduled; saved audio remains available.");
            }
        } catch (RuntimeException unavailable) {
            // An app resume, key change, or another completed thought can retry
            // scheduling. Never turn this background condition into a popup.
            Log.w("VonoTation", "Thought retry scheduling is unavailable; saved audio remains available.");
        }
    }

    private static boolean eligible(Context context) {
        try { return Notes.hasPendingThoughts(context) && !Vault.get(context).isEmpty(); }
        catch (RuntimeException unavailable) { return false; }
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        final Run run = new Run();
        synchronized (runs) {
            if (runs.containsKey(parameters)) return true;
            runs.put(parameters, run);
            ACTIVE_RUNS.incrementAndGet();
        }
        run.observer = new Thread(() -> observe(parameters, run), "VonoTation-thought-retry");
        run.observer.start();
        return true;
    }

    private void observe(JobParameters parameters, Run run) {
        Context app = getApplicationContext();
        boolean retry = false;
        try {
            if (!run.stopped && eligible(app)) {
                // Notes owns the serialized work and its revision/deletion guards.
                // This job only supplies a network opportunity and observes it.
                Notes.catchUpThoughts(app);
                long until = SystemClock.elapsedRealtime() + MAX_OBSERVATION;
                while (!run.stopped && Notes.updateBusy() && SystemClock.elapsedRealtime() < until)
                    Thread.sleep(250);
                retry = !run.stopped && eligible(app);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException unavailable) {
            retry = !run.stopped && eligible(app);
        } finally {
            synchronized (runs) {
                if (runs.get(parameters) == run) {
                    runs.remove(parameters);
                    ACTIVE_RUNS.decrementAndGet();
                    if (!run.stopped) {
                        jobFinished(parameters, retry);
                        // A completed thought can arrive between the final
                        // eligibility check and jobFinished. Recheck after the
                        // framework's main-thread finish message has been sent.
                        // Existing backed-off jobs are preserved by schedule().
                        new Handler(Looper.getMainLooper()).post(() -> schedule(app));
                    }
                }
            }
        }
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        synchronized (runs) {
            Run run = runs.remove(parameters);
            if (run != null) {
                run.stopped = true;
                ACTIVE_RUNS.decrementAndGet();
                if (run.observer != null) run.observer.interrupt();
            }
        }
        // Stop only this observer. A recording or user-owned Notes operation
        // continues safely; a late result still needs Notes' guarded commit.
        return eligible(getApplicationContext());
    }

    @Override public void onDestroy() {
        synchronized (runs) {
            for (Run run : runs.values()) {
                run.stopped = true;
                ACTIVE_RUNS.decrementAndGet();
                if (run.observer != null) run.observer.interrupt();
            }
            runs.clear();
        }
        super.onDestroy();
    }
}
