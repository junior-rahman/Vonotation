package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.*;
import org.json.JSONObject;
import java.text.DateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional, encrypted Google Drive backup with native account consent. */
public final class BackupActivity extends Activity {
    private static final int OPEN_LEGACY = 72;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<char[]> passwords = Collections.newSetFromMap(new IdentityHashMap<char[],Boolean>());
    private final Set<WorkerPassword> workerPasswords = Collections.newSetFromMap(new IdentityHashMap<WorkerPassword,Boolean>());
    private LinearLayout body, feedbackPanel;
    private ScrollView scroll;
    private TextView status, feedbackTitle, feedbackDetail, feedbackState;
    private ProgressBar feedbackProgress;
    private Button feedbackDetails, feedbackAction;
    private AlertDialog activeDialog;
    private AlertDialog leaveDialog;
    private DriveAuth.Callback pendingAuth;
    private boolean busy, destroyed, compactFeedback;
    private long operation;
    private int rendered;
    private Button feedbackCancel;
    private boolean backupCancellable, cancelRequested, backupWorkerStarted;
    private String notice = "", noticeTitle = "", progress = "";
    private Runnable retryOperation;
    private String retryOperationLabel = "";
    private enum NoticeKind { INFO, SUCCESS, WARNING, ERROR }
    private NoticeKind noticeKind = NoticeKind.INFO;
    private boolean observingPreferences;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshSavedStatus = () -> {
        if (alive() && !busy) {
            // A job's actual failure must stay visible even if a preference change
            // had just produced a local acknowledgement in this screen.
            if (!Store.get(this,"backup_error","").isEmpty()) {
                notice = "";
                noticeTitle = "";
                retryOperation = null;
            }
            render();
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener savedStatusChanged = (preferences,key) -> {
        if (key == null || key.startsWith("backup_")) {
            // A completed automatic job can change several fields together. Rebuild once,
            // after that transaction, without interrupting any foreground operation.
            ui.removeCallbacks(refreshSavedStatus);
            ui.post(refreshSavedStatus);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackground(new Daybook.Backdrop(this));
        getWindow().setStatusBarColor(Daybook.paper(this));
        getWindow().setNavigationBarColor(Daybook.paper(this));
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view,insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            }
            return insets;
        });
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,8));
        Button back = Daybook.button(this,"‹",false,v -> leaveBackup());
        back.setContentDescription("Back to settings");
        header.addView(back,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        TextView title = Daybook.text(this,"Backup",24,Daybook.ink(this),true);
        title.setPadding(Ui.dp(this,8),0,0,0);
        header.addView(title);
        root.addView(header,new LinearLayout.LayoutParams(-1,-2));

        scroll = new ScrollView(this);
        scroll.setContentDescription("Backup settings");
        body = Ui.column(this);
        body.setPadding(Ui.dp(this,20),Ui.dp(this,10),Ui.dp(this,20),Ui.dp(this,12));
        scroll.setFillViewport(true);
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        createFeedbackPanel(root);
        root.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom) -> {
            int height=bottom-top-view.getPaddingTop()-view.getPaddingBottom();
            boolean compact=height<Ui.dp(this,480) || (getResources().getConfiguration().fontScale>1.3f && height<Ui.dp(this,620));
            if(compact!=compactFeedback){compactFeedback=compact;adaptFeedbackLayout();renderFeedback();}
        });
        setContentView(root);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!observingPreferences) {
            Store.prefs(this).registerOnSharedPreferenceChangeListener(savedStatusChanged);
            observingPreferences = true;
        }
        DriveBackup.schedule(this);
        if (!busy) refreshSavedStatus.run();
    }

    @Override protected void onPause() {
        if (observingPreferences) Store.prefs(this).unregisterOnSharedPreferenceChangeListener(savedStatusChanged);
        observingPreferences = false;
        ui.removeCallbacks(refreshSavedStatus);
        super.onPause();
    }

    @Override public void onBackPressed() { leaveBackup(); }

    private void leaveBackup() {
        if (!alive()) return;
        if (leaveDialog != null && leaveDialog.isShowing()) return;
        if (busy && (progress.equals("Restoring…") || progress.equals("Importing…"))) {
            // Replacement has already been confirmed. Do not interrupt its apply/rollback
            // transaction just because a system Back gesture or the header is touched.
            leaveDialog = new AlertDialog.Builder(this).setTitle("Restore in progress")
                    .setMessage("Keep this screen open until your data has finished restoring.")
                    .setPositiveButton("Stay here",null).create();
            show(leaveDialog);
            return;
        }
        if (busy && backupCancellable) {
            leaveDialog = new AlertDialog.Builder(this).setTitle("Leave backup?")
                    .setMessage("This requests cancellation. If Google Drive has already saved the backup, that saved copy will remain.")
                    .setNegativeButton("Stay here",null)
                    .setPositiveButton("Cancel and leave",(dialog,which) -> {
                        cancelBackup();
                        finish();
                    }).create();
            show(leaveDialog);
            return;
        }
        finish();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Orientation is handled by this Activity; keep operation ownership and adapt the footer.
        adaptFeedbackLayout();
        renderFeedback();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        operation++;
        pendingAuth = null;
        ui.removeCallbacks(refreshSavedStatus);
        if (activeDialog != null) activeDialog.dismiss();
        activeDialog = null;
        worker.shutdownNow();
        synchronized (workerPasswords) {
            for (WorkerPassword password : workerPasswords) password.cancelIfQueued();
            workerPasswords.clear();
        }
        synchronized (passwords) {
            for (char[] password : passwords) Arrays.fill(password,'\0');
            passwords.clear();
        }
        super.onDestroy();
    }

    private boolean alive() { return !destroyed && !isFinishing() && !isDestroyed(); }
    private boolean owns(long ticket) { return alive() && ticket == operation; }

    private void render() {
        final int viewVersion = ++rendered;
        final int previousScroll = scroll.getScrollY();
        body.removeAllViews();
        renderFeedback();

        boolean connected = DriveBackup.configured(this);
        boolean ready = connected && DriveBackup.passwordReady(this);
        boolean reconnect = connected && DriveBackup.needsReconnect(this);
        boolean recovery = connected && DriveBackup.listingIncomplete(this);
        LinearLayout account = backupCard();
        account.setBackground(Ui.shape(Daybook.soft(this),17,this));
        account.addView(Daybook.text(this,"Google Drive",16,Daybook.ink(this),true));
        Ui.space(account,8);
        if (connected) {
            String name = DriveBackup.accountName(this);
            String email = DriveBackup.accountEmail(this);
            if (!name.isEmpty()) account.addView(Daybook.text(this,name,15,Daybook.ink(this),true));
            account.addView(Daybook.text(this,email,14,Daybook.ink(this),false));
            Ui.space(account,10);
        } else {
            account.addView(Daybook.text(this,"Connect your Google account to keep your history and settings safely backed up.",13,Daybook.ink(this),false));
            Ui.space(account,10);
        }
        status = Daybook.text(this,DriveBackup.summary(this),12,Daybook.ink(this),false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        account.addView(status);
        DriveBackup.requestSnapshotNote(this,note -> {
            if (alive() && !busy && viewVersion == rendered) {
                status.setText(DriveBackup.summary(this));
                renderFeedback();
            }
        });
        if (authProblem != null && !busy) {
            Ui.space(account,10);
            TextView problemTitle = Daybook.text(this,authProblemTitle,13,Daybook.coral(this),true);
            account.addView(problemTitle);
            TextView problem = Daybook.text(this,authProblem,12,Daybook.coral(this),false);
            problem.setTextIsSelectable(true);
            account.addView(problem);
            if (retryAuth != null && !authSetupProblem()) { Ui.space(account,8); action(account,"Retry",false,true,v -> retryAuth.run()); }
            if (authSetupProblem())
                action(account,"Copy setup details",false,true,v -> Ui.copy(this,authProblem));
        }
        Ui.space(account,16);
        if (!connected) {
            action(account,"Connect Google",true,true,v -> discover(null,Purpose.SETUP));
        } else {
            if (reconnect) action(account,"Reconnect Google",true,true,v -> discover(DriveBackup.accountEmail(this),Purpose.RECONNECT));
            if (!ready && !recovery) action(account,"Set up backup password",false,true,v -> discover(DriveBackup.accountEmail(this),Purpose.SETUP));
            if (recovery) action(account,"Check Drive backups",false,true,v -> discover(DriveBackup.accountEmail(this),Purpose.SETUP));
            action(account,"Change Google account",false,true,v -> discover(null,Purpose.SETUP));
        }
        account.addView(Daybook.text(this,"Backups are private to Voitation in your Google Drive. Disconnecting keeps your saved backups.",12,Daybook.ink(this),false));
        addCard(account);

        LinearLayout automatic = backupCard();
        automatic.addView(Daybook.text(this,"Automatic backup",15,Daybook.ink(this),true));
        Ui.space(automatic,8);
        LinearLayout row = Ui.row(this);
        row.addView(Daybook.text(this,"Frequency",14,Daybook.ink(this),false),new LinearLayout.LayoutParams(0,-2,1));
        Spinner frequency = new Spinner(this);
        frequency.setContentDescription("Backup frequency");
        String[] values = {"off","daily","weekly"};
        frequency.setAdapter(frequencyAdapter());
        frequency.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Daybook.accent(this)));
        String selected = Store.get(this,"backup_frequency","off");
        final int initial = selected.equals("daily") ? 1 : selected.equals("weekly") ? 2 : 0;
        frequency.setSelection(initial);
        frequency.setEnabled(!busy && ready && !reconnect && !recovery);
        frequency.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean initialized;
            public void onNothingSelected(AdapterView<?> parent) { }
            public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                if (!initialized) { initialized = true; if (position == initial) return; }
                if (!alive() || busy || viewVersion != rendered || !DriveBackup.passwordReady(BackupActivity.this)
                        || DriveBackup.listingIncomplete(BackupActivity.this)) return;
                if (values[position].equals(Store.get(BackupActivity.this,"backup_frequency","off"))) return;
                Store.prefs(BackupActivity.this).edit().putString("backup_frequency",values[position]).apply();
                DriveBackup.schedule(BackupActivity.this);
                status.setText(DriveBackup.summary(BackupActivity.this));
                feedback(position == 0 ? "Automatic backup off" : "Automatic backup updated",
                        position == 0 ? "You can still use Back up now whenever you need it."
                                : "Android will back up " + (position == 1 ? "daily" : "weekly") + " when your selected network is available.",NoticeKind.SUCCESS);
                renderFeedback();
            }
        });
        row.addView(frequency,new LinearLayout.LayoutParams(Ui.dp(this,130),Ui.dp(this,48)));
        automatic.addView(row);
        Switch wifi = new Switch(this);
        wifi.setText("Wi-Fi only");
        wifi.setTextColor(Daybook.ink(this));
        wifi.setTextSize(14);
        wifi.setThumbTintList(android.content.res.ColorStateList.valueOf(Daybook.surface(this)));
        wifi.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Daybook.action(this),Daybook.line(this)}));
        wifi.setContentDescription("Automatic backup on unmetered networks only");
        wifi.setChecked(Store.bool(this,"backup_wifi",true));
        wifi.setEnabled(!busy);
        wifi.setMinimumHeight(Ui.dp(this,48));
        wifi.setOnCheckedChangeListener((button,checked) -> {
            if (!alive() || busy || viewVersion != rendered) return;
            Store.prefs(this).edit().putBoolean("backup_wifi",checked).apply();
            DriveBackup.schedule(this);
            status.setText(DriveBackup.summary(this));
            feedback("Network preference saved",checked ? "Automatic backups will wait for an unmetered network, such as Wi-Fi."
                    : "Automatic backups can use mobile data. Your carrier’s data charges may apply.",NoticeKind.SUCCESS);
            renderFeedback();
        });
        automatic.addView(wifi);
        automatic.addView(Daybook.text(this,recovery ? "Uploads are paused because some Drive backups need attention. You can still try restoring an available backup." : !ready ? "Connect Google and protect your backup with a password to enable automatic backups." : reconnect ? "Reconnect Google to resume automatic backups." : "Off by default. Android backs up when your network and battery allow.",12,Daybook.muted(this),false));
        addCard(automatic);

        LinearLayout data = backupCard();
        data.addView(Daybook.text(this,"Your data, kept together",15,Daybook.ink(this),true));
        Ui.space(data,8);
        data.addView(Daybook.text(this,"Encrypted dictations, Quick Thoughts, dictionary and settings, including provider, model and endpoint choices. API keys, audio recordings, temporary Clipboard items and text from password or PIN fields are excluded. Reconnect your provider on a new phone or after an endpoint change.",13,Daybook.muted(this),false));
        Ui.space(data,16);
        action(data,"Back up now",true,ready && !reconnect && !recovery,v -> backupNow());
        action(data,"Restore from Google Drive",false,true,v -> discover(connected ? DriveBackup.accountEmail(this) : null,Purpose.RESTORE));
        data.addView(Daybook.text(this,"Keep your backup password. You need it to restore on another phone; Voitation cannot recover it.",12,Daybook.muted(this),false));
        addCard(data);

        if (!Store.get(this,"backup_uri","").isEmpty()) {
            LinearLayout previous = backupCard();
            previous.addView(Daybook.text(this,"Previous backup",16,Daybook.ink(this),true));
            Ui.space(previous,8);
            previous.addView(Daybook.text(this,"Your older backup file is kept until you finish connecting Google Drive.",12,Daybook.muted(this),false));
            Ui.space(previous,10);
            action(previous,"Import previous backup file",false,true,v -> launchLegacy());
            addCard(previous);
        }
        if (connected || !Store.get(this,"backup_uri","").isEmpty()) {
            action(body,"Disconnect backup",false,true,v -> confirmDisconnect());
        }
        scroll.post(() -> {
            if (alive() && viewVersion == rendered) scroll.scrollTo(0,previousScroll);
        });
    }

    /** Outside the scrolling settings: ongoing work and its result remain in view. */
    private void createFeedbackPanel(LinearLayout root) {
        feedbackPanel = backupCard();
        feedbackPanel.setTag("backup_feedback");
        feedbackPanel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout heading = Ui.row(this);
        LinearLayout labels = Ui.column(this);
        feedbackState = Daybook.text(this,"Status",11,Daybook.accent(this),true);
        feedbackTitle = Daybook.text(this,"",17,Daybook.ink(this),true);
        feedbackTitle.setMaxLines(2);
        feedbackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(feedbackState);
        labels.addView(feedbackTitle);
        heading.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        feedbackDetails = Daybook.button(this,"Details",false,v -> {
            if (!busy) show(new AlertDialog.Builder(this).setTitle(feedbackTitle.getText())
                    .setMessage(feedbackDetail.getText()).setPositiveButton("OK",null).create());
        });
        feedbackDetails.setContentDescription("View backup status details");
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-2,-2);
        detailParams.leftMargin = Ui.dp(this,8);
        heading.addView(feedbackDetails,detailParams);
        feedbackPanel.addView(heading);
        Ui.space(feedbackPanel,5);
        feedbackDetail = Daybook.text(this,"",14,Daybook.muted(this),false);
        // Full text stays accessible and is available in Details on compact screens.
        feedbackDetail.setMaxLines(getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 2 : 3);
        feedbackDetail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        feedbackPanel.addView(feedbackDetail);
        feedbackProgress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        feedbackProgress.setContentDescription("Backup operation in progress");
        feedbackProgress.setIndeterminate(true);
        feedbackProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Daybook.action(this)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1,Ui.dp(this,8));
        progressParams.topMargin = Ui.dp(this,8);
        feedbackPanel.addView(feedbackProgress,progressParams);
        feedbackCancel = Daybook.button(this,"Cancel backup",false,v -> cancelBackup());
        feedbackCancel.setContentDescription("Cancel backup");
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1,Ui.dp(this,44));
        cancelParams.topMargin = Ui.dp(this,8);
        feedbackPanel.addView(feedbackCancel,cancelParams);
        feedbackCancel.setVisibility(View.GONE);
        feedbackAction = Daybook.button(this,"Try again",true,v -> recoverBackup());
        feedbackAction.setContentDescription("Resolve backup status");
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1,Ui.dp(this,44));
        actionParams.topMargin = Ui.dp(this,8);
        feedbackPanel.addView(feedbackAction,actionParams);
        feedbackAction.setVisibility(View.GONE);
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1,-2);
        panelParams.setMargins(Ui.dp(this,20),Ui.dp(this,8),Ui.dp(this,20),Ui.dp(this,12));
        root.addView(feedbackPanel,panelParams);
    }

    private void adaptFeedbackLayout() {
        // Keep the actions usable in split-screen/landscape and with large accessibility text.
        feedbackTitle.setMaxLines(compactFeedback ? 1 : 2);
        feedbackState.setVisibility(compactFeedback ? View.GONE : View.VISIBLE);
        feedbackDetail.setVisibility(compactFeedback ? View.GONE : View.VISIBLE);
        feedbackDetail.setMaxLines(getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 2 : 3);
    }

    private void renderFeedback() {
        if (feedbackPanel == null) return;
        if (!busy && leaveDialog != null) {
            leaveDialog.dismiss();
            if (activeDialog == leaveDialog) activeDialog = null;
            leaveDialog = null;
        }
        String savedError = Store.get(this,"backup_error","");
        boolean hasResult = !notice.isEmpty();
        boolean error = !busy && (hasResult ? noticeKind == NoticeKind.ERROR || noticeKind == NoticeKind.WARNING : !savedError.isEmpty());
        String label = busy ? "In progress" : error ? "Needs attention" : hasResult && noticeKind == NoticeKind.SUCCESS ? "Completed" : "Status";
        String title = busy ? progress : hasResult ? noticeTitle : !savedError.isEmpty() ? "Backup needs attention" : "Your backup status";
        String detail = busy ? progressDetail(progress) : hasResult ? notice : !savedError.isEmpty() ? savedError : DriveBackup.summary(this);
        feedbackState.setText(label);
        feedbackState.setTextColor(error ? Daybook.coral(this) : Daybook.accent(this));
        feedbackTitle.setText(title);
        feedbackDetail.setText(detail);
        // A static state label remains clear when Android animations are disabled.
        feedbackProgress.setVisibility(busy && android.animation.ValueAnimator.areAnimatorsEnabled() ? View.VISIBLE : View.GONE);
        feedbackDetails.setVisibility(!busy && (compactFeedback || hasResult || !savedError.isEmpty()) ? View.VISIBLE : View.GONE);
        feedbackDetails.setEnabled(!busy);
        if (feedbackCancel != null) {
            feedbackCancel.setVisibility(busy && backupCancellable ? View.VISIBLE : View.GONE);
            feedbackCancel.setEnabled(!cancelRequested);
            feedbackCancel.setText(cancelRequested ? "Cancelling…" : "Cancel backup");
        }
        if (feedbackAction != null) {
            String action = error ? recoveryLabel() : "";
            feedbackAction.setText(action);
            feedbackAction.setVisibility(!busy && !action.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    private String recoveryLabel() {
        if (authSetupProblem()) return "Copy setup details";
        if (authProblem != null && retryAuth != null) return "Retry Google connection";
        if (retryOperation != null) return retryOperationLabel;
        if (!DriveBackup.configured(this)) return "Connect Google";
        if (DriveBackup.needsReconnect(this)) return "Reconnect Google";
        if (DriveBackup.listingIncomplete(this)) return "Check Drive backups";
        if (!DriveBackup.passwordReady(this)) return "Set up backup password";
        return "Try backup again";
    }

    private void recoverBackup() {
        if (!alive() || busy) return;
        if (authSetupProblem()) { Ui.copy(this,authProblem); return; }
        if (authProblem != null && retryAuth != null) { retryAuth.run(); return; }
        if (retryOperation != null) { retryOperation.run(); return; }
        repairOrRetryUpload();
    }

    private boolean authSetupProblem() {
        // This is the fixed, known DEVELOPER_ERROR title. Other cancelled or
        // unsuccessful Google outcomes are never treated as a setup diagnosis.
        return authProblem != null && "Google sign-in isn’t set up for this build".equals(authProblemTitle);
    }

    private void repairOrRetryUpload() {
        if (!alive() || busy) return;
        if (!DriveBackup.configured(this)) { discover(null,Purpose.SETUP); return; }
        if (DriveBackup.needsReconnect(this)) { discover(DriveBackup.accountEmail(this),Purpose.RECONNECT); return; }
        if (DriveBackup.listingIncomplete(this) || !DriveBackup.passwordReady(this)) {
            discover(DriveBackup.accountEmail(this),Purpose.SETUP); return;
        }
        backupNow();
    }

    /**
     * Cancel is honored at every step before Drive is asked to commit the new backup.
     * Before a worker starts (still connecting Google) it ends immediately; afterwards the
     * worker stops at its next checkpoint and removes any pending upload.
     */
    private void cancelBackup() {
        if (!busy || !backupCancellable || cancelRequested) return;
        cancelRequested = true;
        DriveBackup.requestCancel();
        if (!backupWorkerStarted) {
            operation++;
            pendingAuth = null;
            busy = false;
            backupCancellable = false;
            cancelRequested = false;
            feedback("Backup cancelled","Nothing was saved to Google Drive. Your previous backup is unchanged.",NoticeKind.INFO);
            render();
            return;
        }
        progress = "Cancelling…";
        renderFeedback();
    }

    /** These are real dispatcher boundaries, never timed stages or invented percentages. */
    private String progressDetail(String value) {
        if (value.equals("Connecting Google…")) return "Choose an account and review Google’s permission screen if asked.";
        if (value.equals("Checking Google Drive…")) return "Looking for Voitation backups in this Google account.";
        if (value.equals("Saving backup…")) return "Encrypting, uploading and verifying your backup. Keep this screen open.";
        if (value.equals("Checking backup…") || value.equals("Checking previous backup…")) return "Opening and validating the encrypted backup before asking you to replace any data.";
        if (value.equals("Protecting your backup…")) return "Checking the backup password and saving this phone’s connection.";
        if (value.equals("Restoring…") || value.equals("Importing…")) return "Applying the backup you confirmed. Keep this screen open until it finishes.";
        if (value.equals("Disconnecting…")) return "Stopping automatic backups and forgetting this phone’s saved connection.";
        if (value.equals("Reconnecting…")) return "Checking and saving your Google Drive connection.";
        return "Please keep this screen open until this step finishes.";
    }

    private void feedback(String title,String detail,NoticeKind kind) {
        noticeTitle = title;
        notice = detail;
        noticeKind = kind;
    }

    private String completedTitle(String value) {
        if (value.equals("Saving backup…")) return "Backup saved";
        if (value.equals("Restoring…")) return "Restore complete";
        if (value.equals("Importing…")) return "Import complete";
        if (value.equals("Disconnecting…")) return "Backup disconnected";
        if (value.equals("Reconnecting…")) return "Google Drive reconnected";
        if (value.equals("Protecting your backup…")) return "Google Drive connected";
        return "Step complete";
    }

    private String failedTitle(String value) {
        if (value.equals("Saving backup…")) return "Backup couldn’t finish";
        if (value.equals("Restoring…")) return "Restore couldn’t finish";
        if (value.equals("Importing…")) return "Import couldn’t finish";
        if (value.equals("Checking Google Drive…")) return "Couldn’t check Drive backups";
        if (value.equals("Protecting your backup…")) return "Couldn’t set up backup";
        if (value.equals("Disconnecting…")) return "Couldn’t disconnect backup";
        if (value.equals("Reconnecting…")) return "Couldn’t reconnect Google";
        return "Couldn’t complete this step";
    }

    private void action(LinearLayout parent,String label,boolean primary,boolean enabled,View.OnClickListener click) {
        Button button = Daybook.button(this,label,primary,v -> {
            if (alive() && !busy && enabled) click.onClick(v);
        });
        button.setEnabled(!busy && enabled);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this,8);
        parent.addView(button,params);
    }

    private LinearLayout backupCard() {
        LinearLayout card = Daybook.card(this);
        int padding = Ui.dp(this,13);
        card.setPadding(padding,padding,padding,padding);
        return card;
    }

    private void addCard(View card) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin = Ui.dp(this,12);
        body.addView(card,params);
    }

    private ArrayAdapter<String> frequencyAdapter() {
        return new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Off","Daily","Weekly"}) {
            @Override public View getView(int position,View recycled,android.view.ViewGroup parent) {
                TextView text = (TextView)super.getView(position,recycled,parent);
                text.setTextColor(Daybook.accent(BackupActivity.this));
                text.setTextSize(14);
                return text;
            }
            @Override public View getDropDownView(int position,View recycled,android.view.ViewGroup parent) {
                TextView text = (TextView)super.getDropDownView(position,recycled,parent);
                text.setTextColor(Daybook.ink(BackupActivity.this));
                text.setBackgroundColor(Daybook.surface(BackupActivity.this));
                text.setMinimumHeight(Ui.dp(BackupActivity.this,48));
                return text;
            }
        };
    }

    private void colorDialogText(View view) {
        if (view == null) return;
        if (view instanceof TextView) ((TextView)view).setTextColor(Daybook.ink(this));
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup)view;
            for (int index = 0; index < group.getChildCount(); index++) colorDialogText(group.getChildAt(index));
        }
    }

    private enum Purpose { SETUP, RECONNECT, RESTORE }
    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value); }

    private long begin(String value) {
        if (!alive() || busy) return -1;
        busy = true;
        authProblemTitle = null;
        authProblem = null;
        notice = "";
        noticeTitle = "";
        noticeKind = NoticeKind.INFO;
        retryOperation = null;
        retryOperationLabel = "";
        progress = value;
        long ticket = ++operation;
        render();
        return ticket;
    }

    private <T> void task(long ticket,String value,Work<T> work,Done<T> done) {
        if (!owns(ticket)) return;
        progress = value;
        renderFeedback();
        worker.execute(() -> {
            T result = null;
            Exception failure = null;
            try { result = work.run(); } catch (Exception error) { failure = error; }
            final T completed = result;
            final Exception problem = failure;
            runOnUiThread(() -> {
                if (!owns(ticket)) return;
                busy = false;
                pendingAuth = null;
                backupCancellable = false; cancelRequested = false; backupWorkerStarted = false;
                if (problem instanceof DriveBackup.CancelledException) feedback("Backup cancelled",message(problem),NoticeKind.INFO);
                else if (problem != null) feedback(failedTitle(value),message(problem),NoticeKind.ERROR);
                else { retryOperation = null; retryOperationLabel = ""; }
                render();
                if (problem == null && done != null) done.accept(completed);
            });
        });
    }

    private void perform(String value,Work<String> work) {
        long ticket = begin(value);
        if (ticket < 0) return;
        if (value.equals("Importing…")) {
            retryOperation = this::launchLegacy;
            retryOperationLabel = "Choose previous backup";
        } else if (value.equals("Disconnecting…")) {
            retryOperation = this::confirmDisconnect;
            retryOperationLabel = "Try disconnect again";
        } else if (value.equals("Reconnecting…")) {
            retryOperation = () -> discover(DriveBackup.accountEmail(this),Purpose.RECONNECT);
            retryOperationLabel = "Reconnect Google";
        }
        task(ticket,value,work,result -> { feedback(completedTitle(value),result,NoticeKind.SUCCESS); render(); });
    }

    private void confirmDisconnect() {
        if (!alive() || busy) return;
        show(new AlertDialog.Builder(this).setTitle("Disconnect backup?")
                .setMessage("Stops automatic backups on this phone and forgets the saved backup password. Your backups stay in Google Drive.")
                .setNegativeButton("Keep connected",null)
                .setPositiveButton("Disconnect",(dialog,which) -> perform("Disconnecting…",() -> {
                    DriveBackup.disconnect(getApplicationContext()); return "Backup disconnected.";
                })).create());
    }

    private void authorize(String email,String value,String retryLabel,Done<String> ready) {
        long ticket = begin(value);
        if (ticket < 0) return;
        retryOperation = retryAuth;
        retryOperationLabel = retryLabel;
        pendingAuth = new DriveAuth.Callback() {
            @Override public void ready(String token) {
                if (!owns(ticket)) return;
                pendingAuth = null;
                ready.accept(token);
            }
            @Override public void failed(Exception failure) {
                if (!owns(ticket)) return;
                pendingAuth = null;
                busy = false;
                backupCancellable = false; cancelRequested = false; backupWorkerStarted = false;
                String detail = message(failure);
                String title = failure instanceof DriveAuth.AuthException && ((DriveAuth.AuthException) failure).title != null ? ((DriveAuth.AuthException) failure).title : "Google connection needs attention";
                boolean cancelled = title.equals("Google connection cancelled");
                feedback(title,detail,cancelled ? NoticeKind.INFO : NoticeKind.ERROR);
                // Shown inline in the Google Drive card with Retry until the next attempt.
                authProblemTitle = title; authProblem = detail;
                render();
            }
        };
        DriveAuth.authorize(this,email,pendingAuth);
    }

    private String authProblemTitle, authProblem;
    private Runnable retryAuth;
    private void discover(String email,Purpose purpose) {
        retryAuth = () -> discover(email,purpose);
        authProblemTitle = null; authProblem = null;
        authorize(email,"Connecting Google…","Check Drive again",token -> {
            long ticket = operation;
            task(ticket,"Checking Google Drive…",() -> {
                DriveBackup.Connection connection = DriveBackup.discover(getApplicationContext(),token);
                if (email != null && !email.equalsIgnoreCase(connection.profile.email))
                    throw new Exception("Choose the Google account already connected to this phone, or use Change Google account.");
                return connection;
            },connection -> {
                if (connection.hasRejected) {
                    offerRecovery(connection);
                    return;
                }
                if (purpose == Purpose.RECONNECT && DriveBackup.passwordReady(this)) {
                    perform("Reconnecting…",() -> { DriveBackup.connect(getApplicationContext(),connection); return "Google Drive reconnected."; });
                } else if (purpose == Purpose.RESTORE) {
                    selectBackup(connection,file -> passwordDialog(false,password -> checkArchive(connection,file,password,false)));
                } else {
                    if (connection.files.isEmpty()) {
                        passwordDialog(true,password -> configure(connection,password,null));
                    } else {
                        selectBackup(connection,file -> passwordDialog(false,password -> checkArchive(connection,file,password,true)));
                    }
                }
            });
        });
    }

    private void backupNow() {
        if (!DriveBackup.configured(this) || !DriveBackup.passwordReady(this) || DriveBackup.needsReconnect(this)
                || DriveBackup.listingIncomplete(this)) return;
        final int epoch = DriveBackup.cancelEpoch();
        retryAuth = this::repairOrRetryUpload;
        authorize(DriveBackup.accountEmail(this),"Connecting Google…","Try backup again",token -> {
            if (DriveBackup.cancelEpoch() != epoch) return;
            long ticket = operation;
            backupWorkerStarted = true;
            task(ticket,"Saving backup…",() -> {
                String note = DriveBackup.backup(getApplicationContext(),token,epoch);
                String saved = "Your encrypted backup was saved and verified in Google Drive.";
                if (DriveBackup.cancelEpoch() != epoch) saved = "Cancel arrived after Google Drive was already committing, so this backup was saved and verified.";
                return note.isEmpty() ? saved : saved + " " + note;
            },result -> { feedback("Backup saved",result,NoticeKind.SUCCESS); render(); });
        });
        if (busy) { backupCancellable = true; cancelRequested = false; backupWorkerStarted = false; renderFeedback(); }
    }

    private void selectBackup(DriveBackup.Connection connection,Done<DriveCloudClient.BackupFile> selected) {
        List<DriveCloudClient.BackupFile> files = connection.files;
        if (files.isEmpty()) { feedback(connection.hasRejected ? "Backups need attention" : "No backup found",connection.hasRejected ? connection.warning : "No Voitation backup was found in this Google account.",connection.hasRejected ? NoticeKind.ERROR : NoticeKind.INFO); render(); return; }
        if (files.size() == 1) { selected.accept(files.get(0)); return; }
        int count = files.size();
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) labels[i] = (i == 0 ? (connection.hasRejected ? "Newest available · " : "Latest · ") : "") + backupDate(files.get(i));
        AlertDialog picker=new AlertDialog.Builder(this).setTitle(connection.hasRejected ? "Choose an available backup" : "Choose a backup")
                .setItems(labels,(dialog,index) -> selected.accept(files.get(index)))
                .setNegativeButton("Cancel",null).create();
        show(picker);
        // Keep the compact three-row viewport while allowing recovery from every
        // discovered generation, including an older fourth or fifth backup.
        ListView list=picker.getListView();
        if(list!=null && count>3) {
            list.post(()->{
                if(!picker.isShowing() || list.getAdapter()==null)return;
                int height=0,width=View.MeasureSpec.makeMeasureSpec(Math.max(1,list.getWidth()),View.MeasureSpec.EXACTLY);
                for(int i=0;i<3;i++){View row=list.getAdapter().getView(i,null,list);row.measure(width,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));height+=Math.max(row.getMeasuredHeight(),Ui.dp(this,48));}
                int available=Math.max(Ui.dp(this,48),getResources().getDisplayMetrics().heightPixels-Ui.dp(this,200));
                android.view.ViewGroup.LayoutParams params=list.getLayoutParams();params.height=Math.min(height+list.getDividerHeight()*2,available);list.setLayoutParams(params);
            });
        }
    }

    private void offerRecovery(DriveBackup.Connection connection) {
        feedback("Backups need attention",connection.profile.email + " · " + connection.warning,NoticeKind.WARNING);
        render();
        String detail = connection.warning + "\n\n" + connection.profile.email + "\n\n"
                + (connection.files.isEmpty()
                ? "No available backup can be tried. New backups stay blocked to protect the existing Drive files."
                : "You can try an available backup. New uploads stay paused while the other Drive files need attention.");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Some backups need attention")
                .setMessage(detail).setNegativeButton("Keep current data",null);
        if (!connection.files.isEmpty()) builder.setPositiveButton("Choose a backup",(dialog,which) ->
                selectBackup(connection,file -> passwordDialog(false,password -> checkArchive(connection,file,password,false))));
        show(builder.create());
    }

    private String backupDate(DriveCloudClient.BackupFile file) {
        try { return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date.from(Instant.parse(file.modifiedTime))); }
        catch (Exception malformed) { return "Saved backup"; }
    }

    private void configure(DriveBackup.Connection connection,char[] password,DriveCloudClient.BackupFile existing) {
        if (connection.hasRejected) { release(password); feedback("Backups need attention",connection.warning,NoticeKind.ERROR); render(); return; }
        long ticket = begin("Protecting your backup…");
        if (ticket < 0) { release(password); return; }
        final String account = connection.profile.email;
        retryOperation = () -> discover(account,Purpose.SETUP);
        retryOperationLabel = "Try setup again";
        WorkerPassword owned = ownedPassword(password,true);
        task(ticket,"Protecting your backup…",() -> {
            try {
                owned.start();
                DriveBackup.configurePassword(getApplicationContext(),connection,owned.value,existing);
                return "Google Drive connected. Back up now or choose an automatic schedule.";
            } finally { owned.close(); }
        },result -> { feedback("Google Drive connected",result,NoticeKind.SUCCESS); render(); });
    }

    private void checkArchive(DriveBackup.Connection connection,DriveCloudClient.BackupFile file,char[] password,boolean allowKeep) {
        long ticket = begin("Checking backup…");
        if (ticket < 0) { release(password); return; }
        final String account = connection.profile.email;
        retryOperation = () -> discover(account,allowKeep ? Purpose.SETUP : Purpose.RESTORE);
        retryOperationLabel = "Try unlocking again";
        WorkerPassword owned = ownedPassword(password,false);
        worker.execute(() -> {
            JSONObject checked = null;
            Exception failure = null;
            try { owned.start(); checked = DriveBackup.read(getApplicationContext(),connection,file,owned.value); }
            catch (Exception error) { failure = error; }
            finally { owned.close(); }
            final JSONObject archive = checked;
            final Exception problem = failure;
            runOnUiThread(() -> {
                if (!owns(ticket)) { release(password); return; }
                busy = false;
                if (problem != null) {
                    release(password);
                    feedback("Couldn’t unlock this backup",message(problem),NoticeKind.ERROR);
                    render();
                    return;
                }
                render();
                confirmArchive(connection,file,password,archive,allowKeep);
            });
        });
    }

    private void confirmArchive(DriveBackup.Connection connection,DriveCloudClient.BackupFile file,char[] password,JSONObject archive,boolean allowKeep) {
        allowKeep = allowKeep && !connection.hasRejected;
        final boolean[] transferred = {false};
        String description = BackupData.describe(archive) + "\n\n" + connection.profile.email + " · " + backupDate(file)
                + "\n\nRestoring replaces this phone’s history, dictionary and settings, including provider, model and endpoint choices. API keys are not restored. This phone’s existing key is kept only if the provider endpoint stays the same. Reconnect your provider on a new phone or after an endpoint change.";
        if (connection.hasRejected) description = connection.warning + "\n\n" + description
                + "\n\nUploads stay paused while any Drive backup needs attention. Existing Drive files are kept.";
        if (allowKeep) description += " You can also connect to this backup while keeping this phone’s current data.";
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(allowKeep ? "Backup found" : "Restore this backup?")
                .setMessage(description)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Restore",(dialog,which) -> {
                    transferred[0] = true;
                    restore(connection,file,password,archive);
                });
        if (allowKeep) builder.setNeutralButton("Keep this phone’s data",(dialog,which) -> {
            transferred[0] = true;
            configure(connection,password,file);
        });
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(ignored -> {
            if (!transferred[0]) release(password);
            if (activeDialog == dialog) activeDialog = null;
        });
        show(dialog);
    }

    private void restore(DriveBackup.Connection connection,DriveCloudClient.BackupFile file,char[] password,JSONObject archive) {
        long ticket = begin("Restoring…");
        if (ticket < 0) { release(password); return; }
        final String account = connection.profile.email;
        retryOperation = () -> discover(account,Purpose.RESTORE);
        retryOperationLabel = "Try restore again";
        WorkerPassword owned = ownedPassword(password,true);
        task(ticket,"Restoring…",() -> {
            try {
                owned.start();
                DriveBackup.restore(getApplicationContext(),connection,owned.value,file,archive);
                String result = "Your history and settings have been restored. Reconnect your provider on a new phone or after an endpoint change.";
                if (DriveBackup.listingIncomplete(getApplicationContext()))
                    return result + " Uploads remain paused because some Drive backups need attention.";
                return DriveBackup.passwordReady(getApplicationContext()) ? result : result + " Unlock the latest Drive backup before enabling uploads.";
            } finally { owned.close(); }
        },result -> {
            boolean paused = DriveBackup.listingIncomplete(this) || !DriveBackup.passwordReady(this);
            if (paused) retryOperation = null;
            feedback(paused ? "Restored · uploads paused" : "Restore complete",result,paused ? NoticeKind.WARNING : NoticeKind.SUCCESS);
            render();
        });
    }

    private interface PasswordAction { void accept(char[] password); }
    private EditText passwordField(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextSize(16);
        field.setTextColor(Daybook.ink(this));
        field.setHintTextColor(Daybook.muted(this));
        android.graphics.drawable.GradientDrawable outline = Ui.shape(Daybook.surface(this),12,this);
        outline.setStroke(Ui.dp(this,1),Daybook.controlOutline(this));
        field.setBackground(outline);
        field.setPadding(Ui.dp(this,12),Ui.dp(this,11),Ui.dp(this,12),Ui.dp(this,11));
        field.setMinimumHeight(Ui.dp(this,48));
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSaveEnabled(false);
        field.setFreezesText(false);
        return field;
    }

    private void passwordDialog(boolean create,PasswordAction action) {
        if (!alive()) return;
        LinearLayout form = Ui.column(this);
        form.setPadding(Ui.dp(this,24),Ui.dp(this,12),Ui.dp(this,24),Ui.dp(this,12));
        form.addView(Daybook.text(this,create ? "Create a separate backup password with at least 8 characters. Keep it for restoring on another phone." : "Enter the password used to protect this backup.",14,Daybook.muted(this),false));
        EditText first = passwordField("Backup password");
        form.addView(first);
        EditText second = create ? passwordField("Confirm password") : null;
        if (second != null) form.addView(second);
        TextView error = Daybook.text(this,"",12,Daybook.ink(this),false);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        form.addView(error);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(create ? "Protect your backup" : "Unlock backup")
                .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Continue",null).create();
        dialog.setOnDismissListener(ignored -> {
            first.getText().clear();
            if (second != null) second.getText().clear();
            if (activeDialog == dialog) activeDialog = null;
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!alive() || busy) return;
            char[] password = new char[first.length()];
            first.getText().getChars(0,first.length(),password,0);
            try {
                BackupArchive.checkPassword(password);
                if (second != null) {
                    char[] confirm = new char[second.length()];
                    second.getText().getChars(0,second.length(),confirm,0);
                    boolean match = Arrays.equals(password,confirm);
                    Arrays.fill(confirm,'\0');
                    if (!match) throw new Exception("The passwords do not match.");
                }
                synchronized (passwords) { passwords.add(password); }
                dialog.dismiss();
                action.accept(password);
            } catch (Exception failure) {
                release(password);
                error.setText(message(failure));
            }
        }));
        show(dialog);
    }

    private void release(char[] password) {
        if (password == null) return;
        Arrays.fill(password,'\0');
        synchronized (passwords) { passwords.remove(password); }
    }

    /** Teardown can erase UI buffers without changing a password being derived by a worker. */
    private WorkerPassword ownedPassword(char[] source,boolean consume) {
        WorkerPassword password = new WorkerPassword(source.clone());
        synchronized (workerPasswords) { workerPasswords.add(password); }
        if (consume) release(source);
        return password;
    }

    private final class WorkerPassword {
        final char[] value;
        private boolean started, cancelled;
        WorkerPassword(char[] value) { this.value = value; }
        synchronized void start() throws java.io.IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) throw new java.io.IOException("Backup interrupted. Please try again.");
            started = true;
        }
        synchronized void cancelIfQueued() {
            if (!started) { cancelled = true; Arrays.fill(value,'\0'); }
        }
        void close() {
            synchronized (this) { Arrays.fill(value,'\0'); cancelled = true; }
            synchronized (workerPasswords) { workerPasswords.remove(this); }
        }
    }

    private void show(AlertDialog dialog) {
        if (!alive()) return;
        if (activeDialog != null && activeDialog != dialog) activeDialog.dismiss();
        activeDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(Ui.shape(Daybook.surface(this),22,this));
        colorDialogText(dialog.getWindow() == null ? null : dialog.getWindow().getDecorView());
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) button.setTextColor(Daybook.accent(this));
        }
    }

    private String message(Exception error) {
        String value = error.getMessage();
        if (value == null || value.trim().isEmpty()) return "Could not complete this operation. Please try again.";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length() && clean.length() < 400; i++) {
            char letter = value.charAt(i);
            if (!Character.isISOControl(letter)) clean.append(letter);
        }
        String detail = clean.toString().trim();
        return detail.isEmpty() ? "Could not complete this operation. Please try again." : detail;
    }

    private void launchLegacy() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),OPEN_LEGACY);
        } catch (android.content.ActivityNotFoundException missing) {
            feedback("Import unavailable","No file picker is available to import your previous backup.",NoticeKind.ERROR);
            render();
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == DriveAuth.REQUEST_CODE) {
            DriveAuth.Callback callback = pendingAuth;
            if (callback != null && alive()) DriveAuth.result(this,request,result,data,callback);
            else if (alive()) {
                // The screen was recreated while Google's UI was open, so the original request is gone.
                android.util.Log.w("VonoTationDrive","consent result arrived without a pending request: resultCode=" + result);
                busy = false;
                authProblemTitle = "Google connection was interrupted";
                authProblem = "Voitation restarted while Google was open (resultCode " + result + "). Tap Connect Google again.";
                feedback(authProblemTitle,authProblem,NoticeKind.WARNING);
                render();
            }
            return;
        }
        if (request == OPEN_LEGACY && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            passwordDialog(false,password -> unlockLegacy(uri,password));
        }
    }

    private void unlockLegacy(Uri uri,char[] password) {
        long ticket = begin("Checking previous backup…");
        if (ticket < 0) { release(password); return; }
        retryOperation = this::launchLegacy;
        retryOperationLabel = "Choose previous backup";
        WorkerPassword owned = ownedPassword(password,true);
        task(ticket,"Checking previous backup…",() -> {
            try { owned.start(); return DriveBackup.readLegacy(getApplicationContext(),uri,owned.value); }
            finally { owned.close(); }
        },archive -> show(new AlertDialog.Builder(this).setTitle("Import this backup?")
                .setMessage(BackupData.describe(archive) + "\n\nReplaces this phone’s history, dictionary and settings, including provider, model and endpoint choices. API keys from older backups are not imported. This phone’s existing key is kept only if the provider endpoint stays the same. Reconnect your provider on a new phone or after an endpoint change. Your Google Drive connection stays on this phone.")
                .setNegativeButton("Keep current data",null)
                .setPositiveButton("Import",(dialog,which) -> perform("Importing…",() -> { DriveBackup.restore(getApplicationContext(),archive); return "Your previous history and settings have been imported. Reconnect your provider on a new phone or after an endpoint change."; }))
                .create()));
    }
}
