package app.voiceflow;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.IdentityHashMap;

/** Android may defer periodic backup until its network and battery scheduling conditions allow it. */
public final class BackupJob extends JobService {
    private final IdentityHashMap<JobParameters, Run> runs = new IdentityHashMap<>();
    private static final class Run {
        volatile boolean stopped;
        Thread worker;
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (!AppProduct.backupEnabled() || !DriveBackup.automaticEnabled(getApplicationContext())) return false;
        final Run run = new Run();
        synchronized (runs) {
            Run previous = runs.put(parameters, run);
            if (previous != null) {
                previous.stopped = true;
                if (previous.worker != null) previous.worker.interrupt();
            }
        }
        run.worker = new Thread(() -> {
            boolean retry = false;
            try {
                if (!run.stopped && DriveBackup.automaticEnabled(getApplicationContext())) DriveBackup.backup(getApplicationContext());
            } catch (Exception failure) {
                retry = !run.stopped && DriveBackup.retryable(failure);
            } finally {
                synchronized (runs) {
                    if (runs.get(parameters) == run) {
                        runs.remove(parameters);
                        if (!run.stopped) jobFinished(parameters, retry);
                    }
                }
            }
        }, "VonoTation-backup");
        run.worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        synchronized (runs) {
            Run run = runs.remove(parameters);
            if (run != null) {
                run.stopped = true;
                if (run.worker != null) run.worker.interrupt();
            }
        }
        return DriveBackup.automaticEnabled(getApplicationContext());
    }

    @Override public void onDestroy() {
        synchronized (runs) {
            for (Run run : runs.values()) { run.stopped = true; if (run.worker != null) run.worker.interrupt(); }
            runs.clear();
        }
        super.onDestroy();
    }
}
