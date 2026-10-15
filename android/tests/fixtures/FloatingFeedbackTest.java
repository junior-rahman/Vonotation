package app.voiceflow;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.View;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicInteger;

/** Native component checks. SidePreview separately verifies the actual overlay/IME gestures. */
public final class FloatingFeedbackTest extends Instrumentation {
    private Bundle arguments;
    private int checks;
    private FloatingIcon.Control control;
    private boolean animations;
    private Context context;
    private volatile MainActivity main;
    private final AtomicInteger externalRequests=new AtomicInteger();
    private String requested;
    private float systemAnimatorScale;
    private boolean windowInitialized;
    @Override public void onCreate(Bundle args){arguments=args;super.onCreate(args);start();}
    @Override public void callActivityOnCreate(Activity activity,Bundle state){if(activity instanceof MainActivity)main=(MainActivity)activity;super.callActivityOnCreate(activity,state);}
    @Override public void onStart(){
        Bundle report=new Bundle();context=getTargetContext();SharedPreferences prefs=Store.prefs(context);
        SharedPreferences activityPrefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE);
        Map<String,?> originalPrefs=new HashMap<>(prefs.getAll()),originalActivityPrefs=new HashMap<>(activityPrefs.getAll());
        Map<String,?> originalSecrets=BackupSecrets.snapshot(context);
        String originalDraft=MainActivity.currentDraft;boolean seeded=false;
        try{
            requested=arguments==null?"":arguments.getString("motion","");
            require(requested.equals("enabled") || requested.equals("disabled"),"Run with -e motion enabled or disabled after configuring the disposable emulator's animator scale");
            validateSystemAnimatorScale();
            URL.setURLStreamHandlerFactory(protocol->protocol.equals("http")||protocol.equals("https")?new URLStreamHandler(){
                @Override protected URLConnection openConnection(URL address)throws IOException{
                    externalRequests.incrementAndGet();throw new IOException("Synthetic floating fixture blocks HTTP/HTTPS");
                }
            }:null);
            seeded=true;
            require(prefs.edit().clear().putString("history","[]").putString("engine","offline")
                    .putBoolean("daily_summaries",false).putBoolean("bubble",false)
                    .putBoolean("haptic_feedback",false).putString("backup_frequency","off").commit(),"Could not seed isolated floating fixture state");
            DraftState.set("Synthetic floating feedback fixture draft");
            setInTouchMode(true);
            main=(MainActivity)startActivitySync(new Intent(context,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            waitForIdleSync();awaitAnimatorWindow();
            onMain(()->{
                animations=ValueAnimator.areAnimatorsEnabled();
                require(animations==requested.equals("enabled"),"Actual Android animator setting differs from the requested test mode");
                paletteChecks(false);paletteChecks(true);hapticChecks(prefs);
                control=new FloatingIcon.Control(context,"micKeyboardHidden",Daybook.coral(context),Ui.dp(context,34));
                control.layout(0,0,Ui.dp(context,56),Ui.dp(context,56));
                require(android.widget.Button.class.getName().contentEquals(control.getAccessibilityClassName()),"Floating control does not expose a native button role");
                control.reveal();stableTarget();
                if(animations)require(artwork().getScaleX()<1 && artwork().getAlpha()<1,"Enabled state transition did not begin");
                else stableArtwork();
            });
            Thread.sleep(400);
            onMain(()->{stableArtwork();control.setPressed(true);stableTarget();if(!animations)stableArtwork();});
            Thread.sleep(220);
            onMain(()->{if(animations)require(artwork().getScaleX()<.96f,"Enabled press feedback did not contract artwork");else stableArtwork();control.setPressed(false);stableTarget();});
            Thread.sleep(350);
            onMain(()->{
                stableArtwork();
                // Interrupted press/release/reveal should not leave a shrunken or faded glyph.
                for(int i=0;i<6;i++){control.setPressed(true);control.setPressed(false);control.reveal();}
                control.setPressed(false);stableTarget();if(!animations)stableArtwork();
            });
            Thread.sleep(350);
            onMain(()->{stableArtwork();stableTarget();});
            validateSystemAnimatorScale();
            onMain(()->require(ValueAnimator.areAnimatorsEnabled()==requested.equals("enabled"),"Android animator mode changed during the component checks"));
            require(externalRequests.get()==0,"Floating fixture attempted HTTP/HTTPS");
            require("off".equals(Store.get(context,"backup_frequency",""))
                    && ((JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE)).getPendingJob(DriveBackup.JOB_ID)==null,"Floating fixture enabled automatic backup");
            report.putString("result","PASS: native floating components use warm light/dark Daybook surfaces, coral flow artwork and distinct keyboard badges; optional haptics dispatch exactly once through the Android API when enabled and not at all when disabled; "+(animations?"enabled press/state animations settle after repeated interruption":"disabled Android animations keep artwork at its final scale/alpha")+" with stable zero-translation touch targets. This is component/API coverage, not a physical vibration, actual overlay gesture, or speech-insertion test.");
        }catch(Throwable failure){report.putString("failure",android.util.Log.getStackTraceString(failure));report.putInt("executed_checks",checks);}
        finally{
            try{
                onMain(()->{if(control!=null){control.animate().cancel();artwork().animate().cancel();}if(main!=null&&!main.isDestroyed())main.finish();});
                awaitActivityDestroyed();waitForIdleSync();
            }catch(Throwable cleanup){report.putString("failure",report.getString("failure","")+"\nActivity/component cleanup failed: "+cleanup);}
            if(seeded)try{
                restorePreferences(prefs,originalPrefs);restorePreferences(activityPrefs,originalActivityPrefs);
                restorePreferences(context.getSharedPreferences("voiceflow_backup_secrets",Context.MODE_PRIVATE),originalSecrets);
                DraftState.set(originalDraft);DriveBackup.schedule(context);
                require(originalPrefs.equals(prefs.getAll()) && originalActivityPrefs.equals(activityPrefs.getAll())
                        && originalSecrets.equals(BackupSecrets.snapshot(context)) && java.util.Objects.equals(originalDraft,MainActivity.currentDraft),"Original fixture preferences/draft did not restore exactly");
            }catch(Throwable cleanup){report.putString("failure",report.getString("failure","")+"\nLocal preference/draft restoration failed: "+cleanup);}
        }
        report.putInt("executed_checks",checks);report.putInt("http_https_attempts",externalRequests.get());
        report.putFloat("system_animator_scale",systemAnimatorScale);report.putBoolean("public_animators_enabled",animations);
        report.putBoolean("real_window_initialized",windowInitialized);
        finish(report.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,report);
    }
    private void validateSystemAnimatorScale()throws Settings.SettingNotFoundException{
        systemAnimatorScale=Settings.Global.getFloat(context.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE);
        require(!Float.isNaN(systemAnimatorScale) && !Float.isInfinite(systemAnimatorScale) && systemAnimatorScale>=0
                && (systemAnimatorScale>0)==requested.equals("enabled"),"System animator_duration_scale="+systemAnimatorScale+" differs from requested "+requested+" mode");
    }
    /** A real window initializes the process's framework animator scale; this fixture never sets it. */
    private void awaitAnimatorWindow(){
        long until=SystemClock.elapsedRealtime()+15000;boolean[] ready={false};
        while(SystemClock.elapsedRealtime()<until){
            onMain(()->{
                View decor=main==null?null:main.getWindow().getDecorView();
                ready[0]=main!=null&&!main.isDestroyed()&&main.hasWindowFocus()&&decor.isAttachedToWindow()
                        &&decor.isLaidOut()&&!decor.isLayoutRequested();
                animations=ValueAnimator.areAnimatorsEnabled();
            });
            if(ready[0] && animations==requested.equals("enabled")){windowInitialized=true;return;}
            SystemClock.sleep(50);
        }
        require(false,"Real UI window/public animator mode did not initialize within 15 seconds: windowReady="+ready[0]
                +", systemScale="+systemAnimatorScale+", publicAnimatorsEnabled="+animations+", requested="+requested);
    }
    private void awaitActivityDestroyed(){
        long until=SystemClock.elapsedRealtime()+10000;boolean[] gone={false};
        while(SystemClock.elapsedRealtime()<until){
            onMain(()->gone[0]=main==null||main.isDestroyed());if(gone[0])return;SystemClock.sleep(50);
        }
        require(false,"Synthetic MainActivity did not finish within 10 seconds");
    }
    @SuppressWarnings("unchecked") private void restorePreferences(SharedPreferences prefs,Map<String,?> original){
        SharedPreferences.Editor edit=prefs.edit().clear();
        for(Map.Entry<String,?> item:original.entrySet()){
            String key=item.getKey();Object value=item.getValue();
            if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));
            else throw new AssertionError("Unsupported preference type during cleanup");
        }
        require(edit.commit(),"Could not restore original preferences");
    }
    private void paletteChecks(boolean dark){
        Configuration configuration=new Configuration(context.getResources().getConfiguration());
        configuration.uiMode=(configuration.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
        Context themed=context.createConfigurationContext(configuration);
        Bitmap idle=surface(themed,Glass.DAYBOOK),active=surface(themed,Glass.DAYBOOK_ACTION);
        int warm=idle.getPixel(60,60),action=active.getPixel(60,60);
        require(Color.red(warm)>Color.green(warm) && Color.green(warm)>Color.blue(warm),"Floating surface is not warm in "+(dark?"dark":"light")+" theme");
        require(Color.alpha(warm)>240,"Floating surface does not provide a legible base over another app");
        require(warm!=action,"Recording Finish lost its distinct action surface");
        int[] hidden=icon(themed,"micKeyboardHidden"),visible=icon(themed,"micKeyboardVisible");
        require(!Arrays.equals(hidden,visible),"Keyboard visible/hidden badges render identically");
        require(count(hidden,Daybook.coral(themed))>30,"Flow artwork does not use the Daybook coral color");
        require(count(hidden,Daybook.accent(themed))>10,"Keyboard badge is not legible in the accent color");
        idle.recycle();active.recycle();
    }
    private Bitmap surface(Context themed,int tone){Bitmap bitmap=Bitmap.createBitmap(120,120,Bitmap.Config.ARGB_8888);Drawable surface=Glass.surface(themed,1000,tone);surface.setBounds(0,0,120,120);surface.draw(new Canvas(bitmap));return bitmap;}
    private int[] icon(Context themed,String kind){FloatingIcon icon=new FloatingIcon(themed,kind,Daybook.coral(themed));icon.layout(0,0,144,144);Bitmap bitmap=Bitmap.createBitmap(144,144,Bitmap.Config.ARGB_8888);icon.draw(new Canvas(bitmap));int[] pixels=new int[144*144];bitmap.getPixels(pixels,0,144,0,0,144,144);bitmap.recycle();return pixels;}
    private int count(int[] pixels,int color){int count=0;for(int pixel:pixels)if(pixel==color)count++;return count;}
    private void hapticChecks(SharedPreferences prefs){
        HapticProbe probe=new HapticProbe(context);probe.setHapticFeedbackEnabled(false);
        require(prefs.edit().putBoolean("haptic_feedback",true).commit(),"Could not enable synthetic haptic preference");Daybook.feedback(probe);
        require(probe.calls==1 && probe.last==HapticFeedbackConstants.CONTEXT_CLICK && probe.isHapticFeedbackEnabled(),"Enabled tap did not dispatch exactly one native context-click haptic");
        require(prefs.edit().putBoolean("haptic_feedback",false).commit(),"Could not disable synthetic haptic preference");Daybook.feedback(probe);
        require(probe.calls==1 && !probe.isHapticFeedbackEnabled(),"Disabled preference still dispatched a haptic");
        require(prefs.edit().remove("haptic_feedback").commit(),"Could not test default haptic preference");Daybook.feedback(probe);
        require(probe.calls==2 && probe.isHapticFeedbackEnabled(),"Default haptic preference did not re-enable the native call");
    }
    private void onMain(Runnable work){
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(()->{try{work.run();}catch(Throwable problem){failure.set(problem);}});
        if(failure.get()!=null)throw new AssertionError("Native component assertion failed",failure.get());
    }
    private View artwork(){return control.getChildAt(0);}
    private void stableTarget(){require(near(control.getScaleX(),1)&&near(control.getScaleY(),1)&&near(control.getAlpha(),1)&&near(control.getTranslationX(),0)&&near(control.getTranslationY(),0),"Feedback moved or scaled the touch target");}
    private void stableArtwork(){View icon=artwork();require(near(icon.getScaleX(),1)&&near(icon.getScaleY(),1)&&near(icon.getAlpha(),1)&&near(icon.getTranslationX(),0)&&near(icon.getTranslationY(),0),"Artwork did not settle to stable scale/alpha/zero translation");}
    private boolean near(float actual,float expected){return Math.abs(actual-expected)<.001f;}
    private void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static final class HapticProbe extends View {
        int calls,last;
        HapticProbe(Context context){super(context);}
        @Override public boolean performHapticFeedback(int constant){calls++;last=constant;return true;}
    }
}
