package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Production UI/dispatcher with controlled SYNTHETIC work, never a Google/Drive success claim. */
public final class BackupFeedbackTest extends Instrumentation {
    private static final String GOOGLE_UNAVAILABLE="Google Play services must be available and up to date to connect Google Drive.";
    private static final String SUCCESS="Synthetic QA: controlled worker finished. No Google account or upload was used.";
    private static final String FAILURE="Synthetic QA: connection unavailable.\nPlease try again later.";
    private volatile BackupActivity activity;
    private Context context;
    private UiAutomation automation;
    private SharedPreferences prefs;
    private final AtomicInteger externalRequests=new AtomicInteger();
    private final List<ControlledWork> workers=new ArrayList<>();
    private int checks,physicalTaps;
    private String phase="starting",googleStage="Not exercised";

    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity target,Bundle state){
        if(target instanceof BackupActivity)activity=(BackupActivity)target;
        super.callActivityOnCreate(target,state);
    }
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();prefs=Store.prefs(context);
        Map<String,?> originalPrefs=prefs.getAll(),originalSecrets=BackupSecrets.snapshot(context);
        try{
            URL.setURLStreamHandlerFactory(protocol -> protocol.equals("http") || protocol.equals("https") ? new URLStreamHandler(){
                @Override protected URLConnection openConnection(URL address)throws IOException{
                    externalRequests.incrementAndGet();throw new IOException("Synthetic backup feedback fixture blocks external requests");
                }
            } : null);
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            setInTouchMode(true);
            DriveBackup.disconnect(context);
            require(prefs.edit().clear().putString("engine","offline").putBoolean("daily_summaries",false)
                    .putString("backup_frequency","off").putBoolean("haptic_feedback",false)
                    .putString("backup_uri","content://app.voiceflow.fixture/synthetic-unused-backup").commit(),"Could not seed isolated synthetic state");
            launch();
            baselineAndFirstTaps();
            controlledCompletion();
            controlledFailure();
            destroyedOwner();
            require(externalRequests.get()==0,"Fixture attempted HTTP/HTTPS");
            require("off".equals(Store.get(context,"backup_frequency","")) && noBackupJob(),"Feedback testing enabled automatic backup");
            report.putString("result","PASS: "+checks+" native assertions and "+physicalTaps+" physical taps. Controlled SYNTHETIC worker validated immediate/pinned progress, indeterminate state, busy rejection, success/error/Details and destroyed-owner rejection. No real account authorization, Google upload, remote restore or physical haptic test. "+googleStage);
            report.putString("capture_directory",mediaDirectory().getAbsolutePath());
        }catch(Throwable failure){
            report.putString("failure",android.util.Log.getStackTraceString(failure));report.putString("phase",phase);
        }finally{
            for(ControlledWork work:workers)work.release.countDown();
            try{
                if(activity!=null && !activity.isDestroyed())runOnMainSync(()->activity.finish());
                waitForIdleSync();
            }catch(Throwable failure){report.putString("failure",report.getString("failure","")+"\nActivity cleanup failed: "+failure);}
            try{
                restorePreferences(context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE),originalSecrets);
                restorePreferences(prefs,originalPrefs);DriveBackup.schedule(context);
                if(!originalPrefs.equals(prefs.getAll()) || !originalSecrets.equals(BackupSecrets.snapshot(context)))throw new IOException("Preference cleanup mismatch");
            }catch(Throwable failure){report.putString("failure",report.getString("failure","")+"\nLocal-data cleanup failed: "+failure);}
        }
        report.putInt("executed_checks",checks);report.putInt("physical_taps",physicalTaps);report.putString("google_stage",googleStage);
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }

    private void launch(){
        activity=(BackupActivity)startActivitySync(new Intent(context,BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
    }
    private void baselineAndFirstTaps()throws Exception{
        progress("Disconnected real Backup UI and single physical first taps on missing-GMS Connect/Restore");
        require(text("Your backup status")!=null,"Persistent feedback title missing");
        require(!enabled(text("Back up now")),"Disconnected screen enables upload");
        Spinner frequency=(Spinner)description("Backup frequency");
        int[] selection={-1};runOnMainSync(()->selection[0]=frequency.getSelectedItemPosition());
        require(selection[0]==0 && !enabled(frequency),"Automatic backup is not disabled and Off");
        require(!touchFocusable(text("Connect Google")) && !touchFocusable(text("Restore from Google Drive")),"Backup action can steal first tap for focus");
        assertPinned();
        Map<String,?> seeded=prefs.getAll();
        if(!packageInstalled("com.google.android.gms")){
            singleTap(text("Connect Google"),true);waitText(GOOGLE_UNAVAILABLE);
            require(text("Google connection needs attention")!=null && !busy(activity),"First Connect tap failed to leave visible terminal feedback");
            require(seeded.equals(prefs.getAll()) && noBackupJob(),"Missing-GMS Connect changed backup state");
            // The same missing-services error must be newly produced by Restore, not left over from Connect.
            BackupActivity connectOwner=activity;
            launch();
            require(activity!=connectOwner && text("Your backup status")!=null
                    && text(GOOGLE_UNAVAILABLE)==null && !busy(activity),"Restore first-tap baseline retained Connect feedback");
            singleTap(text("Restore from Google Drive"),true);waitText(GOOGLE_UNAVAILABLE);
            require(enabled(text("Restore from Google Drive")) && !busy(activity),"First Restore tap left the screen busy");
            require(seeded.equals(prefs.getAll()) && BackupSecrets.snapshot(context).isEmpty(),"Missing-GMS Restore changed local data or password state");
            assertPinned();capture("backup-feedback-1910-google-unavailable-synthetic.png");
            googleStage="Physical missing-GMS Connect/Restore first taps passed; live Google remains blocked by absent Play services.";
        }else googleStage="Google account consent deliberately skipped because this fixture is synthetic.";
    }
    private void controlledCompletion()throws Exception{
        progress("SYNTHETIC controlled worker: immediate busy state, pinned native progress and repeated-tap guard");
        ControlledWork work=new ControlledWork(SUCCESS,null,false);workers.add(work);
        perform(activity,"Saving backup…",work);
        require(work.started.await(5,TimeUnit.SECONDS),"Controlled worker never started");
        require(busy(activity) && text("Saving backup…")!=null && text("In progress")!=null,"Dispatcher did not present progress immediately");
        ProgressBar bar=(ProgressBar)field(activity,"feedbackProgress");
        boolean[] indicator={false},motion={false};runOnMainSync(()->{motion[0]=android.animation.ValueAnimator.areAnimatorsEnabled();indicator[0]=bar.isIndeterminate() && (motion[0]?bar.getVisibility()==View.VISIBLE:bar.getVisibility()==View.GONE);});
        require(indicator[0],"Busy feedback must be indeterminate, or static when Android animations are off");
        require(text("Completed")==null && text(SUCCESS)==null,"UI reported success before worker completion");
        require(!enabled(text("Connect Google")) && !enabled(text("Restore from Google Drive")) && !enabled(description("Backup frequency")),"Busy actions remain enabled");
        long ticket=((Long)field(activity,"operation")).longValue();
        singleTap(text("Restore from Google Drive"),false);
        singleTap(text("Restore from Google Drive"),false);
        ControlledWork duplicate=new ControlledWork("Synthetic QA: must never run",null,false);workers.add(duplicate);
        perform(activity,"Saving backup…",duplicate);
        require(((Long)field(activity,"operation")).longValue()==ticket && work.calls.get()==1 && duplicate.calls.get()==0 && field(activity,"pendingAuth")==null,"Repeated busy taps or dispatch created duplicate work");
        assertPinned();capture("backup-feedback-1910-busy-synthetic.png");
        work.release.countDown();waitText(SUCCESS);
        require(text("Backup saved")!=null && text("Completed")!=null && !busy(activity),"Successful worker did not produce terminal completion feedback");
        require(enabled(text("Connect Google")) && !enabled(text("Back up now")),"Completion failed to restore configuration-based action guards");
        require(visibility(bar)==View.GONE,"Progress bar remained after completion");
        assertPinned();capture("backup-feedback-1910-complete-synthetic.png");
        assertDetails(SUCCESS);
    }
    private void controlledFailure()throws Exception{
        progress("SYNTHETIC controlled failure: visible error, full Details and enabled retry action");
        ControlledWork work=new ControlledWork(null,new IOException(FAILURE),false);workers.add(work);
        perform(activity,"Saving backup…",work);require(work.started.await(5,TimeUnit.SECONDS),"Failure worker never started");
        require(text("Completed")==null && text(SUCCESS)==null,"New operation retained stale completion feedback");
        work.release.countDown();waitText(FAILURE.replace("\n",""));
        require(text("Backup couldn’t finish")!=null && text("Needs attention")!=null && !busy(activity),"Failed worker did not produce clear terminal error");
        require(text("Completed")==null && enabled(text("Restore from Google Drive")),"Failure reports success or blocks future actions");
        assertPinned();capture("backup-feedback-1910-error-synthetic.png");
        assertDetails(FAILURE.replace("\n",""));
    }
    private void destroyedOwner()throws Exception{
        progress("SYNTHETIC delayed result: native Back, owned-password cleanup and stale callback rejection");
        BackupActivity old=activity;
        ControlledWork work=new ControlledWork("Synthetic QA: stale owner must never show this",null,true);workers.add(work);
        perform(old,"Saving backup…",work);require(work.started.await(5,TimeUnit.SECONDS),"Delayed worker never started");
        char[] input="synthetic-queued-password".toCharArray();
        Object[] owned={null};onMain(()->owned[0]=invoke(old,"ownedPassword",new Class<?>[]{char[].class,boolean.class},input,true));
        require(allZero(input),"Consuming worker password did not clear source buffer");
        char[] queued=(char[])field(owned[0],"value");require(!allZero(queued),"Queued worker password was unexpectedly empty before cancellation");
        long ticket=((Long)field(old,"operation")).longValue();
        singleTap(description("Back to settings"),true);
        long until=SystemClock.elapsedRealtime()+10000;
        while(!old.isDestroyed() && SystemClock.elapsedRealtime()<until)Thread.sleep(50);
        require(old.isDestroyed(),"Native Back did not destroy the busy owner");
        require(allZero(queued),"Destroyed owner did not clear queued password");
        require(((Long)field(old,"operation")).longValue()>ticket && field(old,"pendingAuth")==null,"Destroyed owner retained operation/auth ownership");
        work.release.countDown();
        require(((ExecutorService)field(old,"worker")).awaitTermination(5,TimeUnit.SECONDS),"Controlled worker did not terminate after release");
        waitForIdleSync();
        require("".equals(field(old,"notice")),"Late worker changed destroyed owner's terminal notice");
        launch();
        require(text("Your backup status")!=null && text("Synthetic QA: stale owner must never show this")==null && !busy(activity),"New Activity inherited stale work/result state");
    }

    /** Only this fixture uses reflection; production has no injected success/test path. */
    private final class ControlledWork implements InvocationHandler {
        final String result;final Exception failure;final boolean ignoreInterrupt;
        final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        ControlledWork(String result,Exception failure,boolean ignoreInterrupt){this.result=result;this.failure=failure;this.ignoreInterrupt=ignoreInterrupt;}
        @Override public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
            if(method.getName().equals("toString"))return "Synthetic controlled backup work";
            if(method.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(method.getName().equals("equals"))return proxy==args[0];
            calls.incrementAndGet();started.countDown();
            while(true){try{release.await();break;}catch(InterruptedException interrupted){if(!ignoreInterrupt)throw new IOException("Synthetic QA: interrupted");}}
            if(failure!=null)throw failure;return result;
        }
    }
    private void perform(BackupActivity target,String stage,ControlledWork work)throws Exception{
        Class<?> contract=Class.forName("app.voiceflow.BackupActivity$Work");
        Object proxy=Proxy.newProxyInstance(contract.getClassLoader(),new Class<?>[]{contract},work);
        onMain(()->invoke(target,"perform",new Class<?>[]{String.class,contract},stage,proxy));
        // This assertion runs before waiting for the worker: begin must update native UI synchronously.
        boolean[] immediate={false};onMain(()->immediate[0]=busy(target) && stage.equals(((TextView)field(target,"feedbackTitle")).getText().toString()));
        require(immediate[0],"begin did not synchronously show its actual operation stage");
    }
    private void assertPinned()throws Exception{
        View panel=(View)field(activity,"feedbackPanel");ScrollView scroll=(ScrollView)field(activity,"scroll");
        require(panel.getParent()!=scroll && !hasAncestor(panel,ScrollView.class),"Feedback was placed inside the scrolling settings");
        scrollToEdge(scroll,View.FOCUS_UP);Rect top=bounds(panel);
        scrollToEdge(scroll,View.FOCUS_DOWN);Rect bottom=bounds(panel);
        require(top.equals(bottom) && !bottom.isEmpty(),"Backup feedback moves or disappears when settings scroll");
        Rect viewport=bounds(scroll);require(viewport.height()>0 && bottom.top>=viewport.bottom,"Pinned feedback overlaps or eliminates the settings viewport");
        require(panel.getAccessibilityLiveRegion()==View.ACCESSIBILITY_LIVE_REGION_POLITE,"Feedback does not announce operation/result changes accessibly");
    }
    /** Wait for the requested scroll endpoint and a stable footer, not just an idle main thread. */
    private void scrollToEdge(ScrollView scroll,int direction)throws Exception{
        View panel=(View)field(activity,"feedbackPanel");
        runOnMainSync(()->scroll.fullScroll(direction));
        long until=SystemClock.elapsedRealtime()+10000,stableSince=-1;
        int previousY=Integer.MIN_VALUE;Rect previousFooter=null;
        while(SystemClock.elapsedRealtime()<until){
            int[] y={0};boolean[] ready={false};Rect footer=new Rect();
            runOnMainSync(()->{
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
    private void assertDetails(String expected)throws Exception{
        singleTap(description("View backup status details"),true);
        AlertDialog dialog=(AlertDialog)field(activity,"activeDialog");
        boolean[] shown={false};runOnMainSync(()->shown[0]=dialog!=null && dialog.isShowing() && find(dialog.getWindow().getDecorView(),expected,null)!=null);
        require(shown[0],"ONE first Details tap did not show the complete result/error");
        singleTap(dialog.getButton(AlertDialog.BUTTON_POSITIVE),true);waitForIdleSync();
        require(!dialog.isShowing(),"Details OK did not close its native dialog");
    }
    /** Exactly one physical touchscreen DOWN/UP. Disabled taps deliberately exercise busy guards. */
    private void singleTap(View control,boolean mustBeEnabled)throws Exception{
        require(control!=null,"Missing physical tap target in "+phase);
        runOnMainSync(()->control.requestRectangleOnScreen(new Rect(0,0,control.getWidth(),control.getHeight()),true));waitForIdleSync();
        Rect area=bounds(control);require(!area.isEmpty() && (!mustBeEnabled || enabled(control)),"Native target is not visibly tappable");
        long down=SystemClock.uptimeMillis();motion(MotionEvent.ACTION_DOWN,down,area.centerX(),area.centerY());SystemClock.sleep(75);
        motion(MotionEvent.ACTION_UP,down,area.centerX(),area.centerY());physicalTaps++;waitForIdleSync();
    }
    private void motion(int action,long down,float x,float y)throws Exception{
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try{require(automation.injectInputEvent(event,true),"Android rejected physical touch event");}finally{event.recycle();}
    }
    // Local visible rect includes the view's own scroll offset; screen location does not.
    private Rect bounds(View view){Rect result=new Rect();runOnMainSync(()->{
        if(!view.isShown() || !view.getLocalVisibleRect(result))result.setEmpty();
        else{int[] xy=new int[2];view.getLocationOnScreen(xy);result.offset(xy[0]-view.getScrollX(),xy[1]-view.getScrollY());}
    });return result;}
    private boolean enabled(View view){if(view==null)return false;boolean[] value={false};runOnMainSync(()->value[0]=view.isEnabled());return value[0];}
    private boolean touchFocusable(View view){boolean[] value={true};runOnMainSync(()->value[0]=view.isFocusableInTouchMode());return value[0];}
    private int visibility(View view){int[] value={-1};runOnMainSync(()->value[0]=view.getVisibility());return value[0];}
    private boolean hasAncestor(View view,Class<?> type){android.view.ViewParent parent=view.getParent();while(parent!=null){if(type.isInstance(parent))return true;parent=parent.getParent();}return false;}
    private View text(String label){return findCurrent(label,null);}
    private View description(String label){return findCurrent(null,label);}
    private View findCurrent(String label,String description){View[] result={null};runOnMainSync(()->result[0]=find(activity.getWindow().getDecorView(),label,description));return result[0];}
    private View find(View view,String label,String description){
        if(description!=null && description.equals(String.valueOf(view.getContentDescription())))return view;
        if(label!=null && view instanceof TextView && label.equals(((TextView)view).getText().toString()))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++){View match=find(group.getChildAt(index),label,description);if(match!=null)return match;}}return null;
    }
    private void waitText(String label)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(text(label)!=null){waitForIdleSync();return;}Thread.sleep(50);}throw new Exception("Expected native feedback missing: "+label);}
    private boolean packageInstalled(String name){try{context.getPackageManager().getPackageInfo(name,0);return true;}catch(PackageManager.NameNotFoundException absent){return false;}}
    private boolean noBackupJob(){return ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID)==null;}
    private boolean busy(BackupActivity target)throws Exception{return ((Boolean)field(target,"busy")).booleanValue();}
    private boolean allZero(char[] value){for(char letter:value)if(letter!='\0')return false;return true;}
    private Object field(Object target,String name)throws Exception{Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private Object invoke(Object target,String name,Class<?>[] types,Object... args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    private interface MainWork{void run()throws Exception;}
    private void onMain(MainWork work)throws Exception{Exception[] problem={null};runOnMainSync(()->{try{work.run();}catch(Exception failure){problem[0]=failure;}});if(problem[0]!=null)throw problem[0];}
    private File mediaDirectory()throws IOException{File[] locations=context.getExternalMediaDirs();if(locations==null || locations.length==0 || locations[0]==null)throw new IOException("Screenshot media directory unavailable");File directory=locations[0];if(!directory.isDirectory() && !directory.mkdirs())throw new IOException("Could not create screenshot directory");return directory;}
    private void capture(String name)throws Exception{waitForIdleSync();Bitmap screenshot=automation.takeScreenshot();require(screenshot!=null,"Native screenshot unavailable");File file=new File(mediaDirectory(),name);try(FileOutputStream output=new FileOutputStream(file)){require(screenshot.compress(Bitmap.CompressFormat.PNG,100,output),"Screenshot export failed");}finally{screenshot.recycle();}require(file.length()>0,"Screenshot was empty");}
    private void progress(String value){phase=value;Bundle status=new Bundle();status.putString("phase",value);sendStatus(0,status);}
    private void require(boolean okay,String message)throws Exception{checks++;if(!okay)throw new Exception(message);}
    @SuppressWarnings("unchecked") private void restorePreferences(SharedPreferences store,Map<String,?> original)throws IOException{
        SharedPreferences.Editor editor=store.edit().clear();for(Map.Entry<String,?> entry:original.entrySet()){String key=entry.getKey();Object value=entry.getValue();if(value instanceof String)editor.putString(key,(String)value);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);else if(value instanceof Set)editor.putStringSet(key,(Set<String>)value);}if(!editor.commit())throw new IOException("Could not restore fixture preferences");
    }
}
