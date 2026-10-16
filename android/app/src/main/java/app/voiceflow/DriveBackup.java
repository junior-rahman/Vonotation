package app.voiceflow;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Opt-in encrypted Drive app-data backups. Identity and authorization remain on this phone. */
final class DriveBackup {
    static final int JOB_ID = 73021;
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final ReentrantLock OPERATION = new ReentrantLock();
    /** Foreground Cancel advances this epoch; an upload started under an older epoch stops before commit. */
    private static final java.util.concurrent.atomic.AtomicInteger CANCEL_EPOCH = new java.util.concurrent.atomic.AtomicInteger();
    static final class CancelledException extends java.io.InterruptedIOException {
        CancelledException() { super("Backup cancelled. Nothing new was saved to Google Drive; your previous backup is unchanged."); }
    }
    static int cancelEpoch() { return CANCEL_EPOCH.get(); }
    static void requestCancel() { CANCEL_EPOCH.incrementAndGet(); }
    private static void checkCancelled(int epoch) throws IOException {
        checkInterrupted();
        if (epoch >= 0 && CANCEL_EPOCH.get() != epoch) throw new CancelledException();
    }
    /** Worker only: parsing and hashing portable history can be expensive. */
    static String dataFingerprint(Context context) {
        try { return BackupData.fingerprint(BackupData.snapshot(context)); }
        catch (Exception unavailable) { return ""; }
    }
    private static final String FRESHNESS_UNAVAILABLE = "This phone’s current backup data could not be checked. Your saved backup is unchanged.";
    private static final long FRESHNESS_RETRY_MS = 30_000;
    private static final Map<SharedPreferences, SnapshotState> SNAPSHOT_STATES = new IdentityHashMap<>();
    private static final java.util.concurrent.ExecutorService SNAPSHOT_WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VonoTation-backup-status");
        thread.setDaemon(true);
        return thread;
    });

    interface SnapshotNoteCallback { void ready(String note); }

    private static final class SnapshotState {
        final SharedPreferences preferences;
        final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
        final List<SnapshotNoteCallback> callbacks = new ArrayList<>();
        final SharedPreferences.OnSharedPreferenceChangeListener listener;
        boolean running;
        SnapshotStamp cachedStamp;
        String cachedNote;
        long retryAt;
        SnapshotState(SharedPreferences preferences) {
            this.preferences = preferences;
            listener = (changed, key) -> {
                // Status-only writes must not cause another parse of the user's history.
                if (key == null || freshnessRelevant(key)) revision.incrementAndGet();
            };
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
    }

    /** Cheap immutable identity for a requested calculation; no history text is parsed here. */
    private static final class SnapshotStamp {
        final long revision, at;
        final String fingerprint, draft;
        final Map<String, Object> sourceReferences = new HashMap<>();
        SnapshotStamp(SnapshotState state) {
            revision = state.revision.get();
            at = snapshotAt(state.preferences);
            fingerprint = snapshotFingerprint(state.preferences);
            draft = MainActivity.currentDraft;
            for (Map.Entry<String, ?> item : state.preferences.getAll().entrySet())
                if (freshnessRelevant(item.getKey())) sourceReferences.put(item.getKey(), item.getValue());
        }
        boolean matches(SnapshotState state) {
            if (revision != state.revision.get() || at != snapshotAt(state.preferences)
                    || !fingerprint.equals(snapshotFingerprint(state.preferences)) || draft != MainActivity.currentDraft) return false;
            // Android posts background preference notifications to main. Values may already have
            // changed before their listener callback arrives, so compare immutable value identities
            // as well. This scans the small settings map, never the large history/notes strings.
            int matched = 0;
            for (Map.Entry<String, ?> item : state.preferences.getAll().entrySet()) if (freshnessRelevant(item.getKey())) {
                if (!sourceReferences.containsKey(item.getKey()) || sourceReferences.get(item.getKey()) != item.getValue()) return false;
                matched++;
            }
            return matched == sourceReferences.size();
        }
    }

    private static boolean freshnessRelevant(String key) {
        return !key.startsWith("backup_") || "backup_frequency".equals(key) || "backup_wifi".equals(key)
                || "backup_account_id".equals(key) || "backup_snapshot_at".equals(key)
                || "backup_snapshot_fingerprint".equals(key);
    }

    private static SnapshotState snapshotState(Context context) {
        SharedPreferences preferences = Store.prefs(context.getApplicationContext());
        synchronized (SNAPSHOT_STATES) {
            SnapshotState state = SNAPSHOT_STATES.get(preferences);
            if (state == null) { state = new SnapshotState(preferences); SNAPSHOT_STATES.put(preferences, state); }
            return state;
        }
    }

    /** UI-safe freshness check. Concurrent requests share one worker and cached results survive
     * ordinary renders. The callback is delivered on main and never receives a stale result. */
    static void requestSnapshotNote(Context context, SnapshotNoteCallback callback) {
        if (callback == null) return;
        Context app = context.getApplicationContext();
        SnapshotState state = snapshotState(app);
        synchronized (state) {
            if (state.cachedStamp != null && state.cachedStamp.matches(state)
                    && (state.retryAt == 0 || android.os.SystemClock.elapsedRealtime() < state.retryAt)) {
                SnapshotStamp cachedStamp = state.cachedStamp;
                String cached = state.cachedNote;
                new Handler(Looper.getMainLooper()).post(() -> {
                    // A preference or draft edit between queuing and delivery invalidates the result too.
                    synchronized (state) {
                        if (state.cachedStamp != cachedStamp || !cachedStamp.matches(state)) {
                            requestSnapshotNote(app, callback);
                            return;
                        }
                    }
                    callback.ready(cached);
                });
                return;
            }
            state.callbacks.add(callback);
            if (state.running) return;
            state.running = true;
        }
        calculateSnapshotNote(app, state, 0);
    }

    private static void calculateSnapshotNote(Context app, SnapshotState state, int attempt) {
        SnapshotStamp stamp = new SnapshotStamp(state);
        SNAPSHOT_WORKER.execute(() -> {
            String result = calculateSnapshotNote(app, stamp.at, stamp.fingerprint);
            new Handler(Looper.getMainLooper()).post(() -> {
                List<SnapshotNoteCallback> callbacks;
                String delivered = result;
                synchronized (state) {
                    if (!stamp.matches(state)) {
                        if (attempt == 0) { calculateSnapshotNote(app, state, 1); return; }
                        // Continuous edits get one bounded retry; do not create a hashing/polling loop.
                        delivered = FRESHNESS_UNAVAILABLE;
                        state.cachedStamp = new SnapshotStamp(state);
                    } else {
                        state.cachedStamp = stamp;
                    }
                    state.cachedNote = delivered;
                    // Keep uncertainty visible, but allow a future explicit/resume request to retry.
                    state.retryAt = FRESHNESS_UNAVAILABLE.equals(delivered)
                            ? android.os.SystemClock.elapsedRealtime() + FRESHNESS_RETRY_MS : 0;
                    state.running = false;
                    callbacks = new ArrayList<>(state.callbacks);
                    state.callbacks.clear();
                }
                for (SnapshotNoteCallback callback : callbacks) callback.ready(delivered);
            });
        });
    }

    private static String cachedSnapshotNote(Context context) {
        SnapshotState state = snapshotState(context);
        synchronized (state) { return state.cachedStamp != null && state.cachedStamp.matches(state) ? state.cachedNote : ""; }
    }

    private static long snapshotAt(SharedPreferences preferences) {
        try { return preferences.getLong("backup_snapshot_at", 0); }
        catch (ClassCastException malformed) { return 0; }
    }
    private static String snapshotFingerprint(SharedPreferences preferences) {
        try { return preferences.getString("backup_snapshot_fingerprint", ""); }
        catch (ClassCastException malformed) { return ""; }
    }

    /** Non-empty when the saved snapshot is older. Main-thread callers read the async cache. */
    static String snapshotNote(Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) return cachedSnapshotNote(context);
        return calculateSnapshotNote(context, snapshotAt(Store.prefs(context)), snapshotFingerprint(Store.prefs(context)));
    }
    private static String calculateSnapshotNote(Context context, long at, String saved) {
        if (at == 0 || saved.isEmpty()) return "";
        String current=dataFingerprint(context);
        if(current.isEmpty())return FRESHNESS_UNAVAILABLE;
        if(saved.equals(current))return "";
        return "The saved backup is a snapshot from " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(at))
                + ". Newer dictations, Quick Thoughts or settings are not in it yet.";
    }
    private static final String BUSY = "A backup or restore is already running. Please wait.";
    private static final String[] CONFIG_KEYS = {
        "backup_account", "backup_account_id", "backup_account_name", "backup_cloud_ready",
        "backup_remote_id", "backup_reconnect", "backup_last_success", "backup_error",
        "backup_snapshot_at", "backup_snapshot_fingerprint",
        "backup_listing_incomplete", "backup_listing_warning",
        "backup_frequency", "backup_wifi", "backup_uri", "backup_name"
    };

    static class ConfigurationException extends IOException {
        ConfigurationException(String message) { super(message); }
    }
    private static final class IncompleteListingException extends ConfigurationException {
        IncompleteListingException(String warning) { super(warning); }
    }
    private static final class BusyException extends IOException {
        BusyException() { super(BUSY); }
    }

    /** Short-lived foreground result. The token is never written to preferences or an archive. */
    static final class Connection {
        final DriveCloudClient.Profile profile;
        final List<DriveCloudClient.BackupFile> files;
        final List<DriveCloudClient.RejectedBackup> rejected;
        final boolean hasRejected;
        final String warning;
        final String token;
        Connection(DriveCloudClient.Profile profile, DriveCloudClient.Listing listing, String token) {
            this.profile = profile;
            this.files = Collections.unmodifiableList(new ArrayList<>(listing.files));
            this.rejected = Collections.unmodifiableList(new ArrayList<>(listing.rejected));
            this.hasRejected = listing.hasRejected();
            this.warning = listing.warning();
            this.token = token;
        }
    }

    static boolean configured(Context context) {
        return !accountEmail(context).isEmpty() && !text(context, "backup_account_id").isEmpty();
    }
    static String accountEmail(Context context) { return text(context, "backup_account"); }
    static String accountName(Context context) { return text(context, "backup_account_name"); }
    static boolean needsReconnect(Context context) { return flag(context, "backup_reconnect", false); }
    static boolean listingIncomplete(Context context) { return flag(context, "backup_listing_incomplete", false); }
    static boolean passwordReady(Context context) {
        return configured(context) && !listingIncomplete(context) && flag(context, "backup_cloud_ready", false)
                && BackupSecrets.snapshot(context).containsKey("secret");
    }
    static boolean automaticEnabled(Context context) {
        return passwordReady(context) && !needsReconnect(context) && !"off".equals(frequency(context));
    }
    static String summary(Context context) {
        if (!configured(context)) return "Connect Google Drive to back up your history and settings";
        if (listingIncomplete(context)) return "Drive backup needs attention · Older backups can still be restored";
        if (needsReconnect(context)) return "Reconnect Google Drive · " + accountEmail(context);
        if (!passwordReady(context)) return "Set up your backup password · " + accountEmail(context);
        String cadence = "daily".equals(frequency(context)) ? "Daily" : "weekly".equals(frequency(context)) ? "Weekly" : "Automatic backup off";
        String network = flag(context, "backup_wifi", true) ? " · Wi-Fi only" : " · Any network";
        long last = lastSuccess(context);
        String freshness = cachedSnapshotNote(context);
        return cadence + network + (last == 0 ? " · No backup saved yet" : " · Saved "
                + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(last))
                + (freshness.isEmpty() ? "" : FRESHNESS_UNAVAILABLE.equals(freshness)
                ? " · Backup freshness could not be checked" : " · Newer changes are not backed up yet"));
    }

    /** Identical pending jobs keep their original schedule; connection alone never opts in. */
    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        try {
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) return;
            if (!automaticEnabled(app)) { scheduler.cancel(JOB_ID); return; }
            long interval = "weekly".equals(frequency(app)) ? DAY * 7 : DAY;
            int network = flag(app, "backup_wifi", true) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
            ComponentName component = new ComponentName(app, BackupJob.class);
            JobInfo old = scheduler.getPendingJob(JOB_ID);
            if (old != null && old.getService().equals(component) && old.isPersisted()
                    && old.getIntervalMillis() == interval && old.getNetworkType() == network) return;
            JobInfo job = new JobInfo.Builder(JOB_ID, component).setPersisted(true)
                    .setRequiredNetworkType(network).setPeriodic(interval).build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) recordError(app,
                    "Android could not schedule automatic backup. Back up now is still available.");
        } catch (RuntimeException failure) {
            recordError(app, "Android could not schedule automatic backup. Back up now is still available.");
        }
    }

    /** Worker only. New-account discovery never changes the saved connection. Known partial
     * listings for the saved identity pause uploads without changing the user's backup consent. */
    static Connection discover(Context context, String token) throws Exception {
        checkInterrupted();
        acquire();
        try {
            DriveCloudClient client = new DriveCloudClient(token);
            DriveCloudClient.Profile profile = client.profile();
            validateProfile(profile);
            DriveCloudClient.Listing listing = client.listing();
            checkInterrupted();
            Connection connection = new Connection(profile, listing, token);
            noteRejectedListing(context.getApplicationContext(), connection);
            return connection;
        } catch (DriveCloudClient.HttpFailure failure) {
            if (failure.status == 401) DriveAuth.invalidate(context.getApplicationContext(), token);
            throw failure;
        } finally { OPERATION.unlock(); }
    }

    /** Reconnect an existing pinned identity, preserving its password and opt-in schedule. */
    static void connect(Context context, Connection connection) throws Exception {
        acquire();
        Context app = context.getApplicationContext();
        Map<String, ?> before = configurationSnapshot(app);
        Connection fresh = null;
        try {
            fresh = refresh(app, connection);
            if (configured(app)) verifyIdentity(app, fresh.profile);
            requireCompleteListing(fresh);
            if (flag(app, "backup_cloud_ready", false) && BackupSecrets.snapshot(app).containsKey("secret")) {
                char[] password = BackupSecrets.get(app);
                try { validateLatest(new DriveCloudClient(fresh.token), fresh.files, password); }
                finally { if (password != null) Arrays.fill(password, '\0'); }
            }
            checkInterrupted();
            boolean same = sameIdentity(app, fresh.profile);
            SharedPreferences.Editor edit = identityEditor(app, fresh.profile).remove("backup_error")
                    .putBoolean("backup_reconnect", false).putBoolean("backup_listing_incomplete", false)
                    .remove("backup_listing_warning");
            if (!same) edit.putBoolean("backup_cloud_ready", false).putString("backup_frequency", "off")
                    .remove("backup_last_success").remove("backup_remote_id")
                    .remove("backup_snapshot_at").remove("backup_snapshot_fingerprint");
            if (!edit.commit()) throw new ConfigurationException("Could not save the Google Drive connection.");
        } catch (Exception failure) {
            invalidateRejectedToken(app, connection, failure);
            rollbackConfiguration(app, before, failure);
            preserveKnownPartial(app, fresh, failure);
            throw failure;
        } finally { OPERATION.unlock(); }
        schedule(app);
    }

    /** Validate existing remote history before adopting a password, including when restore is skipped. */
    static JSONObject configurePassword(Context context, Connection connection, char[] password,
            DriveCloudClient.BackupFile existing) throws Exception {
        acquire();
        Context app = context.getApplicationContext();
        Map<String, ?> oldSecret = BackupSecrets.snapshot(app);
        Map<String, ?> oldConfiguration = configurationSnapshot(app);
        boolean changed = false;
        Connection fresh = null;
        try {
            fresh = refresh(app, connection);
            requireCompleteListing(fresh);
            BackupArchive.checkPassword(password);
            JSONObject archive = validateAdoption(fresh, password, existing);
            checkInterrupted();
            changed = true;
            BackupSecrets.set(app, password);
            commitAdoption(app, fresh, existing, true);
            releaseLegacyAfterSuccess(app, oldConfiguration);
            return archive;
        } catch (Exception failure) {
            invalidateRejectedToken(app, connection, failure);
            if (changed) rollback(app, oldSecret, oldConfiguration, failure);
            preserveKnownPartial(app, fresh, failure);
            throw failure;
        } finally { OPERATION.unlock(); schedule(app); }
    }

    /** Disconnect leaves every remote generation and all history/settings intact. */
    static void disconnect(Context context) {
        if (!OPERATION.tryLock()) throw new IllegalStateException(BUSY);
        Context app = context.getApplicationContext();
        Map<String, ?> oldSecret = BackupSecrets.snapshot(app);
        Map<String, ?> before = configurationSnapshot(app);
        try {
            checkInterrupted();
            BackupSecrets.clear(app);
            SharedPreferences.Editor edit = Store.prefs(app).edit();
            for (String key : CONFIG_KEYS) if (!key.equals("backup_wifi")) edit.remove(key);
            edit.putString("backup_frequency", "off");
            if (!edit.commit()) throw new ConfigurationException("Could not disconnect Google Drive. Try again.");
            JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) scheduler.cancel(JOB_ID);
            releaseLegacyAfterSuccess(app, before);
        } catch (Exception failure) {
            rollback(app, oldSecret, before, failure);
            schedule(app);
            throw new IllegalStateException("Could not disconnect Google Drive. Try again.", failure);
        } finally { OPERATION.unlock(); }
    }

    /** Worker only. Authorization never opens UI from this method. */
    static void backup(Context context) throws Exception { backupInternal(context, null, -1); }
    /** Worker for a foreground-authorized token or offline fixture; all account/archive checks remain. */
    static void backup(Context context, String accessToken) throws Exception { backupInternal(context, accessToken, -1); }
    /** Foreground backup that honors Cancel until the commit request; returns a snapshot note or "". */
    static String backup(Context context, String accessToken, int cancelEpoch) throws Exception { return backupInternal(context, accessToken, cancelEpoch); }

    private static String backupInternal(Context context, String providedToken, int cancelEpoch) throws Exception {
        acquire();
        Context app = context.getApplicationContext();
        char[] password = null;
        DriveCloudClient client = null;
        DriveCloudClient.BackupFile uploaded = null;
        boolean committed = false;
        boolean commitAttempted = false;
        String token = null;
        try {
            if (!configured(app)) throw new ConfigurationException("Connect Google Drive before backing up.");
            if (listingIncomplete(app)) throw new IncompleteListingException(incompleteWarning(app));
            if (!passwordReady(app)) throw new ConfigurationException("Set up your backup password before backing up.");
            if (providedToken == null && needsReconnect(app)) throw new DriveAuth.ReconnectException("Reconnect Google Drive before backing up.");
            checkCancelled(cancelEpoch);
            password = rememberedPassword(app);
            token = providedToken != null ? providedToken : DriveAuth.token(app, accountEmail(app));
            client = new DriveCloudClient(token);
            DriveCloudClient.Profile profile = client.profile();
            validateProfile(profile);
            verifyIdentity(app, profile);
            // Re-discover on every upload so a reinstall cannot silently overwrite remote history.
            Connection fresh = new Connection(profile, client.listing(), token);
            noteRejectedListing(app, fresh);
            requireCompleteListing(fresh);
            validateLatest(client, fresh.files, password);
            checkCancelled(cancelEpoch);
            if (providedToken != null && needsReconnect(app)) {
                if (!Store.prefs(app).edit().putBoolean("backup_reconnect", false).remove("backup_error").commit())
                    throw new ConfigurationException("Could not save the refreshed Google Drive connection.");
                schedule(app);
            }
            final JSONObject snapshot = BackupData.snapshot(app);
            final long snapshotAt = snapshot.getLong("created_at");
            final String snapshotFingerprint = BackupData.fingerprint(snapshot);
            byte[] encrypted = BackupArchive.encode(snapshot, password);
            // Exercise the same decryption and schema validation as restore before
            // any upload, then again on downloaded bytes before remote commit.
            if(!snapshotFingerprint.equals(BackupData.fingerprint(decode(encrypted,password))))
                throw new IOException("The new backup could not be verified. Your previous backup has been kept.");
            checkCancelled(cancelEpoch);
            uploaded = client.upload(encrypted); // A pending, immutable generation.
            checkCancelled(cancelEpoch);
            byte[] remote = client.download(uploaded);
            if (!Arrays.equals(encrypted, remote)) throw new IOException("Drive could not verify the new backup. Your previous backup has been kept.");
            if(!snapshotFingerprint.equals(BackupData.fingerprint(decode(remote,password))))
                throw new IOException("Drive could not verify the new backup data. Your previous backup has been kept.");
            checkCancelled(cancelEpoch);
            commitAttempted = true;
            DriveCloudClient.BackupFile acknowledged = client.commit(uploaded.id);
            if (acknowledged == null || !uploaded.id.equals(acknowledged.id))
                throw new IOException("Drive did not confirm the new backup. Your previous backup has been kept.");
            committed = true;
            // From this point the remote backup must stay even if a local status write fails.
            if (!Store.prefs(app).edit().putLong("backup_last_success", System.currentTimeMillis())
                    .putLong("backup_snapshot_at", snapshotAt).putString("backup_snapshot_fingerprint", snapshotFingerprint)
                    .putString("backup_remote_id", uploaded.id).remove("backup_error").commit())
                throw new ConfigurationException("Backup was saved to Drive, but this phone could not update its status.");
            // Cancel can no longer undo a committed backup; finish housekeeping and report it.
            if (!Thread.currentThread().isInterrupted()) retainGenerations(client, uploaded.id);
            return snapshotNote(app);
        } catch (Exception failure) {
            if (uploaded != null && !committed && !commitAttempted && client != null && !Thread.currentThread().isInterrupted()) {
                try { client.delete(uploaded.id); } catch (IOException ignored) { /* Pending files are excluded from discovery. */ }
            }
            if (reconnectFailure(failure)) {
                if (token != null && failure instanceof DriveCloudClient.HttpFailure
                        && ((DriveCloudClient.HttpFailure) failure).status == 401) DriveAuth.invalidate(app, token);
                Store.prefs(app).edit().putBoolean("backup_reconnect", true).apply();
                schedule(app);
            } else if (failure instanceof ConfigurationException && !(failure instanceof IncompleteListingException) && !committed) {
                // A lost device-bound password must stop recurring attempts until foreground repair.
                Store.prefs(app).edit().putBoolean("backup_cloud_ready", false).apply();
                schedule(app);
            }
            if (!(failure instanceof CancelledException)) recordError(app, safeError(failure));
            throw failure;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            OPERATION.unlock();
        }
    }

    /** Worker only. Reading never applies saved data; a known partial listing can pause uploads. */
    static JSONObject read(Context context, Connection connection, DriveCloudClient.BackupFile file,
            char[] password) throws Exception {
        acquire();
        try {
            Connection fresh = refresh(context.getApplicationContext(), connection);
            DriveCloudClient.BackupFile current = findFile(fresh.files, file);
            checkInterrupted();
            return decode(new DriveCloudClient(fresh.token).download(current), password);
        } catch (Exception failure) {
            invalidateRejectedToken(context.getApplicationContext(), connection, failure);
            throw failure;
        } finally { OPERATION.unlock(); }
    }

    static JSONObject readLegacy(Context context, Uri uri, char[] password) throws Exception {
        acquire();
        try {
            validateUri(uri);
            checkInterrupted();
            return decode(readLegacyBytes(context.getApplicationContext(), uri), password);
        } finally { OPERATION.unlock(); }
    }
    static JSONObject read(Context context, Uri uri, char[] password) throws Exception {
        return readLegacy(context, uri, password);
    }

    /** Apply only after the UI has presented an explicit restore confirmation. */
    static void restore(Context context, JSONObject archive) throws Exception {
        acquire();
        Context app = context.getApplicationContext();
        String previousFrequency = automaticEnabled(app) ? frequency(app) : "off";
        try {
            JSONObject checked = BackupData.validate(archive);
            checked.getJSONObject("settings").put("backup_frequency", previousFrequency);
            checked.getJSONObject("settings").put("backup_wifi", flag(app, "backup_wifi", true));
            checkedRestore(app, checked);
        } finally { OPERATION.unlock(); schedule(app); }
    }

    /** Restore plus account/password adoption, with local connection rollback on failed data restore. */
    static void restore(Context context, Connection connection, char[] password,
            DriveCloudClient.BackupFile existing, JSONObject archive) throws Exception {
        acquire();
        Context app = context.getApplicationContext();
        Map<String, ?> oldSecret = BackupSecrets.snapshot(app);
        Map<String, ?> oldConfiguration = configurationSnapshot(app);
        boolean changed = false;
        Connection fresh = null;
        try {
            fresh = refresh(app, connection);
            BackupArchive.checkPassword(password);
            DriveCloudClient client = new DriveCloudClient(fresh.token);
            DriveCloudClient.BackupFile selected = findFile(fresh.files, existing);
            JSONObject remote = decode(client.download(selected), password);
            if (remote == null || archive == null || !BackupData.validate(archive).toString().equals(remote.toString()))
                throw new ConfigurationException("The backup changed. Choose it again before restoring.");
            boolean latestUnlocked = !fresh.hasRejected && !fresh.files.isEmpty() && fresh.files.get(0).id.equals(selected.id);
            if (!fresh.hasRejected && !latestUnlocked) {
                try { validateLatest(client, fresh.files, password); latestUnlocked = true; }
                catch (Exception latestFailure) {
                    checkInterrupted();
                    if (latestFailure instanceof DriveCloudClient.HttpFailure
                            && ((DriveCloudClient.HttpFailure) latestFailure).status == 401) DriveAuth.invalidate(app, fresh.token);
                    // Explicit recovery of an older valid archive remains available. New uploads
                    // stay disabled until the current remote history is successfully unlocked.
                }
            }
            checkInterrupted();
            checkRestoreAllowed();
            synchronized (Store.class) {
                if (writingActive()) throw new ConfigurationException("Finish recording or processing before restoring a backup.");
                checkInterrupted();
                boolean same = sameIdentity(app, fresh.profile);
                String previousFrequency = same && latestUnlocked && automaticEnabled(app) ? frequency(app) : "off";
                changed = true;
                BackupSecrets.set(app, password);
                commitAdoption(app, fresh, existing, latestUnlocked);
                checkInterrupted();
                // Override the portable schedule before restore to avoid an implicit opt-in.
                JSONObject checked = BackupData.validate(remote);
                checked.getJSONObject("settings").put("backup_frequency", previousFrequency);
                checked.getJSONObject("settings").put("backup_wifi", flag(app, "backup_wifi", true));
                checkInterrupted();
                BackupData.restore(app, checked);
            }
            releaseLegacyAfterSuccess(app, oldConfiguration);
        } catch (Exception failure) {
            invalidateRejectedToken(app, connection, failure);
            if (changed) rollback(app, oldSecret, oldConfiguration, failure);
            preserveKnownPartial(app, fresh, failure);
            throw failure;
        } finally { OPERATION.unlock(); schedule(app); }
    }

    static boolean retryable(Exception failure) {
        if (Thread.currentThread().isInterrupted()) return false;
        if (failure instanceof ConfigurationException || failure instanceof DriveAuth.ReconnectException) return false;
        if (failure instanceof DriveAuth.AuthException) return ((DriveAuth.AuthException) failure).retryable;
        if (failure instanceof DriveCloudClient.HttpFailure) return ((DriveCloudClient.HttpFailure) failure).retryable;
        if (failure instanceof DriveCloudClient.ProtocolFailure) return false;
        if (failure instanceof java.net.SocketTimeoutException) return true;
        return failure instanceof IOException && !(failure instanceof java.io.InterruptedIOException);
    }
    static String safeError(Exception failure) {
        String message = failure.getMessage();
        return boundedText(message == null || message.trim().isEmpty() ? "Backup failed. Please try again." : message, 240);
    }
    static long lastSuccess(Context context) {
        try { return Store.prefs(context).getLong("backup_last_success", 0); }
        catch (ClassCastException malformed) { return 0; }
    }

    private static Connection refresh(Context context, Connection connection) throws Exception {
        if (connection == null || connection.token == null || connection.token.isEmpty())
            throw new ConfigurationException("Connect Google Drive again to continue.");
        checkInterrupted();
        DriveCloudClient client = new DriveCloudClient(connection.token);
        DriveCloudClient.Profile profile = client.profile();
        validateProfile(profile);
        if (!profile.permissionId.equals(connection.profile.permissionId) || !profile.email.equalsIgnoreCase(connection.profile.email))
            throw new ConfigurationException("The Google account changed. Connect again before continuing.");
        DriveCloudClient.Listing listing = client.listing();
        checkInterrupted();
        Connection fresh = new Connection(profile, listing, connection.token);
        noteRejectedListing(context, fresh);
        return fresh;
    }
    private static JSONObject validateAdoption(Connection fresh, char[] password,
            DriveCloudClient.BackupFile existing) throws Exception {
        requireCompleteListing(fresh);
        BackupArchive.checkPassword(password);
        DriveCloudClient client = new DriveCloudClient(fresh.token);
        if (existing == null && !fresh.files.isEmpty())
            throw new ConfigurationException("An existing backup was found. Enter its password before creating new backups.");
        JSONObject archive = existing == null ? null : decode(client.download(findFile(fresh.files, existing)), password);
        if (!fresh.files.isEmpty() && (existing == null || !fresh.files.get(0).id.equals(existing.id)))
            validateLatest(client, fresh.files, password);
        checkInterrupted();
        return archive;
    }
    private static void validateLatest(DriveCloudClient client, List<DriveCloudClient.BackupFile> files, char[] password) throws Exception {
        if (files.isEmpty()) return;
        byte[] bytes = client.download(files.get(0));
        try { decode(bytes, password); }
        catch (java.io.InterruptedIOException failure) { throw failure; }
        catch (IOException failure) {
            // A stop arriving during decryption must not turn into a lost-password pause.
            checkInterrupted();
            throw new ConfigurationException("The latest Drive backup could not be unlocked. Enter its backup password before saving a new backup.");
        }
    }
    private static JSONObject decode(byte[] bytes, char[] password) throws Exception {
        checkInterrupted();
        JSONObject value = BackupData.validate(BackupArchive.decode(bytes, password));
        checkInterrupted();
        return value;
    }
    private static DriveCloudClient.BackupFile findFile(List<DriveCloudClient.BackupFile> files,
            DriveCloudClient.BackupFile selected) throws IOException {
        if (selected != null) for (DriveCloudClient.BackupFile file : files) if (file.id.equals(selected.id)) return file;
        throw new ConfigurationException("This backup is no longer available. Refresh Google Drive and try again.");
    }
    private static void commitAdoption(Context app, Connection fresh, DriveCloudClient.BackupFile existing, boolean latestUnlocked) throws Exception {
        checkInterrupted();
        boolean same = sameIdentity(app, fresh.profile);
        SharedPreferences.Editor edit = identityEditor(app, fresh.profile).putBoolean("backup_cloud_ready", latestUnlocked)
                .putBoolean("backup_reconnect", false).putBoolean("backup_listing_incomplete", fresh.hasRejected)
                .remove("backup_error").remove("backup_uri").remove("backup_name");
        if (fresh.hasRejected) edit.putString("backup_listing_warning", fresh.warning); else edit.remove("backup_listing_warning");
        if (!same) edit.putString("backup_frequency", "off").remove("backup_last_success")
                .remove("backup_snapshot_at").remove("backup_snapshot_fingerprint");
        if (!latestUnlocked) edit.putString("backup_frequency", "off");
        if (existing != null) {
            edit.putString("backup_remote_id", existing.id);
            if (!same || lastSuccess(app) == 0) {
                try {
                    long modified = java.time.Instant.parse(existing.modifiedTime).toEpochMilli();
                    if (modified > 0) edit.putLong("backup_last_success", modified);
                } catch (RuntimeException ignored) { /* A status date cannot invalidate a verified archive. */ }
            }
        } else edit.remove("backup_remote_id");
        if (!edit.commit()) throw new ConfigurationException("Could not save the backup configuration.");
    }
    private static SharedPreferences.Editor identityEditor(Context app, DriveCloudClient.Profile profile) {
        return Store.prefs(app).edit().putString("backup_account", profile.email)
                .putString("backup_account_id", profile.permissionId).putString("backup_account_name", profile.name);
    }
    private static void validateProfile(DriveCloudClient.Profile profile) throws IOException {
        if (profile == null || profile.email == null || profile.email.isEmpty() || profile.permissionId == null
                || profile.permissionId.isEmpty()) throw new ConfigurationException("Google Drive did not identify the connected account. Connect again.");
    }
    private static boolean sameIdentity(Context context, DriveCloudClient.Profile profile) {
        return configured(context) && profile.permissionId.equals(text(context, "backup_account_id"))
                && profile.email.equalsIgnoreCase(accountEmail(context));
    }
    private static void requireCompleteListing(Connection connection) throws IOException {
        if (connection.hasRejected) throw new IncompleteListingException(connection.warning);
    }
    private static String incompleteWarning(Context context) {
        String warning = text(context, "backup_listing_warning");
        return warning.isEmpty() ? "Some Drive backups could not be verified. Restore an available older backup; uploads remain paused." : warning;
    }
    private static void noteRejectedListing(Context context, Connection connection) throws IOException {
        if (!connection.hasRejected || !sameIdentity(context, connection.profile)) return;
        checkInterrupted();
        persistKnownPartial(context, connection);
    }
    private static void persistKnownPartial(Context context, Connection connection) throws IOException {
        if (!connection.hasRejected || !sameIdentity(context, connection.profile)) return;
        try {
            if (!Store.prefs(context).edit().putBoolean("backup_listing_incomplete", true)
                    .putString("backup_listing_warning", boundedText(connection.warning, 500)).commit())
                throw new ConfigurationException("This phone could not save the backup safety status. Check available storage before trying again.");
        } finally { schedule(context); }
    }
    private static void preserveKnownPartial(Context context, Connection connection, Exception failure) {
        if (connection == null) return;
        // Already discovered safety state must survive a later transaction rollback or cancellation.
        try { persistKnownPartial(context, connection); }
        catch (Exception safetyStatus) { failure.addSuppressed(safetyStatus); }
    }
    private static void verifyIdentity(Context context, DriveCloudClient.Profile profile) throws IOException {
        if (!sameIdentity(context, profile)) throw new DriveAuth.ReconnectException("Google returned a different account. Reconnect your saved Drive account.");
    }
    private static char[] rememberedPassword(Context context) throws ConfigurationException {
        try {
            char[] password = BackupSecrets.get(context);
            if (password == null || password.length == 0) throw new ConfigurationException("Enter your backup password again.");
            return password;
        } catch (Exception failure) { throw new ConfigurationException("Enter your backup password again to enable backups."); }
    }
    private static void retainGenerations(DriveCloudClient client, String newestId) {
        try {
            DriveCloudClient.Listing listing = client.listing();
            if (listing.hasRejected()) return;
            List<DriveCloudClient.BackupFile> files = listing.files;
            if (files.size() <= 3 || !files.get(0).id.equals(newestId)) return;
            for (int i = 3; i < files.size(); i++) {
                checkInterrupted();
                client.delete(files.get(i).id);
            }
        } catch (Exception ignored) { /* Retention is optional; a saved backup must not become a failed backup. */ }
    }
    private static void checkedRestore(Context app, JSONObject archive) throws Exception {
        checkInterrupted();
        checkRestoreAllowed();
        synchronized (Store.class) {
            if (writingActive()) throw new ConfigurationException("Finish recording or processing before restoring a backup.");
            checkInterrupted();
            BackupData.restore(app, archive);
        }
    }
    private static void checkRestoreAllowed() throws Exception {
        boolean recording;
        if (Looper.myLooper() == Looper.getMainLooper()) recording = recordingActive();
        else {
            FutureTask<Boolean> query = new FutureTask<>(DriveBackup::recordingActive);
            if (!new Handler(Looper.getMainLooper()).post(query)) throw new ConfigurationException("Could not check recording status. Try restoring again.");
            try { recording = query.get(5, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) { query.cancel(false); throw new ConfigurationException("The app is busy. Try restoring again."); }
        }
        if (recording) throw new ConfigurationException("Finish recording or processing before restoring a backup.");
    }
    private static boolean writingActive() {
        return Dictation.isWorking() || VoiceTake.updateBusy() || LineWire.updateBusy() || Notes.updateBusy();
    }
    private static boolean recordingActive() {
        return writingActive() || CaptureActivity.visible
                || (FlowKeyboard.instance != null && (FlowKeyboard.instance.isRecording() || FlowKeyboard.instance.isFinishing()))
                || (FlowAccessibility.instance != null && FlowAccessibility.instance.isRecording());
    }
    private static boolean reconnectFailure(Exception failure) {
        return failure instanceof DriveAuth.ReconnectException || failure instanceof DriveCloudClient.HttpFailure
                && ((DriveCloudClient.HttpFailure) failure).requiresReconnect;
    }
    private static void invalidateRejectedToken(Context context, Connection connection, Exception failure) {
        if (connection != null && failure instanceof DriveCloudClient.HttpFailure
                && ((DriveCloudClient.HttpFailure) failure).status == 401) DriveAuth.invalidate(context, connection.token);
    }
    private static void acquire() throws IOException { if (!OPERATION.tryLock()) throw new BusyException(); }
    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Backup interrupted. Please try again.");
    }
    private static String frequency(Context context) {
        String value = text(context, "backup_frequency");
        return "daily".equals(value) || "weekly".equals(value) ? value : "off";
    }
    private static String text(Context context, String key) {
        try { return Store.get(context, key, ""); } catch (ClassCastException malformed) { return ""; }
    }
    private static boolean flag(Context context, String key, boolean fallback) {
        try { return Store.bool(context, key, fallback); } catch (ClassCastException malformed) { return fallback; }
    }
    private static void recordError(Context app, String message) { Store.prefs(app).edit().putString("backup_error", message).apply(); }
    private static Map<String, ?> configurationSnapshot(Context context) {
        Map<String, ?> all = Store.prefs(context).getAll();
        HashMap<String, Object> copy = new HashMap<>();
        for (String key : CONFIG_KEYS) if (all.containsKey(key)) copy.put(key, all.get(key));
        return copy;
    }
    private static void restoreConfiguration(Context context, Map<String, ?> old) throws IOException {
        SharedPreferences.Editor edit = Store.prefs(context).edit();
        for (String key : CONFIG_KEYS) {
            edit.remove(key);
            Object value = old.get(key);
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
        }
        if (!edit.commit()) throw new ConfigurationException("Could not restore the backup configuration.");
    }
    private static void rollbackConfiguration(Context app, Map<String, ?> old, Exception failure) {
        try { restoreConfiguration(app, old); } catch (Exception rollback) { failure.addSuppressed(rollback); }
    }
    private static void rollback(Context app, Map<String, ?> secret, Map<String, ?> configuration, Exception failure) {
        try { BackupSecrets.restore(app, secret); } catch (Exception rollback) { failure.addSuppressed(rollback); }
        rollbackConfiguration(app, configuration, failure);
    }
    private static void releaseLegacyAfterSuccess(Context app, Map<String, ?> before) {
        Object value = before.get("backup_uri");
        if (!(value instanceof String) || ((String) value).isEmpty()) return;
        try {
            app.getContentResolver().releasePersistableUriPermission(Uri.parse((String) value),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (RuntimeException ignored) { }
    }
    private static void validateUri(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null || uri.getAuthority().isEmpty()
                || uri.toString().length() > 4096) throw new ConfigurationException("Choose an encrypted VonoTation backup file.");
    }
    private static byte[] readLegacyBytes(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new ConfigurationException("The backup file could not be opened.");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count, total = 0;
            while ((count = in.read(buffer)) != -1) {
                checkInterrupted();
                total += count;
                if (total > BackupArchive.MAX_BYTES) throw new ConfigurationException("The backup file is too large (maximum 8 MB).");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (SecurityException denied) { throw new ConfigurationException("The previous backup file needs to be selected again."); }
    }
    private static String boundedText(String value, int max) {
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length() && clean.length() < max;) {
            int codepoint = value.codePointAt(i);
            i += Character.charCount(codepoint);
            if (Character.isISOControl(codepoint)) continue;
            if (clean.length() + Character.charCount(codepoint) <= max) clean.appendCodePoint(codepoint);
        }
        return clean.toString();
    }
    private DriveBackup() { }
}
