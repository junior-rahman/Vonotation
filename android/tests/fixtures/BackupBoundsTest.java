package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/** Actual production BackupActivity bounds and first-touch checks. All data/work is synthetic. */
public final class BackupBoundsTest extends Instrumentation {
    private static final String ERROR = "Synthetic bounds check only. No Google connection or upload was attempted. "
            + "This deliberately long backup-status detail must remain complete in the actual native Details dialog, "
            + "including the final marker: BOUNDS-END.";
    private static final String SUCCESS = "Synthetic bounds worker completed locally. No backup was uploaded. BOUNDS-COMPLETE.";
    private volatile BackupActivity activity;
    private Context context;
    private UiAutomation automation;
    private Bundle arguments;
    private SharedPreferences prefs;
    private final AtomicInteger externalRequests = new AtomicInteger();
    private final JSONArray measurements = new JSONArray();
    private final JSONArray actionTextIssues = new JSONArray();
    private ControlledWork work;
    private int checks, physicalTaps, physicalBacks, initialOrientation;
    private String phase = "starting", caseName;

    @Override public void onCreate(Bundle args) { arguments = args == null ? new Bundle() : args; super.onCreate(args); start(); }
    @Override public void callActivityOnCreate(Activity target, Bundle state) {
        if (target instanceof BackupActivity) activity = (BackupActivity) target;
        super.callActivityOnCreate(target, state);
    }
    @Override public void onStart() {
        Bundle report = new Bundle();
        context = getTargetContext(); prefs = Store.prefs(context);
        Map<String, ?> originalPrefs = prefs.getAll(), originalSecrets = BackupSecrets.snapshot(context);
        caseName = arguments.getString("case", "backup-bounds").replaceAll("[^a-zA-Z0-9_-]", "_");
        try {
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("http") || protocol.equals("https") ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL address) throws IOException {
                    externalRequests.incrementAndGet(); throw new IOException("Synthetic bounds fixture blocks external requests");
                }
            } : null);
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            setInTouchMode(true);
            DriveBackup.disconnect(context);
            require(prefs.edit().clear().putString("engine", "offline").putBoolean("daily_summaries", false)
                    .putString("backup_frequency", "off").putBoolean("haptic_feedback", false)
                    .putString("backup_error", ERROR).commit(), "Could not seed synthetic status");
            launch();
            require("1.9.10".equals(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName),
                    "Fixture requires the exact 1.9.10 app; runtime APK hash must also be recorded by runner");
            String[] orientations = arguments.getString("orientations", "portrait,landscape,portrait").split(",");
            for (int index = 0; index < orientations.length; index++) {
                int orientation = parseOrientation(orientations[index]);
                progress("Bounds, pinned footer and first Details tap: " + orientations[index] + " visit " + index);
                orient(orientation);
                verifyConfiguration();
                assertBounds("visit-" + index + "-top", true);
                assertPinned("visit-" + index);
                inspectActionText("visit-" + index);
                assertDetails(ERROR, false);
                // A fresh physical first tap after native Back dismissal must work too.
                assertDetails(ERROR, true);
                assertBounds("visit-" + index + "-returned", true);
                capture("visit-" + index);
            }
            if (!"false".equals(arguments.getString("exerciseBusy", "true"))) busyOrientationReturn();
            // Finish the actual Activity using its Back control and return to a fresh owner.
            progress("Repeated return after finishing native BackupActivity");
            BackupActivity old = activity;
            singleTap(description("Back to settings"));
            waitUntil(() -> old.isDestroyed(), "Back did not destroy the old BackupActivity");
            launch();
            orient(parseOrientation(orientations[0]));
            verifyConfiguration();
            require(!busy() && ERROR.equals(((TextView)field(activity, "feedbackDetail")).getText().toString()),
                    "Fresh BackupActivity retained stale synthetic completion/busy state");
            assertBounds("fresh-return", true); assertDetails(ERROR, true);
            require(externalRequests.get() == 0, "Fixture attempted an external HTTP/HTTPS request");
            require("off".equals(Store.get(context, "backup_frequency", ""))
                    && ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID) == null,
                    "Bounds testing enabled scheduled backup");
            require(actionTextIssues.length() == 0, "Clipped or unreachable native backup action text: " + actionTextIssues);
            report.putString("result", "PASS: " + checks + " assertions; " + physicalTaps + " single physical DOWN/UP taps; "
                    + physicalBacks + " native Back key presses. Actual BackupActivity viewport, pinned compact footer, complete Details dialog, "
                    + "orientation during local synthetic work and repeated return checked. No live Google or real data used.");
            report.putString("capture_directory", mediaDirectory().getAbsolutePath());
        } catch (Throwable failure) {
            report.putString("failure", android.util.Log.getStackTraceString(failure)); report.putString("phase", phase);
        } finally {
            if (work != null) work.release.countDown();
            try {
                if (activity != null && !activity.isDestroyed()) runOnMainSync(() -> {
                    activity.setRequestedOrientation(initialOrientation); activity.finish();
                });
                waitForIdleSync();
            } catch (Throwable failure) { appendFailure(report, "Activity cleanup", failure); }
            try {
                restorePreferences(context.getSharedPreferences("voiceflow_backup_secrets", Context.MODE_PRIVATE), originalSecrets);
                restorePreferences(prefs, originalPrefs); DriveBackup.schedule(context);
                if (!originalPrefs.equals(prefs.getAll()) || !originalSecrets.equals(BackupSecrets.snapshot(context)))
                    throw new IOException("Preference cleanup mismatch");
            } catch (Throwable failure) { appendFailure(report, "Local-data cleanup", failure); }
        }
        report.putString("case", caseName); report.putString("bounds", measurements.toString());
        report.putString("action_text_issues", actionTextIssues.toString());
        report.putInt("executed_checks", checks); report.putInt("physical_taps", physicalTaps); report.putInt("physical_backs", physicalBacks);
        finish(report.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, report);
    }
    private void launch() throws Exception {
        activity = (BackupActivity) startActivitySync(new Intent(context, BackupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        initialOrientation = activity.getRequestedOrientation();
        settle();
    }
    private int parseOrientation(String value) throws Exception {
        if ("portrait".equals(value.trim())) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if ("landscape".equals(value.trim())) return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        throw new Exception("orientations must be a comma-separated portrait/landscape list");
    }
    private void orient(int requested) throws Exception {
        BackupActivity owner = activity;
        int expected = requested == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        runOnMainSync(() -> owner.setRequestedOrientation(requested));
        waitUntil(() -> activity.getResources().getConfiguration().orientation == expected,
                "Android did not honor requested orientation; runner must allow activity orientation");
        settle();
        require(activity == owner && !owner.isDestroyed(), "Orientation recreated the owner despite handled configuration");
    }
    private void verifyConfiguration() throws Exception {
        Configuration c = activity.getResources().getConfiguration();
        String font = arguments.getString("fontScale");
        if (font != null) require(Math.abs(c.fontScale - Float.parseFloat(font)) < .03f,
                "Expected fontScale=" + font + " but actual=" + c.fontScale);
        View root = ((View)field(activity, "feedbackPanel")).getRootView();
        float height = bounds(root).height() / activity.getResources().getDisplayMetrics().density;
        String max = arguments.getString("maxHeightDp"), min = arguments.getString("minHeightDp");
        if (max != null) require(height <= Float.parseFloat(max), "Actual window height " + height + "dp exceeds requested maximum " + max);
        if (min != null) require(height >= Float.parseFloat(min), "Actual window height " + height + "dp below requested minimum " + min);
    }
    private void assertBounds(String stage, boolean detailsExpected) throws Exception {
        settle();
        View panel = (View)field(activity, "feedbackPanel");
        ScrollView scroll = (ScrollView)field(activity, "scroll");
        View details = (View)field(activity, "feedbackDetails");
        View root = (View)panel.getParent();
        TextView title = (TextView)field(activity,"feedbackTitle");
        View progress = (View)field(activity,"feedbackProgress");
        boolean animations = android.animation.ValueAnimator.areAnimatorsEnabled();
        Rect rootRect = bounds(root), viewport = bounds(scroll), footer = bounds(panel), button = bounds(details);
        float density = activity.getResources().getDisplayMetrics().density;
        int[] available = {0}; runOnMainSync(() -> available[0] = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
        boolean compact = (Boolean)field(activity, "compactFeedback");
        boolean expectedCompact = available[0] < Ui.dp(activity,480)
                || (activity.getResources().getConfiguration().fontScale > 1.3f && available[0] < Ui.dp(activity,620));
        JSONObject measurement = new JSONObject().put("stage", stage)
                .put("orientation", activity.getResources().getConfiguration().orientation).put("fontScale", activity.getResources().getConfiguration().fontScale)
                .put("density", density).put("availableHeightDp", available[0]/density).put("compact", compact)
                .put("root", rootRect.toShortString()).put("viewport", viewport.toShortString()).put("footer", footer.toShortString())
                .put("details", button.toShortString()).put("scrollY", scroll.getScrollY())
                .put("title",title.getText().toString()).put("titleBounds",bounds(title).toShortString())
                .put("titleEllipsis",title.getLayout()==null ? -1 : title.getLayout().getEllipsisCount(0))
                .put("animationsEnabled",animations).put("progressVisible",progress.getVisibility()==View.VISIBLE);
        measurements.put(measurement); Bundle status = new Bundle(); status.putString("measurement", measurement.toString()); sendStatus(0,status);
        require(!rootRect.isEmpty() && viewport.height() >= Ui.dp(activity,48) && viewport.width() >= Ui.dp(activity,120),
                "No usable settings viewport (must contain at least one 48dp-high touch row): " + measurement);
        require(!footer.isEmpty() && rootRect.contains(footer) && viewport.bottom <= footer.top && !Rect.intersects(viewport,footer),
                "Footer overlaps or escapes the settings viewport/root: " + measurement);
        require(fullBounds(panel).equals(footer), "Footer is clipped by its actual native window: " + measurement);
        require(compact == expectedCompact, "Compact flag does not match current available height/font threshold: " + measurement);
        require(title.isShown() && !bounds(title).isEmpty() && footer.contains(bounds(title)) && fullBounds(title).equals(bounds(title)),
                "Actual visible stage/status title is clipped or missing: " + measurement);
        if (!detailsExpected) {
            require("Saving backup…".equals(title.getText().toString()), "Busy state has no actual visible operation title");
            require(progress.getVisibility() == (animations ? View.VISIBLE : View.GONE), "Busy indicator does not honor Android animation setting");
        }
        require(panel.getAccessibilityLiveRegion() == View.ACCESSIBILITY_LIVE_REGION_POLITE, "Feedback lost its accessible live region");
        require(!hasScrollAncestor(panel), "Pinned feedback is inside a ScrollView");
        View detailText = (View)field(activity,"feedbackDetail"), stateText = (View)field(activity,"feedbackState");
        require(detailText.getVisibility() == (compact ? View.GONE : View.VISIBLE)
                && stateText.getVisibility() == (compact ? View.GONE : View.VISIBLE), "Compact/noncompact footer labels are wrong");
        if (detailsExpected) {
            require(details.isEnabled() && details.isShown() && !details.isFocusableInTouchMode(), "Details is missing, disabled, or steals first touch for focus");
            require(!button.isEmpty() && footer.contains(button) && fullBounds(details).equals(button)
                    && button.width() >= Ui.dp(activity,44) && button.height() >= Ui.dp(activity,44),
                    "Details is clipped or lacks a reachable 44dp touch target: " + measurement);
        } else require(details.getVisibility() == View.GONE && !details.isEnabled(), "Busy work must hide and disable Details");
    }
    private void assertPinned(String stage) throws Exception {
        ScrollView scroll = (ScrollView)field(activity,"scroll"); View panel = (View)field(activity,"feedbackPanel");
        scrollToEdge(scroll,View.FOCUS_UP); Rect before = bounds(panel);
        scrollToEdge(scroll,View.FOCUS_DOWN);
        require(before.equals(bounds(panel)), "Pinned footer moved when scrolling settings");
        int[] content = {0}; runOnMainSync(() -> content[0] = scroll.getChildAt(0).getHeight());
        require(content[0] <= scroll.getHeight() || scroll.getScrollY() > 0, "Overflowing settings cannot scroll to their bottom");
        assertBounds(stage + "-bottom", true);
        scrollToEdge(scroll,View.FOCUS_UP);
        require(scroll.getScrollY() == 0, "Could not return to top of settings");
    }
    /** Wait for the requested scroll endpoint and a stable footer, not a fixed animation delay. */
    private void scrollToEdge(ScrollView scroll,int direction) throws Exception {
        View panel=(View)field(activity,"feedbackPanel");
        runOnMainSync(() -> scroll.fullScroll(direction));
        long until=SystemClock.elapsedRealtime()+10000,stableSince=-1;
        int previousY=Integer.MIN_VALUE;Rect previousFooter=null;
        while(SystemClock.elapsedRealtime()<until){
            int[] y={0};boolean[] ready={false};Rect footer=new Rect();
            runOnMainSync(() -> {
                y[0]=scroll.getScrollY();
                ready[0]=scroll.isLaidOut() && !scroll.isLayoutRequested() && scroll.getChildCount()>0
                        && !scroll.getChildAt(0).isLayoutRequested() && !panel.isLayoutRequested()
                        && (direction==View.FOCUS_UP ? y[0]==0 : !scroll.canScrollVertically(1));
                if(panel.isShown() && panel.getLocalVisibleRect(footer)){
                    int[] xy=new int[2];panel.getLocationOnScreen(xy);footer.offset(xy[0]-panel.getScrollX(),xy[1]-panel.getScrollY());
                }else footer.setEmpty();
            });
            long now=SystemClock.elapsedRealtime();
            if(ready[0] && !footer.isEmpty() && y[0]==previousY && footer.equals(previousFooter)){
                if(stableSince<0)stableSince=now;
                if(now-stableSince>=200){waitForIdleSync();return;}
            }else stableSince=-1;
            previousY=y[0];previousFooter=new Rect(footer);SystemClock.sleep(50);
        }
        throw new Exception("Scroll did not settle at "+(direction==View.FOCUS_UP?"top":"bottom")
                +" with stable footer; scrollY="+previousY+", footer="+previousFooter+", phase="+phase);
    }
    /** Collect all large-font action clipping evidence, then fail at the end so bounds coverage is retained. */
    private void inspectActionText(String stage) throws Exception {
        ScrollView scroll = (ScrollView)field(activity,"scroll");
        for (String label : new String[]{"Connect Google","Restore from Google Drive"}) {
            TextView[] target = {null}; runOnMainSync(() -> target[0] = findText(activity.getWindow().getDecorView(),label));
            require(target[0] != null,"Missing native backup action " + label);
            TextView button = target[0];
            runOnMainSync(() -> button.requestRectangleOnScreen(new Rect(0,0,button.getWidth(),button.getHeight()),true)); settle();
            Rect visible = bounds(button), complete = fullBounds(button);
            JSONObject[] evidence = {null}; boolean[] clipped = {false};
            onMain(() -> {
                android.text.Layout layout = button.getLayout();
                int available = button.getHeight()-button.getCompoundPaddingTop()-button.getCompoundPaddingBottom();
                int lines = layout==null ? 0 : layout.getLineCount();
                int bottom = lines==0 ? 0 : layout.getLineBottom(lines-1);
                int end = lines==0 ? 0 : layout.getLineEnd(lines-1);
                int ellipsis = 0; for(int i=0;i<lines;i++)ellipsis += layout.getEllipsisCount(i);
                clipped[0] = !button.isEnabled() || visible.isEmpty() || !visible.equals(complete) || lines==0
                        || bottom>available || end<button.getText().length() || ellipsis>0;
                evidence[0] = new JSONObject().put("stage",stage).put("label",label)
                        .put("orientation",activity.getResources().getConfiguration().orientation)
                        .put("fontScale",activity.getResources().getConfiguration().fontScale)
                        .put("visible",visible.toShortString()).put("full",complete.toShortString())
                        .put("lineCount",lines).put("lineBottomPx",bottom).put("availableTextHeightPx",available)
                        .put("lastLineEnd",end).put("textLength",button.getText().length()).put("ellipsisCount",ellipsis)
                        .put("clipped",clipped[0]);
            });
            Bundle status=new Bundle();status.putString("action_text",evidence[0].toString());sendStatus(0,status);
            if(clipped[0]) { actionTextIssues.put(evidence[0]); capture(stage+"-clipped-"+label.replace(' ','-')); }
        }
        runOnMainSync(() -> scroll.scrollTo(0,0)); settle();
    }
    private TextView findText(View view,String label) {
        if(view instanceof TextView && label.equals(((TextView)view).getText().toString()))return (TextView)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=findText(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private void assertDetails(String expected, boolean useBack) throws Exception {
        AlertDialog prior = (AlertDialog)field(activity,"activeDialog");
        require(prior == null || !prior.isShowing(), "Previous dialog was not dismissed before fresh first-tap assertion");
        int tapsBefore = physicalTaps;
        singleTap((View)field(activity,"feedbackDetails"));
        final AlertDialog[] dialog = {null};
        waitUntil(() -> { dialog[0] = (AlertDialog)field(activity,"activeDialog"); return dialog[0] != null && dialog[0].isShowing(); },
                "Exactly ONE first Details tap failed to show the actual native dialog (no second-tap retry)");
        require(physicalTaps == tapsBefore + 1 && dialog[0] != prior, "Details was not opened by exactly one fresh physical tap");
        View[] message = {null}; runOnMainSync(() -> message[0] = dialog[0].findViewById(android.R.id.message));
        require(message[0] instanceof TextView && expected.equals(((TextView)message[0]).getText().toString()),
                "Actual Details dialog omitted/truncated the full status text");
        if (useBack) physicalBack(); else singleTap(dialog[0].getButton(AlertDialog.BUTTON_POSITIVE));
        waitUntil(() -> !dialog[0].isShowing(), "Native dialog did not dismiss"); settle();
    }
    private void busyOrientationReturn() throws Exception {
        progress("Synthetic pending work across orientation interruption and return");
        work = new ControlledWork();
        Class<?> contract = Class.forName("app.voiceflow.BackupActivity$Work");
        Object proxy = Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},work);
        onMain(() -> invoke(activity,"perform",new Class<?>[]{String.class,contract},"Saving backup…",proxy));
        require(work.started.await(5,TimeUnit.SECONDS), "Synthetic work did not start");
        require(busy(), "Pending local work did not enter actual dispatcher busy state");
        BackupActivity owner = activity; long ticket = (Long)field(activity,"operation");
        int current = activity.getResources().getConfiguration().orientation;
        int other = current == Configuration.ORIENTATION_PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        orient(other); verifyConfiguration(); assertBounds("busy-rotated", false); capture("busy-rotated");
        require(activity == owner && busy() && ((Long)field(activity,"operation")) == ticket && work.calls.get() == 1,
                "Orientation interruption duplicated or lost operation ownership");
        orient(current == Configuration.ORIENTATION_PORTRAIT ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        verifyConfiguration(); assertBounds("busy-returned", false);
        work.release.countDown();
        waitUntil(() -> !busy() && SUCCESS.equals(((TextView)field(activity,"feedbackDetail")).getText().toString()), "Synthetic result did not complete");
        assertBounds("completed-after-return", true); assertDetails(SUCCESS, true);
        capture("completed-after-return");
    }
    private static final class ControlledWork implements InvocationHandler {
        final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("toString")) return "Synthetic backup bounds worker";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            calls.incrementAndGet(); started.countDown(); release.await(); return SUCCESS;
        }
    }
    /** Deliberately no performClick, requestFocus, fallback tap, or scroll-to-target for pinned controls. */
    private void singleTap(View control) throws Exception {
        require(control != null && control.isEnabled(), "Missing/disabled physical tap target in " + phase);
        settle(); Rect area = bounds(control);
        require(!area.isEmpty() && fullBounds(control).equals(area), "Physical tap target is clipped/invisible: " + area);
        long down = SystemClock.uptimeMillis(); motion(MotionEvent.ACTION_DOWN,down,area.centerX(),area.centerY()); SystemClock.sleep(75);
        motion(MotionEvent.ACTION_UP,down,area.centerX(),area.centerY()); physicalTaps++; settle();
    }
    private void motion(int action,long down,float x,float y) throws Exception {
        MotionEvent event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { require(automation.injectInputEvent(event,true), "Android rejected physical touch event"); } finally { event.recycle(); }
    }
    private void physicalBack() throws Exception {
        long now = SystemClock.uptimeMillis();
        require(automation.injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true),"Android rejected Back down");
        require(automation.injectInputEvent(new KeyEvent(now,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true),"Android rejected Back up");
        physicalBacks++; settle();
    }
    private void settle() { waitForIdleSync(); SystemClock.sleep(180); waitForIdleSync(); }
    // Local visible rect includes the view's own scroll offset; screen location does not.
    private Rect bounds(View view) { Rect result = new Rect(); runOnMainSync(() -> { if (!view.isShown() || !view.getLocalVisibleRect(result)) result.setEmpty(); else { int[] xy=new int[2];view.getLocationOnScreen(xy);result.offset(xy[0]-view.getScrollX(),xy[1]-view.getScrollY()); } }); return result; }
    private Rect fullBounds(View view) { Rect result = new Rect(); runOnMainSync(() -> { int[] xy = new int[2]; view.getLocationOnScreen(xy); result.set(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight()); }); return result; }
    private boolean hasScrollAncestor(View view) { android.view.ViewParent parent = view.getParent(); while (parent != null) { if (parent instanceof ScrollView) return true; parent = parent.getParent(); } return false; }
    private boolean busy() throws Exception { return (Boolean)field(activity,"busy"); }
    private View description(String label) { View[] found = {null}; runOnMainSync(() -> found[0] = findDescription(activity.getWindow().getDecorView(),label)); return found[0]; }
    private View findDescription(View view,String label) { if (label.equals(String.valueOf(view.getContentDescription()))) return view; if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup)view; for (int i=0;i<group.getChildCount();i++) { View match=findDescription(group.getChildAt(i),label); if(match!=null)return match; } } return null; }
    private Object field(Object target,String name) throws Exception { Field value=target.getClass().getDeclaredField(name);value.setAccessible(true);return value.get(target); }
    private Object invoke(Object target,String name,Class<?>[] types,Object... args) throws Exception { Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args); }
    private interface MainWork { void run() throws Exception; }
    private void onMain(MainWork action) throws Exception { Exception[] failure={null};runOnMainSync(()->{try{action.run();}catch(Exception e){failure[0]=e;}});if(failure[0]!=null)throw failure[0]; }
    private interface Condition { boolean check() throws Exception; }
    private void waitUntil(Condition condition,String error) throws Exception { long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(condition.check()){waitForIdleSync();return;}SystemClock.sleep(50);}throw new Exception(error); }
    private File mediaDirectory() throws IOException { File[] dirs=context.getExternalMediaDirs();if(dirs==null||dirs.length==0||dirs[0]==null)throw new IOException("Screenshot directory unavailable");if(!dirs[0].isDirectory()&&!dirs[0].mkdirs())throw new IOException("Could not create screenshot directory");return dirs[0]; }
    private void capture(String stage) throws Exception { Bitmap bitmap=automation.takeScreenshot();require(bitmap!=null,"Native screenshot unavailable");File path=new File(mediaDirectory(),"backup-bounds-1910-"+caseName+"-"+stage+"-synthetic.png");try(FileOutputStream output=new FileOutputStream(path)){require(bitmap.compress(Bitmap.CompressFormat.PNG,100,output),"Screenshot save failed");}finally{bitmap.recycle();} }
    private void progress(String value) { phase=value;Bundle status=new Bundle();status.putString("phase",value);sendStatus(0,status); }
    private void require(boolean condition,String message) throws Exception { checks++;if(!condition)throw new Exception(message); }
    private void appendFailure(Bundle report,String stage,Throwable failure) { report.putString("failure",report.getString("failure","")+"\n"+stage+" failed: "+failure); }
    @SuppressWarnings("unchecked") private void restorePreferences(SharedPreferences store,Map<String,?> original) throws IOException { SharedPreferences.Editor editor=store.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);}if(!editor.commit())throw new IOException("Could not restore preferences"); }
}
