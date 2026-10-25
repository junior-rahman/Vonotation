package app.voiceflow;

import android.content.Context;
import java.io.File;

/** Process-owned retry. Activities observe it; rotation never cancels or owns the work. */
final class RetryCoordinator {
    private static Dictation job;
    private static File source;
    private static String status="";
    private static Runnable observer;
    static boolean busy(){return job!=null;}
    static boolean owns(File file){return source!=null&&source.equals(file)&&busy();}
    static String status(){return status;}
    static void observe(Runnable value){observer=value;}
    static void detach(Runnable value){if(observer==value)observer=null;}
    private static void changed(){if(observer!=null)observer.run();}
    static void cancel(){Dictation active=job;job=null;source=null;if(active!=null)active.cancel();status="Retry stopped. Your recording is still available.";changed();}
    static void start(Context context,File audio){
        if(audio==null||busy())return;
        Context app=context.getApplicationContext();
        if(Dictation.isWorking()||VoiceTake.updateBusy()||Translate.going()){status="Finish the current recording first.";changed();return;}
        boolean thought=RetryBuffer.thought(audio);
        source=audio;
        job=new Dictation(app,new Dictation.Listener(){
            public void state(String value){status=value;changed();}
            public void partial(String value){}
            public void level(float value){}
            public void result(String raw,String text,String warning){
                boolean saved;
                if(thought){long id=Notes.keepThought(app,RetryBuffer.time(audio),raw,0,audio);saved=id>0;if(saved)Notes.formatThought(app,id);}
                else saved=Store.save(app,raw,text,0);
                Dictation done=job;
                if(saved&&done!=null)done.acknowledgeSaved();
                if(done!=null)done.close();job=null;source=null;
                status=saved?(thought?"Thought saved.":"Saved to Dictation."):"Transcribed. Copy the text below, or retry saving it. Audio remains available until its expiry.";
                if(!warning.isEmpty())status+=" "+warning;
                changed();
            }
            public void error(String message){Dictation done=job;job=null;source=null;if(done!=null)done.close();status=message;changed();}
        },false,thought);
        job.retry(audio);
    }
    private RetryCoordinator(){}
}
