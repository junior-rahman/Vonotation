package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.media.MediaPlayer;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;

/** Native synthetic audio/recorder behavior. Does not establish real phone or call quality. */
final class VocadingAudioChecks {
    static int run(Instrumentation runner,Activity activity)throws Exception{
        Context context=activity.getApplicationContext();String before=Store.get(context,"notes","[]");
        File first=new File(Notes.sourceDirectory(context),"native-player-1.m4a"),second=new File(Notes.sourceDirectory(context),"native-player-2.m4a");long recording=-1;
        try{
            wave(first);wave(second);
            JSONObject source=new JSONObject().put("id",9002).put("created",9002).put("revision",1).put("audio",first.getName()).put("parts",new JSONArray().put(first.getName()).put(second.getName())).put("text","Native audio test").put("duration",6000);
            Store.prefs(context).edit().putString("notes",new JSONArray().put(source).toString()).commit();
            Method play=method(Notes.class,"togglePlay",Activity.class,JSONObject.class,Runnable.class);
            main(runner,()->play.invoke(null,activity,source,(Runnable)()->{}));
            await(()->!bool("loading")&&(long)value(Notes.class,"playing")==9002,"Audio did not prepare");
            main(runner,()->play.invoke(null,activity,source,(Runnable)()->{}));require(bool("paused"),"Pause failed");
            main(runner,()->method(Notes.class,"seekTo",long.class).invoke(null,3500L));
            await(()->!bool("loading"),"Seek did not prepare");require((int)value(Notes.class,"queueAt")==1&&bool("paused"),"Cross-segment seek lost pause state");
            main(runner,()->{Field speed=Notes.class.getDeclaredField("playbackSpeed");speed.setAccessible(true);speed.setFloat(null,1.5f);method(Notes.class,"applySpeed").invoke(null);});
            MediaPlayer player=(MediaPlayer)value(Notes.class,"player");require(!player.isPlaying(),"Changing speed resumed paused playback");
            main(runner,()->play.invoke(null,activity,source,(Runnable)()->{}));require(!bool("paused"),"Playback resume failed");
            await(()->Math.abs(player.getPlaybackParams().getSpeed()-1.5f)<.01f,"Playback speed not applied after resume");
            main(runner,Notes::stop);require((long)value(Notes.class,"playing")==-1,"Playback stop failed");
            // A pending asynchronous prepare cannot resurrect an explicitly stopped player.
            main(runner,()->{play.invoke(null,activity,source,(Runnable)()->{});Notes.stop();});SystemClock.sleep(350);
            require((long)value(Notes.class,"playing")==-1&&value(Notes.class,"player")==null,"Late prepare restarted playback");
            Method record=method(Notes.class,"record",Activity.class,Runnable.class);
            main(runner,()->record.invoke(null,activity,(Runnable)()->{}));
            await(()->VoiceTake.noteRunning()&&VoiceTake.elapsed()>650,"Recorder did not capture");recording=(long)value(Notes.class,"liveId");final long id=recording;
            screenshot(runner,"recording-active");
            main(runner,()->require(NoteRecordingService.togglePause(context,id,Notes::recordingChanged),"Pause command refused"));
            await(()->VoiceTake.notePaused()&&!VoiceTake.pauseChanging(),"Capture did not pause");long stopped=VoiceTake.elapsed();SystemClock.sleep(500);
            require(VoiceTake.elapsed()==stopped,"Paused time counted as recorded audio");
            screenshot(runner,"recording-paused");
            require(new File(Notes.sourceDirectory(context),id+"-0.m4a").length()>0,"Pause did not preserve source segment");
            main(runner,()->require(NoteRecordingService.togglePause(context,id,Notes::recordingChanged),"Resume command refused"));
            await(()->!VoiceTake.notePaused()&&!VoiceTake.pauseChanging()&&VoiceTake.elapsed()>stopped+650,"Capture did not resume");
            main(runner,()->{require(Notes.finishRecording(id),"Finish refused");NoteRecordingService.finalizing(context,id);});
            await(()->!VoiceTake.updateBusy()&&!NoteRecordingService.busy(),"Capture did not finalize");
            JSONObject saved=null;JSONArray notes=new JSONArray(Store.get(context,"notes","[]"));for(int i=0;i<notes.length();i++)if(notes.getJSONObject(i).optLong("id")==id)saved=notes.getJSONObject(i);
            require(saved!=null&&!saved.optBoolean("recording")&&saved.getJSONArray("parts").length()==2,"Pause/resume split or lost the note");
            require(saved.optString("capture_error").isEmpty()&&saved.optLong("duration")>900,"Final source was incomplete");
            return 14;
        }finally{
            main(runner,()->{Notes.stop();if(VoiceTake.noteRunning())Notes.finishRecording();});
            await(()->!VoiceTake.updateBusy(),"Fixture capture cleanup failed");
            if(recording>0){File[] parts=Notes.sourceDirectory(context).listFiles();if(parts!=null)for(File file:parts)if(file.getName().startsWith(recording+"-"))file.delete();}
            first.delete();second.delete();Store.prefs(context).edit().putString("notes",before).commit();SystemClock.sleep(3700);
        }
    }
    private static void screenshot(Instrumentation runner,String name)throws Exception{main(runner,Notes::recordingChanged);runner.waitForIdleSync();method(VisualPaletteTest.class,"capture",String.class).invoke(runner,name);
        android.view.View finish=(android.view.View)method(VisualPaletteTest.class,"findText",String.class).invoke(runner,"Finish");
        main(runner,()->{android.graphics.Rect visible=new android.graphics.Rect();require(finish!=null&&finish.getGlobalVisibleRect(visible)&&visible.width()==finish.getWidth(),"Finish control is clipped");});}
    private interface Action{void run()throws Exception;}
    private interface Condition{boolean get()throws Exception;}
    private static void main(Instrumentation runner,Action action)throws Exception{Throwable[] error={null};runner.runOnMainSync(()->{try{action.run();}catch(Throwable e){error[0]=e;}});if(error[0]!=null)throw new Exception("Main-thread audio check failed",error[0]);}
    private static void await(Condition condition,String message)throws Exception{for(int i=0;i<120;i++){if(condition.get())return;SystemClock.sleep(50);}throw new AssertionError(message);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static Method method(Class<?> cls,String name,Class<?>... args)throws Exception{Method m=cls.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Object value(Class<?> cls,String name)throws Exception{Field f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    private static boolean bool(String name)throws Exception{return (boolean)value(Notes.class,name);}
    private static void wave(File file)throws Exception{
        int count=24000;java.nio.ByteBuffer data=java.nio.ByteBuffer.allocate(44+count*2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes("US-ASCII")).putInt(36+count*2).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(count*2);
        for(int i=0;i<count;i++)data.putShort((short)(2000*Math.sin(i*2*Math.PI*220/8000)));try(FileOutputStream out=new FileOutputStream(file)){out.write(data.array());}
    }
}
