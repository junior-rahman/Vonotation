package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/** Isolated Android fixture: finalizing audio and queued note saves must block data replacement. */
public final class BackupRestoreBusyTest extends Instrumentation {
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    public void onStart(){Bundle result=new Bundle();SharedPreferences isolated=getTargetContext().getSharedPreferences("backup-restore-busy-test",Context.MODE_PRIVATE);
        Context context=new ContextWrapper(getTargetContext()){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return isolated;}
        };
        Field notesBusy=null;boolean previousBusy=false;AtomicInteger take=null,line=null,thought=null;
        try{
            require(!DriveBackup.configured(getTargetContext())&&!VoiceTake.updateBusy()&&!LineWire.updateBusy()&&!Notes.updateBusy()&&!Dictation.isWorking(),"Run only on an idle disposable app installation");
            isolated.edit().clear().putString("dictionary","current=Current").putString("history","[]").putString("notes","[]").commit();
            JSONObject archive=BackupData.snapshot(context);archive.getJSONObject("settings").put("dictionary","restored=Restored");
            Map<String,?> before=isolated.getAll();
            notesBusy=field(Notes.class,"busy");previousBusy=notesBusy.getBoolean(null);notesBusy.setBoolean(null,true);
            blocked(context,archive,before,"note processing");notesBusy.setBoolean(null,previousBusy);
            take=(AtomicInteger)field(VoiceTake.class,"pendingWork").get(null);take.incrementAndGet();try{blocked(context,archive,before,"audio finalization");}finally{take.decrementAndGet();take=null;}
            line=(AtomicInteger)field(LineWire.class,"pending").get(null);line.incrementAndGet();try{blocked(context,archive,before,"call finalization");}finally{line.decrementAndGet();line=null;}
            thought=(AtomicInteger)field(Notes.class,"thoughtWork").get(null);thought.incrementAndGet();try{blocked(context,archive,before,"queued thought organization/save");}finally{thought.decrementAndGet();thought=null;}
            result.putString("result","PASS: "+checks+" restore refusal checks for note processing, audio/call finalization and queued thought saves; local data unchanged");
        }catch(Throwable failure){result.putString("failure",android.util.Log.getStackTraceString(failure));}
        finally{
            try{if(notesBusy!=null)notesBusy.setBoolean(null,previousBusy);}catch(Exception ignored){}
            if(take!=null)take.decrementAndGet();if(line!=null)line.decrementAndGet();if(thought!=null)thought.decrementAndGet();isolated.edit().clear().commit();
        }
        finish(result.containsKey("failure")?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);
    }
    private void blocked(Context context,JSONObject archive,Map<String,?> before,String kind)throws Exception{
        boolean rejected=false;try{DriveBackup.restore(context,archive);}catch(DriveBackup.ConfigurationException expected){rejected=expected.getMessage().contains("Finish recording or processing");}
        require(rejected,"Restore did not refuse during "+kind);require(before.equals(Store.prefs(context).getAll()),"Restore changed data during "+kind);
    }
    private static Field field(Class<?> owner,String name)throws Exception{Field field=owner.getDeclaredField(name);field.setAccessible(true);return field;}
    private void require(boolean pass,String message){checks++;if(!pass)throw new AssertionError(message);}
}
