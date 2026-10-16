package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;

/** Native resource contract; compile alone is not a claim that screen-timeout behavior was run. */
public final class RecordingAwakeNativeTest extends Instrumentation {
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){
        Bundle result=new Bundle();final Throwable[] failure={null};
        runOnMainSync(()->{
            RecordingAwake awake=null;
            try{
                Context app=getTargetContext().getApplicationContext();
                Context owner=new ContextWrapper(app);
                awake=new RecordingAwake(owner);
                require(field(awake,"app")==app,"The recording helper retained its caller instead of the application context");
                awake.start();
                PowerManager.WakeLock cpu=(PowerManager.WakeLock)field(awake,"cpu");
                require(cpu!=null&&cpu.isHeld(),"An active capture did not acquire its CPU lease");
                PowerManager power=app.getSystemService(PowerManager.class);
                KeyguardManager keyguard=app.getSystemService(KeyguardManager.class);
                boolean unlocked=power.isInteractive()&&!keyguard.isKeyguardLocked();
                PowerManager.WakeLock display=(PowerManager.WakeLock)field(awake,"display");
                require(!unlocked||(display!=null&&display.isHeld()),"An interactive unlocked capture did not protect display timeout");
                BroadcastReceiver receiver=(BroadcastReceiver)field(awake,"receiver");
                // Invoke the actual callback without trying to send a protected system broadcast.
                receiver.onReceive(app,new Intent(Intent.ACTION_SCREEN_OFF));
                require(display==null||!display.isHeld(),"Screen-off did not release the display resource");
                require(cpu.isHeld(),"Screen-off prematurely released the recording CPU resource");
                awake.close();awake.close();
                require(!cpu.isHeld()&&(display==null||!display.isHeld()),"Close did not release both native resources");
                require(!((Boolean)field(awake,"registered")),"Close retained its dynamic receiver");
                receiver.onReceive(app,new Intent(Intent.ACTION_USER_PRESENT));
                ((Runnable)field(awake,"heartbeat")).run();
                awake.start();
                require(!cpu.isHeld()&&(display==null||!display.isHeld()),"A late callback reacquired resources after close");
            }catch(Throwable problem){failure[0]=problem;}
            finally{if(awake!=null)awake.close();}
        });
        if(failure[0]!=null)result.putString("failure",android.util.Log.getStackTraceString(failure[0]));
        else result.putString("result","PASS: "+checks+" native recording awake lifecycle checks");
        finish(failure[0]==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private Object field(Object owner,String name)throws Exception{
        java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private void require(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
}
