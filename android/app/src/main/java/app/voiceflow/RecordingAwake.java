package app.voiceflow;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/** Keeps an active dictation awake across app/IME changes, without waking a locked screen. */
@SuppressWarnings("deprecation")
final class RecordingAwake implements AutoCloseable {
    private static final String TAG="RecordingAwake";
    private final Context app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final PowerManager power;
    private final KeyguardManager keyguard;
    private final RecordingAwakePolicy policy;
    private PowerManager.WakeLock cpu,display;
    private boolean started,closed,registered,warned;

    RecordingAwake(Context context){
        // A capture can outlive its original Activity or IME window.
        app=context==null?null:context.getApplicationContext();
        PowerManager foundPower=null;KeyguardManager foundKeyguard=null;
        try{
            if(app!=null){
                foundPower=app.getSystemService(PowerManager.class);
                foundKeyguard=app.getSystemService(KeyguardManager.class);
            }
        }catch(RuntimeException failure){warn(failure);}
        power=foundPower;keyguard=foundKeyguard;
        policy=new RecordingAwakePolicy(SystemClock::elapsedRealtime,new RecordingAwakePolicy.Port(){
            public void acquireCpu(long timeoutMs){
                try{
                    if(power==null)return;
                    if(cpu==null){cpu=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"VonoTation:dictation-cpu");cpu.setReferenceCounted(false);}
                    cpu.acquire(timeoutMs);
                }catch(RuntimeException failure){warn(failure);}
            }
            public void acquireDisplay(long timeoutMs){
                try{
                    if(power==null)return;
                    if(display==null){display=power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"VonoTation:dictation-display");display.setReferenceCounted(false);}
                    // No ACQUIRE_CAUSES_WAKEUP: deliberate screen-off remains under user control.
                    display.acquire(timeoutMs);
                }catch(RuntimeException failure){warn(failure);}
            }
            public void releaseCpu(){release(cpu);}
            public void releaseDisplay(){release(display);}
        });
    }

    synchronized void start(){
        if(started||closed)return;
        started=true;
        if(power==null)warn(new IllegalStateException("Power service unavailable"));
        try{
            if(app!=null){
                IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);
                filter.addAction(Intent.ACTION_USER_PRESENT);
                if(Build.VERSION.SDK_INT>=33)app.registerReceiver(receiver,filter,null,main,Context.RECEIVER_NOT_EXPORTED);
                else app.registerReceiver(receiver,filter,null,main);
                registered=true;
            }
        }catch(RuntimeException failure){warn(failure);}
        policy.start(interactive(),unlocked());
        main.postDelayed(heartbeat,RecordingAwakePolicy.RENEW_MS);
    }

    private final Runnable heartbeat=new Runnable(){public void run(){synchronized(RecordingAwake.this){
        if(!started||closed)return;
        policy.refresh(interactive(),unlocked());
        main.postDelayed(this,RecordingAwakePolicy.RENEW_MS);
    }}};

    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){synchronized(RecordingAwake.this){
            if(!started||closed||intent==null)return;
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))policy.screenOff();
            else if(Intent.ACTION_USER_PRESENT.equals(intent.getAction())||Intent.ACTION_SCREEN_ON.equals(intent.getAction()))
                policy.userPresent(interactive(),unlocked());
        }}
    };

    private boolean interactive(){
        try{return power!=null&&power.isInteractive();}catch(RuntimeException failure){warn(failure);return false;}
    }
    private boolean unlocked(){
        try{return keyguard!=null&&!keyguard.isKeyguardLocked();}catch(RuntimeException failure){warn(failure);return false;}
    }
    private void release(PowerManager.WakeLock lock){
        try{if(lock!=null&&lock.isHeld())lock.release();}catch(RuntimeException failure){warn(failure);}
    }
    private void warn(RuntimeException failure){
        if(warned)return;
        warned=true;
        Log.w(TAG,"Could not keep dictation awake",failure);
    }

    @Override public synchronized void close(){
        if(closed)return;
        closed=true;started=false;
        main.removeCallbacks(heartbeat);
        policy.close();
        if(registered){
            registered=false;
            try{app.unregisterReceiver(receiver);}catch(RuntimeException failure){warn(failure);}
        }
    }
}
