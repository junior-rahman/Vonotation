package app.voiceflow;

import android.app.Activity;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Saved recordings with playback, like a voice recorder. The audio stays so a wrong language can be sent again. */
final class Notes {
    static final String[] CODES={"auto","en","bn","hi","es","ne"};
    static final String[] NAMES={"Auto","English","বাংলা","हिन्दी","Español","नेपाली"};
    private static long open=-1;
    private static volatile long transcribing=-1;
    private static String pick="auto";
    private static boolean fixing,original,choosing;
    private static volatile boolean busy;
    private static List<File> queue=new ArrayList<>();
    private static int queueAt;
    private static MediaPlayer player;
    private static long playing=-1,liveId=-1;
    private static volatile boolean draining;
    private static boolean translating;
    private static final java.util.concurrent.atomic.AtomicInteger thoughtWork=new java.util.concurrent.atomic.AtomicInteger();
    private static Wave liveWave,playWave;
    private static TextView liveTime,playTime;
    private static long totalDuration,playedBefore;
    private static final Handler tick=new Handler(Looper.getMainLooper());
    private static boolean callsOnly,thoughtsOnly;
    private static java.lang.ref.WeakReference<Activity> screen=new java.lang.ref.WeakReference<>(null);
    static void callsMode(boolean on){callsOnly=on;thoughtsOnly=false;}
    static void thoughtsMode(){callsOnly=false;thoughtsOnly=true;}
    static boolean thoughtOpened(Context context){JSONObject note=find(context,open);return note!=null && note.optBoolean("thought");}
    static boolean opened(){return open>=0;}
    /** Updater gate includes queued thought formatting until its guarded save finishes. */
    static boolean updateBusy(){return busy || draining || transcribing>=0 || thoughtWork.get()>0;}
    private static Runnable onChange=()->{};
    private static final java.util.concurrent.ExecutorService work=java.util.concurrent.Executors.newSingleThreadExecutor();

    private Notes() {}

    static boolean back(){if(open<0)return false;stopPlayback();fixing=false;original=false;translating=false;open=-1;return true;}
    static void stop(){stopPlayback();}

    static void show(Activity activity,LinearLayout body,Runnable refresh) { show(activity,body,null,refresh); }
    static void show(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        screen=new java.lang.ref.WeakReference<>(activity);onChange=refresh;
        catchUp(activity.getApplicationContext());
        if(thoughtsOnly && !Store.quickThoughts(activity))return;
        if(open<0)list(activity,body,dock,refresh);else detail(activity,body,refresh);
    }

    private static void list(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        if(thoughtsOnly){thoughtList(activity,body,refresh);return;}
        if(!callsOnly){
            body.addView(Daybook.text(activity,"I'll hold this and write it up when you're back online.",13,Daybook.muted(activity),false));
            Ui.space(body,8);
            TextView language=Daybook.text(activity,name(pick),14,Daybook.accent(activity),true);
            language.setOnClickListener(v->{Daybook.feedback(v);if(!busy && !VoiceTake.noteRunning()){choosing=!choosing;refresh.run();}});
            body.addView(language);
            if(choosing && !VoiceTake.noteRunning()){Ui.space(body,8);chips(activity,body,refresh);}
        } else if(VoiceTake.callRunning()){
            body.addView(Daybook.button(activity,"Finish",true,v->{VoiceTake.finish();refresh.run();}),new LinearLayout.LayoutParams(-1,Ui.dp(activity,52)));
        }
        if(callsOnly && VoiceTake.callRunning()){
            Ui.space(body,10);
            LinearLayout live=Daybook.card(activity);live.setPadding(Ui.dp(activity,16),Ui.dp(activity,14),Ui.dp(activity,16),Ui.dp(activity,14));
            live.addView(Daybook.text(activity,"Recording this call",16,Daybook.ink(activity),true));
            Ui.space(live,4);
            liveTime=Daybook.text(activity,clock(VoiceTake.elapsed()),20,Daybook.accent(activity),true);
            live.addView(liveTime);
            Ui.space(live,8);
            liveWave=new Wave(activity);liveWave.show(VoiceTake.liveBars(48),1f);live.addView(liveWave,new LinearLayout.LayoutParams(-1,Ui.dp(activity,44)));
            Ui.space(live,6);
            String hint=VoiceTake.bothSides()?"Both sides are being recorded. You can leave this screen.":"Turn the speaker on so the other person is recorded too. Your side is always saved.";
            live.addView(Daybook.text(activity,hint,13,Daybook.muted(activity),false));
            body.addView(live);armTick();
        } else if(callsOnly){ liveTime=null; liveWave=null; }
        Ui.space(body,14);
        JSONArray notes=all(activity);
        if(dock!=null && !callsOnly){
            dockHost=dock;onRefresh=refresh;
            dock.removeAllViews();
            dock.addView(actionPill(activity,refresh),new android.widget.FrameLayout.LayoutParams(-2,-2));
        }
        if(notes.length()==0 && !VoiceTake.running()){
            LinearLayout empty=Daybook.card(activity);empty.setPadding(Ui.dp(activity,16),Ui.dp(activity,18),Ui.dp(activity,16),Ui.dp(activity,18));
            if(callsOnly){empty.addView(Daybook.text(activity,"No calls yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"When a call connects, it is saved here and written up online.",13,Daybook.muted(activity),false));}
            else{empty.addView(Daybook.text(activity,"Nothing recorded yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"Tap Record. The audio stays on this phone, and the words fill in when you're online.",13,Daybook.muted(activity),false));}
            body.addView(empty);return;
        }
        String bucket="";
        int shown=0;
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.optJSONObject(i);if(note==null)continue;
            if(note.optBoolean("thought") || (callsOnly?!note.optBoolean("call"):note.optBoolean("call")))continue;
            shown++;
            String group=bucketOf(note);
            if(!group.equals(bucket)){bucket=group;if(i>0)Ui.space(body,8);body.addView(Daybook.text(activity,group,13,Daybook.muted(activity),true));Ui.space(body,6);}
            long id=note.optLong("id");
            LinearLayout card=Daybook.card(activity);card.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
            card.setClipToOutline(true);
            LinearLayout row=Ui.row(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout words=Ui.column(activity);
            boolean coming=transcribing==id;
            TextView line=Daybook.text(activity,coming?"Writing it up…":titleOf(note),16,coming?Daybook.muted(activity):Daybook.ink(activity),false);
            line.setMaxLines(2);line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(line);
            Ui.space(words,4);
            String who=note.optString("who","");
            String kind=note.optBoolean("call")?(who.isEmpty()?"Call":who):"";
            String meta=when(note)+" · "+clock(note.optLong("duration"))+(kind.isEmpty()?"":" · "+kind)+" · "+name(note.optString("lang","auto"));
            TextView metaLine=Daybook.text(activity,meta,12,Daybook.muted(activity),false);
            metaLine.setMaxLines(1);metaLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(metaLine);
            row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            boolean on=playing==id;
            Button play=Daybook.button(activity,on?"Pause":"Play",false,v->togglePlay(activity,note,refresh));
            if(filesOf(activity,note).isEmpty())play.setEnabled(false);
            LinearLayout.LayoutParams playLp=new LinearLayout.LayoutParams(Ui.dp(activity,72),Ui.dp(activity,40));playLp.leftMargin=Ui.dp(activity,8);
            row.addView(play,playLp);
            card.addView(row);
            String preview=previewOf(note);
            if(!preview.isEmpty()){
                Ui.space(card,8);
                android.view.View rule=new android.view.View(activity);rule.setBackgroundColor(Daybook.line(activity));
                card.addView(rule,new LinearLayout.LayoutParams(-1,Ui.dp(activity,1)));
                Ui.space(card,8);
                TextView peek=Daybook.text(activity,preview,14,Daybook.muted(activity),false);
                peek.setMaxLines(2);peek.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(peek);
            }
            if(on){
                Ui.space(card,8);
                playWave=new Wave(activity);playWave.show(barsOf(note.optString("wave")),totalDuration<=0?0:position()/(float)totalDuration);
                card.addView(playWave,new LinearLayout.LayoutParams(-1,Ui.dp(activity,36)));
                armTick();
            }
            card.setOnClickListener(v->{Daybook.feedback(v);open=id;fixing=false;original=false;translating=false;refresh.run();});
            body.addView(card);Ui.space(body,8);
        }
        if(shown==0 && !VoiceTake.noteRunning()){
            LinearLayout empty=Daybook.card(activity);empty.setPadding(Ui.dp(activity,16),Ui.dp(activity,18),Ui.dp(activity,16),Ui.dp(activity,18));
            if(callsOnly){empty.addView(Daybook.text(activity,"No calls yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"A connected call shows up here, not in Notes.",13,Daybook.muted(activity),false));}
            else{empty.addView(Daybook.text(activity,"No voice notes yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"Record is for meetings and memos. Phone calls stay in Calls.",13,Daybook.muted(activity),false));}
            body.addView(empty);
        }
        if(playing<0)playWave=null;
    }

    private static void detail(Activity activity,LinearLayout body,Runnable refresh) {
        JSONObject note=find(activity,open);
        TextView back=Daybook.text(activity,"Back",14,Daybook.accent(activity),true);back.setMinHeight(Ui.dp(activity,44));back.setGravity(android.view.Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v->{Daybook.feedback(v);back();refresh.run();});body.addView(back);
        if(note==null){body.addView(Daybook.text(activity,"This recording is gone.",18,Daybook.ink(activity),true));return;}
        if(note.optBoolean("thought")){thoughtDetail(activity,body,note,refresh);return;}
        long id=note.optLong("id");
        boolean coming=transcribing==id;
        boolean on=playing==id;
        body.addView(Daybook.text(activity,titleOf(note),22,Daybook.ink(activity),true));
        Ui.space(body,4);
        body.addView(Daybook.text(activity,(note.optBoolean("call")?"Call · ":"")+when(note)+" · "+name(note.optString("heard","").isEmpty()?note.optString("lang","auto"):note.optString("heard","")),12,Daybook.muted(activity),false));
        Ui.space(body,12);
        playTime=Daybook.text(activity,(on?clock(position()):"0:00")+" / "+clock(note.optLong("duration")),16,Daybook.ink(activity),true);
        body.addView(playTime);
        Ui.space(body,8);
        playWave=new Wave(activity);
        playWave.show(barsOf(note.optString("wave")),on && totalDuration>0?position()/(float)totalDuration:0);
        body.addView(playWave,new LinearLayout.LayoutParams(-1,Ui.dp(activity,56)));
        if(on)armTick();
        Ui.space(body,12);
        LinearLayout controls=Ui.row(activity);controls.setGravity(android.view.Gravity.CENTER);
        TextView back5=Daybook.text(activity,"−5s",14,Daybook.muted(activity),true);back5.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8));back5.setOnClickListener(v->{Daybook.feedback(v);nudge(-5000);});
        controls.addView(back5);
        LinearLayout.LayoutParams mid=new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1);mid.leftMargin=Ui.dp(activity,8);mid.rightMargin=Ui.dp(activity,8);
        Button play=Daybook.button(activity,on?"Pause":"Play",true,v->togglePlay(activity,note,refresh));
        if(filesOf(activity,note).isEmpty())play.setEnabled(false);
        controls.addView(play,mid);
        TextView fwd=Daybook.text(activity,"+5s",14,Daybook.muted(activity),true);fwd.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8));fwd.setOnClickListener(v->{Daybook.feedback(v);nudge(5000);});
        controls.addView(fwd);
        body.addView(controls);
        Ui.space(body,16);
        String summary=note.optString("summary","");
        if(!coming && !summary.isEmpty()){
            TextView lead=Daybook.text(activity,summary,16,Daybook.ink(activity),false);
            lead.setLineSpacing(Ui.dp(activity,3),1);lead.setTextIsSelectable(true);body.addView(lead);
            Ui.space(body,14);
        }
        String shown=coming?"Writing it up…":note.optBoolean("pending")?"Saved on this phone. I'll write the summary and the full note when you're online.":note.optString("text","").isEmpty()?"The words will show up here.":note.optString("text");
        if(!coming && !note.optBoolean("pending") && !note.optString("text","").isEmpty())body.addView(Daybook.text(activity,"Everything you said",12,Daybook.muted(activity),true));
        Ui.space(body,4);
        TextView transcript=Daybook.text(activity,shown,17,note.optString("text","").isEmpty()?Daybook.muted(activity):Daybook.ink(activity),false);
        transcript.setLineSpacing(Ui.dp(activity,4),1);transcript.setTextIsSelectable(!coming && !note.optString("text","").isEmpty());
        body.addView(transcript);
        String translation=note.optString("translation","");
        if(!translation.isEmpty()){
            Ui.space(body,14);
            body.addView(Daybook.text(activity,"Translation · "+name(note.optString("translationLang","")),12,Daybook.muted(activity),true));
            Ui.space(body,4);
            TextView other=Daybook.text(activity,translation,16,Daybook.ink(activity),false);other.setTextIsSelectable(true);body.addView(other);
        }
        Ui.space(body,18);
        LinearLayout actions=Ui.row(activity);actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        String writeLabel=note.optBoolean("pending")?"Transcribe":"Again";
        actions.addView(quiet(activity,coming?"Working":writeLabel,v->{
            if(note.optBoolean("pending"))catchUp(activity.getApplicationContext());
            else retranscribe(activity,note,note.optString("lang","auto"),refresh);
        }));
        actions.addView(quiet(activity,"Summary",v->summarize(activity,note)));
        actions.addView(quiet(activity,"Translate",v->{translating=!translating;fixing=false;refresh.run();}));
        actions.addView(quiet(activity,"Language",v->{fixing=!fixing;translating=false;refresh.run();}));
        actions.addView(quiet(activity,"Delete",v->confirmDelete(activity,note,refresh)));
        body.addView(actions);
        if(translating){Ui.space(body,8);languageChoices(activity,body,note.optString("translationLang",""),code->{translating=false;translateNote(activity,note,code);});}
        if(fixing){Ui.space(body,8);languageChoices(activity,body,note.optString("lang","auto"),code->retranscribe(activity,note,code,refresh));}
    }

    private static TextView quiet(Activity activity,String label,android.view.View.OnClickListener click) {
        TextView word=Daybook.text(activity,label,13,Daybook.accent(activity),true);
        word.setPadding(0,Ui.dp(activity,8),Ui.dp(activity,14),Ui.dp(activity,8));
        word.setOnClickListener(v->{Daybook.feedback(v);click.onClick(v);});
        return word;
    }

    private static void languageChoices(Activity activity,LinearLayout body,String selected,java.util.function.Consumer<String> pick) {
        android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=Ui.row(activity);
        for(int i=1;i<CODES.length;i++){
            String code=CODES[i];
            TextView chip=Daybook.text(activity,NAMES[i],14,code.equals(selected)?Daybook.ink(activity):Daybook.muted(activity),true);
            chip.setPadding(Ui.dp(activity,12),Ui.dp(activity,8),Ui.dp(activity,12),Ui.dp(activity,8));
            chip.setOnClickListener(v->{Daybook.feedback(v);pick.accept(code);});
            row.addView(chip);
        }
        scroll.addView(row);body.addView(scroll);
    }

    private static void chips(Activity activity,LinearLayout body,Runnable refresh) {
        android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row=Ui.row(activity);
        for(int i=0;i<CODES.length;i++){
            String code=CODES[i];boolean on=pick.equals(code);
            Button chip=Daybook.button(activity,NAMES[i],on,v->{if(!busy && !VoiceTake.running()){pick=code;choosing=false;refresh.run();}});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,Ui.dp(activity,40));lp.rightMargin=Ui.dp(activity,6);row.addView(chip,lp);
        }
        scroll.addView(row);body.addView(scroll);
    }

    private static void record(Activity activity,Runnable refresh) {
        if(Translate.going()){toast(activity,"Translation is still going. Finish it first.");return;}
        if(VoiceTake.callRunning() || LineWire.running()){toast(activity,"A call is being recorded. Wait until it ends.");return;}
        if(VoiceTake.running()){
            VoiceTake.finish();
            if(liveDigits!=null){liveDigits.animate().cancel();liveDigits.setTranslationY(0);liveDigits.setAlpha(1f);liveDigits.setTextSize(16);liveDigits.setText("Saving…");shownFace="";}
            if(liveMeter!=null)liveMeter.setVisibility(android.view.View.GONE);
            if(beat!=null){beat.cancel();beat=null;}
            return;
        }
        if(busy)return;
        choosing=false;
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO,android.Manifest.permission.ANSWER_PHONE_CALLS},22);return;}
        if(activity.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)activity.requestPermissions(new String[]{android.Manifest.permission.ANSWER_PHONE_CALLS},24);
        stopPlayback();
        Context app=activity.getApplicationContext();
        long id=System.currentTimeMillis();
        liveId=id;
        File folder=dir(app);
        holdMic(app,true);
        VoiceTake.beginNote(app,new VoiceTake.Listener(){
            public void listening(){if(liveDigits!=null && liveDigits.isAttachedToWindow())armTick();}
            public void failed(String message){liveId=-1;holdMic(app,false);toast(app,message);poke();}
            public void finished(File file){}
            public void partReady(List<File> files){remember(app,id,files,pick,true);}
            public void finishedAll(List<File> files){liveId=-1;holdMic(app,false);remember(app,id,files,pick,true);stamp(app,id,"wave",barsText(VoiceTake.bars()));toast(app,online(app)?"Saved. Writing it up.":"Saved on this phone. I'll write it up when you're online.");writeUp(app,id);}
        },folder,id);
        if(dockHost!=null && VoiceTake.noteRunning() && !VoiceTake.callRunning()){dockHost.removeAllViews();dockHost.addView(actionPill(activity,refresh),new android.widget.FrameLayout.LayoutParams(-2,-2));armTick();}
        else refresh.run();
    }

    private static android.widget.FrameLayout dockHost;
    private static Runnable onRefresh;
    private static TextView liveDigits;
    private static Wave liveMeter;
    private static android.animation.ObjectAnimator beat;
    private static String shownFace="";
    private static android.view.View actionPill(Activity activity,Runnable refresh){
        boolean timing=VoiceTake.noteRunning() && !VoiceTake.callRunning();
        boolean held=!timing && (Translate.going() || VoiceTake.callRunning() || LineWire.running());
        LinearLayout pill=Ui.column(activity);
        pill.setBackground(Daybook.ripple(activity,Daybook.action(activity),26));
        pill.setElevation(Ui.dp(activity,8));
        pill.setMinimumWidth(Ui.dp(activity,168));
        pill.setPadding(Ui.dp(activity,22),Ui.dp(activity,10),Ui.dp(activity,22),Ui.dp(activity,10));
        if(held)pill.setAlpha(0.4f);
        if(!timing){
            liveDigits=null;liveMeter=null;shownFace="";
            if(beat!=null){beat.cancel();beat=null;}
            TextView label=Daybook.text(activity,busy?"Saving…":"Record",16,Daybook.actionInk(activity),true);
            label.setGravity(android.view.Gravity.CENTER);
            label.setMinHeight(Ui.dp(activity,32));
            pill.addView(label);
            pill.setOnClickListener(v->{Daybook.feedback(v);record(activity,refresh);});
            pill.setContentDescription(busy?"Saving":"Record");
            return pill;
        }
        TextView title=Daybook.text(activity,"Recording",12,Daybook.actionInk(activity),true);
        title.setGravity(android.view.Gravity.CENTER);
        pill.addView(title);
        LinearLayout row=Ui.row(activity);row.setGravity(android.view.Gravity.CENTER);
        android.view.View dot=new android.view.View(activity);
        android.graphics.drawable.GradientDrawable mark=new android.graphics.drawable.GradientDrawable();
        mark.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        mark.setColor(Daybook.actionInk(activity));
        dot.setBackground(mark);
        int d=Ui.dp(activity,8);
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(d,d);dp.rightMargin=Ui.dp(activity,8);
        row.addView(dot,dp);
        if(beat!=null)beat.cancel();
        beat=android.animation.ObjectAnimator.ofFloat(dot,android.view.View.ALPHA,1f,0.25f);
        beat.setDuration(700);beat.setRepeatMode(android.animation.ValueAnimator.REVERSE);beat.setRepeatCount(android.animation.ValueAnimator.INFINITE);beat.start();
        liveDigits=Daybook.text(activity,face(VoiceTake.elapsed()),22,Daybook.actionInk(activity),true);
        liveDigits.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD);
        liveDigits.setGravity(android.view.Gravity.CENTER);
        shownFace=face(VoiceTake.elapsed());
        row.addView(liveDigits);
        pill.addView(row);
        Ui.space(pill,6);
        liveMeter=new Wave(activity);liveMeter.tone(Daybook.actionInk(activity));liveMeter.show(VoiceTake.liveBars(24),1f);
        pill.addView(liveMeter,new LinearLayout.LayoutParams(Ui.dp(activity,148),Ui.dp(activity,28)));
        pill.setOnClickListener(v->{Daybook.feedback(v);record(activity,refresh);});
        pill.setContentDescription("Finish recording");
        armTick();
        return pill;
    }
    private static void roll(TextView digits,String next){
        if(digits==null || next.equals(shownFace))return;
        boolean first=shownFace.isEmpty();
        shownFace=next;
        if(first){digits.setText(next);return;}
        digits.animate().cancel();
        digits.setTranslationY(Ui.dp(digits.getContext(),12));digits.setAlpha(0f);digits.setText(next);
        digits.animate().translationY(0).alpha(1f).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    }
    private static String face(long ms){long s=Math.max(0,ms/1000);if(s>=3600)return String.format(Locale.US,"%d:%02d:%02d",s/3600,(s%3600)/60,s%60);return String.format(Locale.US,"%02d:%02d",s/60,s%60);}

    private static long callId=-1;

    /** Started by the call watcher so the recording survives leaving this screen. */
    static void beginCall(Context context) {
        if(VoiceTake.running() || busy)return;
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){toast(context,"Allow microphone access first.");return;}
        long id=System.currentTimeMillis();
        liveId=id;callId=id;
        File folder=dir(context);
        VoiceTake.beginCall(context,new VoiceTake.Listener(){
            public void listening(){poke();}
            public void failed(String message){liveId=-1;toast(context,message);poke();}
            public void finished(File file){}
            public void partReady(List<File> files){remember(context,id,files,pick,true);}
            public void finishedAll(List<File> files){liveId=-1;remember(context,id,files,pick,true);stamp(context,id,"wave",barsText(VoiceTake.bars()));toast(context,online(context)?"Call saved. Writing it up.":"Call saved on this phone. I'll write it up when you're online.");writeUp(context,id);}
        },folder,id);
    }

    /** A call captured as a finished m4a, already in the notes folder. */
    static void keepFile(Context context,File audio) {
        if(audio==null || !audio.isFile() || audio.length()<1000)return;
        long id;
        try { id=Long.parseLong(audio.getName().substring(0,audio.getName().indexOf('-'))); }
        catch(Exception error) { id=System.currentTimeMillis(); }
        callId=id;
        java.util.List<File> files=new java.util.ArrayList<>();
        files.add(audio);
        remember(context,id,files,"auto",true);
        toast(context,online(context)?"Call saved. Writing it up.":"Call saved on this phone. I'll write it up when you're online.");
        writeUp(context,id);
    }

    private static void retranscribe(Activity activity,JSONObject note,String code,Runnable refresh) {
        if(filesOf(activity,note).isEmpty()){Toast.makeText(activity,"The recording is missing.",Toast.LENGTH_SHORT).show();return;}
        fixing=false;busy=true;transcribing=note.optLong("id");refresh.run();
        long id=note.optLong("id");
        Context app=activity.getApplicationContext();
        work.execute(()->{
            try {
                String[] detected=new String[]{code};
                String raw=transcribeFiles(app,filesOf(app,note),code,detected);
                String text=organize(app,raw);
                String summary=recap(app,text);
                save(app,id,text,raw,!text.equals(raw),code,detected[0],note.optString("audio"),note.optLong("duration"),summary,false,note.optJSONArray("parts"));
                busy=false;transcribing=-1;poke();
            } catch(Exception error) {
                busy=false;transcribing=-1;toast(app,offlineMessage(app,error));poke();
            }
        });
    }

    private static void confirmDelete(Activity activity,JSONObject note,Runnable refresh) {
        new android.app.AlertDialog.Builder(activity).setTitle("Delete this recording?").setMessage("The audio and the words are removed from this phone.").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->{remove(activity,note.optLong("id"));back();refresh.run();}).show();
    }

    private static void togglePlay(Activity activity,JSONObject note,Runnable refresh) {
        long id=note.optLong("id");
        if(playing==id){stopPlayback();refresh.run();return;}
        stopPlayback();
        List<File> files=filesOf(activity,note);
        if(files.isEmpty()){Toast.makeText(activity,"The recording is missing.",Toast.LENGTH_SHORT).show();return;}
        queue=files;queueAt=0;playedBefore=0;totalDuration=Math.max(1,note.optLong("duration"));playing=id;playQueue(activity,refresh);armTick();
        if(playing==id)PlayWatch.show(activity.getApplicationContext(),titleOf(note));
    }

    private static void playQueue(Activity activity,Runnable refresh) {
        if(queue==null || queueAt>=queue.size()){stopPlayback();activity.runOnUiThread(refresh);return;}
        try {
            player=new MediaPlayer();
            player.setDataSource(queue.get(queueAt).getAbsolutePath());
            player.prepare();
            int span=Math.max(0,player.getDuration());
            player.setOnCompletionListener(mp->{playedBefore+=span;try{mp.release();}catch(Exception ignored){} if(player==mp)player=null; queueAt++; playQueue(activity,refresh);});
            player.start();
            activity.runOnUiThread(refresh);
        } catch(Exception e) {
            stopPlayback();Toast.makeText(activity,"Couldn't play that recording.",Toast.LENGTH_SHORT).show();
        }
    }

    private static void stopPlayback(){playing=-1;if(player!=null){try{player.stop();}catch(Exception ignored){}try{player.release();}catch(Exception ignored){}player=null;}PlayWatch.hide();}
    static void stopFromNotice(){stopPlayback();poke();}

    private static void touch(Activity activity,JSONObject note,Runnable refresh) {
        if(busy)return;
        String raw=note.optString("raw","");
        if(raw.isEmpty())raw=note.optString("text","");
        if(raw.trim().isEmpty())return;
        busy=true;refresh.run();
        String source=raw;long id=note.optLong("id");String lang=note.optString("lang","auto");String heard=note.optString("heard","");String audio=note.optString("audio","");long duration=note.optLong("duration");
        work.execute(()->{
            try {
                String text=new CloudApi(activity).touchUp(source);
                save(activity,id,text,source,!text.equals(source),lang,heard,audio,duration,note.optString("summary",""),note.optBoolean("pending"),note.optJSONArray("parts"));
                activity.runOnUiThread(()->{busy=false;refresh.run();});
            } catch(Exception error) {
                activity.runOnUiThread(()->{busy=false;Toast.makeText(activity,error.getMessage()==null?"Touch-up failed. The transcript was kept.":error.getMessage(),Toast.LENGTH_LONG).show();refresh.run();});
            }
        });
    }

    /** Basic cleanup, then AI touch-up. Verbatim mode keeps the recognizer text. A failed touch-up keeps the cleaned text. */
    private static String organize(Context context,String raw) {
        if(raw.isEmpty() || Store.verbatim(context))return raw;
        String clean=Store.clean(context,raw);
        if(Vault.get(context).isEmpty() || Store.engine(context).equals("offline"))return clean;
        try { String touched=new CloudApi(context).touchUp(clean); return touched==null||touched.trim().isEmpty()?clean:touched.trim(); }
        catch(Exception ignored){return clean;}
    }
    private static String heardLabel(String heard){
        if(heard==null||heard.isEmpty())return "?";
        String h=heard.toLowerCase(Locale.ROOT);
        if(h.startsWith("en")||h.equals("english"))return "English";
        if(h.startsWith("bn")||h.contains("bengali")||h.contains("bangla"))return "বাংলা";
        if(h.startsWith("hi")||h.contains("hindi"))return "हिन्दी";
        if(h.startsWith("es")||h.contains("spanish"))return "Español";
        if(h.startsWith("ne")||h.contains("nepali"))return "नेपाली";
        return heard;
    }
    static String name(String code){for(int i=0;i<CODES.length;i++)if(CODES[i].equals(code))return NAMES[i];return code==null||code.isEmpty()?"Auto":code;}
    private static String titleOf(JSONObject note){
        if(note.optBoolean("thought"))return note.optString("title",QuickThoughtPolicy.title(note.optString("text"),"Quick thought"));
        if(note.optBoolean("call") && note.optString("text","").isEmpty() && note.optString("summary","").isEmpty()){String who=note.optString("who","");return who.isEmpty()?"Phone call":who;}
        String summary=note.optString("summary","").replace('\n',' ').trim();
        if(!summary.isEmpty()){int dot=summary.indexOf('.');String line=dot>20 && dot<90?summary.substring(0,dot):summary;return line.length()>80?line.substring(0,80).trim():line;}
        String text=note.optString("text","").replace('\n',' ').trim();
        if(!text.isEmpty())return text.length()>80?text.substring(0,80).trim():text;
        return "New recording";
    }
    private static String previewOf(JSONObject note){
        String text=note.optString("text","").replace('\n',' ').trim();
        String summary=note.optString("summary","").replace('\n',' ').trim();
        String translation=note.optString("translation","").replace('\n',' ').trim();
        if(!summary.isEmpty() && !text.isEmpty())return text;
        if(!translation.isEmpty())return translation;
        if(note.optBoolean("pending"))return "Writes the summary and the full note when you're online.";
        return "";
    }
    private static String when(JSONObject note){
        long created=note.optLong("created",note.optLong("id"));
        java.util.Calendar now=java.util.Calendar.getInstance(),then=java.util.Calendar.getInstance();
        then.setTimeInMillis(created);
        boolean today=now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR)==then.get(java.util.Calendar.DAY_OF_YEAR);
        if(today)return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(created));
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(created));
    }
    private static String bucketOf(JSONObject note){
        long created=note.optLong("created",note.optLong("id"));
        java.util.Calendar now=java.util.Calendar.getInstance(),then=java.util.Calendar.getInstance();
        then.setTimeInMillis(created);
        if(now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR)==then.get(java.util.Calendar.DAY_OF_YEAR))return "Today";
        if(now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.MONTH)==then.get(java.util.Calendar.MONTH))return "This month";
        return "Older";
    }
    private static String barsText(int[] bars){
        if(bars==null || bars.length==0)return "";
        StringBuilder text=new StringBuilder();
        for(int i=0;i<bars.length;i++){if(i>0)text.append(',');text.append(Math.max(0,Math.min(9,bars[i])));}
        return text.toString();
    }
    private static int[] barsOf(String text){
        if(text==null || text.isEmpty())return new int[]{2,3,2,4,3,2};
        String[] parts=text.split(",");
        int[] bars=new int[parts.length];
        for(int i=0;i<parts.length;i++)try{bars[i]=Integer.parseInt(parts[i]);}catch(Exception e){bars[i]=2;}
        return bars;
    }
    private static void summarize(Activity activity,JSONObject note){
        String text=note.optString("text","");
        if(text.trim().isEmpty() || busy){if(text.trim().isEmpty())Toast.makeText(activity,"Nothing to summarize yet.",Toast.LENGTH_SHORT).show();return;}
        busy=true;long id=note.optLong("id");Context app=activity.getApplicationContext();
        work.execute(()->{
            try{String summary=new CloudApi(app).noteSummary(text);if(summary==null||summary.isEmpty())throw new java.io.IOException("No summary.");stamp(app,id,"summary",summary);}
            catch(Exception error){toast(app,error.getMessage()==null?"Couldn't summarize.":error.getMessage());}
            busy=false;poke();
        });
    }
    private static void translateNote(Activity activity,JSONObject note,String code){
        String text=note.optString("text","");
        if(text.trim().isEmpty() || busy){if(text.trim().isEmpty())Toast.makeText(activity,"Nothing to translate yet.",Toast.LENGTH_SHORT).show();return;}
        busy=true;long id=note.optLong("id");Context app=activity.getApplicationContext();
        String from=name(note.optString("lang","auto"));if(from.equals("Auto"))from=heardLabel(note.optString("heard",""));
        String to=name(code);String sourceLang=from;
        work.execute(()->{
            try{String result=new CloudApi(app).translate(text,sourceLang,to);stamp(app,id,"translation",result);stamp(app,id,"translationLang",code);}
            catch(Exception error){toast(app,error.getMessage()==null?"Couldn't translate.":error.getMessage());}
            busy=false;poke();
        });
    }
    private static String headline(JSONObject note){return titleOf(note);}
    private static String clock(long ms){long s=Math.max(0,ms/1000);if(s>=3600)return (s/3600)+":"+String.format(Locale.US,"%02d:%02d",(s%3600)/60,s%60);return (s/60)+":"+String.format(Locale.US,"%02d",s%60);}
    private static String meta(Activity activity,JSONObject note){
        long created=note.optLong("created",note.optLong("id"));
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(new Date(created));
    }

    private static File dir(Context context){File folder=new File(context.getFilesDir(),"notes");if(!folder.isDirectory())folder.mkdirs();return folder;}

    private static File storeAudio(Activity activity,long id,File source) {
        File dest=new File(dir(activity),id+".m4a");
        try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(dest)){
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);return dest;
        } catch(Exception e){dest.delete();return null;}
    }
    private static long audioDuration(File file){
        MediaMetadataRetriever r=new MediaMetadataRetriever();
        try{r.setDataSource(file.getAbsolutePath());String v=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return v==null?0:Long.parseLong(v);}
        catch(Exception e){return 0;}
        finally{try{r.release();}catch(Exception ignored){}}
    }

    private static JSONArray all(Context context){try{return new JSONArray(Store.get(context,"notes","[]"));}catch(Exception e){return new JSONArray();}}

    private static JSONObject find(Context context,long id) {
        JSONArray notes=all(context);
        for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null && note.optLong("id")==id)return note;}
        return null;
    }

    static void catchUp(Context context) {
        if(context==null)return;
        JSONArray notes=all(context);
        for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null && note.optBoolean("pending")){writeUp(context,note.optLong("id"));return;}}
    }

    private static void writeUp(Context context,long id) {
        synchronized(Notes.class){if(draining)return;if(!online(context) || Vault.get(context).isEmpty())return;draining=true;transcribing=id;}
        poke();
        work.execute(()->{
            boolean wrote=false;
            try {
                JSONObject note=find(context,id);
                List<File> files=note==null?new ArrayList<>():filesOf(context,note);
                if(note==null || files.isEmpty())throw new java.io.IOException("The recording is missing.");
                String language=note.optString("lang","auto");
                String[] detected=new String[]{language};
                String raw=transcribeFiles(context,files,language,detected);
                if(raw.isEmpty()){save(context,id,"","",false,language,"",files.get(0).getName(),durations(files),"",false,names(files));toast(context,"I couldn't hear speech in that one. The recording is still saved.");}
                else {
                    String text=organize(context,raw);
                    String summary=recap(context,text);
                    save(context,id,text,raw,!text.equals(raw),language,detected[0],files.get(0).getName(),durations(files),summary,false,names(files));
                }
                wrote=true;
            } catch(Exception error) { toast(context,offlineMessage(context,error)); }
            synchronized(Notes.class){draining=false;transcribing=-1;}
            poke();
            if(wrote)catchUp(context);
        });
    }

    private static void remember(Context context,long id,List<File> files,String lang,boolean pending) {
        try {
            JSONArray parts=names(files);
            if(parts.length()==0)return;
            save(context,id,"","",false,lang,"",files.get(0).getName(),durations(files),"",pending,parts);
        } catch(Exception ignored) {}
        poke();
    }

    private static String transcribeFiles(Context context,List<File> files,String language,String[] detected) throws Exception {
        StringBuilder raw=new StringBuilder();
        String lang=language!=null && language.equals("auto")?null:language;
        for(File file:files){
            try {
                String piece=new CloudApi(context).transcribe(file,true,null,lang,detected,"notes_transcribe_model");
                if((lang==null || "auto".equals(lang)) && LanguagePolicy.arabicMisread(piece)){
                    String[] again=new String[]{""};
                    String retry=new CloudApi(context).transcribe(file,true,null,"bn",again,"notes_transcribe_model");
                    if(retry!=null && !LanguagePolicy.wrongScript("bn",retry)){piece=retry;if(detected!=null && detected.length>0)detected[0]="bn";}
                }
                piece=piece==null?"":piece.trim();
                if(piece.isEmpty())continue;
                if(raw.length()>0)raw.append('\n');
                raw.append(piece);
            } catch(Exception error) {
                String message=error.getMessage()==null?"":error.getMessage();
                if(message.contains("unsure") || message.contains("No speech"))continue;
                throw error;
            }
        }
        return raw.toString().trim();
    }

    private static String recap(Context context,String text) {
        if(text==null || text.length()<80 || Vault.get(context).isEmpty())return "";
        try { return new CloudApi(context).noteSummary(text); } catch(Exception ignored) { return ""; }
    }

    private static List<File> filesOf(Context context,JSONObject note) {
        List<File> files=new ArrayList<>();
        JSONArray parts=note.optJSONArray("parts");
        if(parts!=null)for(int i=0;i<parts.length();i++)addFile(files,context,parts.optString(i));
        if(files.isEmpty())addFile(files,context,note.optString("audio"));
        return files;
    }

    private static void addFile(List<File> files,Context context,String name) {
        if(name==null || name.isEmpty())return;
        File file=new File(dir(context),name);
        if(file.isFile())files.add(file);
    }

    private static JSONArray names(List<File> files) {
        JSONArray parts=new JSONArray();
        for(File file:files)if(file!=null && file.isFile())parts.put(file.getName());
        return parts;
    }

    private static long durations(List<File> files){long total=0;for(File file:files)total+=audioDuration(file);return total;}

    private static boolean online(Context context) {
        try {
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return false;
            android.net.Network network=cm.getActiveNetwork();
            if(network==null)return false;
            android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(network);
            return caps!=null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch(RuntimeException e) { return false; }
    }

    private static String offlineMessage(Context context,Exception error) {
        if(!online(context))return "Saved on this phone. I'll write it up when you're online.";
        return error.getMessage()==null?"Couldn't write it up. The recording is still saved.":error.getMessage();
    }

    private static void holdMic(Context context,boolean on) {
        try { context.startService(new android.content.Intent(context,FlowAccessibility.class).setAction(on?"app.voiceflow.NOTE_ON":"app.voiceflow.NOTE_OFF")); }
        catch(RuntimeException ignored) {}
    }

    static void tagLatestCall(Context context,String who){
        if(who==null || who.trim().isEmpty())return;
        JSONArray notes=all(context);
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.optJSONObject(i);
            if(note!=null && note.optBoolean("call") && note.optString("who","").isEmpty()){stamp(context,note.optLong("id"),"who",who.trim());return;}
        }
    }

    private static void poke() {
        Activity activity=screen.get();
        if(activity==null || activity.isDestroyed())return;
        activity.runOnUiThread(()->{if(!activity.isDestroyed())onChange.run();});
    }

    private static void toast(Context context,String message) {
        if(message==null || message.isEmpty())return;
        new Handler(Looper.getMainLooper()).post(()->{Toast toast=Toast.makeText(context,message,Toast.LENGTH_LONG);toast.setGravity(android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL,0,Ui.dp(context,72));toast.show();});
    }

    /** A spoken translation. The recording stays playable, and the other language shows under it in the list. */
    static void keepTurn(Context context,File audio,String spoken,String spokenCode,String translation,String targetCode) {
        synchronized(Store.class){
        try {
            long id=System.currentTimeMillis();
            String audioName="";long duration=0;JSONArray parts=null;
            if(audio!=null && audio.isFile()){
                File dest=new File(dir(context),id+".m4a");
                try(FileInputStream in=new FileInputStream(audio);FileOutputStream out=new FileOutputStream(dest)){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
                }
                if(!dest.isFile() || dest.length()==0)dest.delete();
                else {audioName=dest.getName();duration=audioDuration(dest);parts=new JSONArray().put(audioName);}
            }
            JSONArray notes=all(context),next=new JSONArray();
            JSONObject item=new JSONObject().put("id",id).put("text",spoken==null?"":spoken).put("raw",spoken==null?"":spoken).put("touched",false)
                .put("lang",spokenCode==null?"auto":spokenCode).put("heard",spokenCode==null?"":spokenCode).put("audio",audioName).put("duration",duration)
                .put("created",id).put("summary","").put("pending",false).put("translation",translation==null?"":translation).put("translationLang",targetCode==null?"":targetCode);
            if(parts!=null)item.put("parts",parts);
            next.put(item);
            appendRetained(next,notes,id);
            Store.prefs(context).edit().putString("notes",next.toString()).apply();
        } catch(Exception ignored) {}
            }
    }

    private static void save(Context context,long id,String text,String raw,boolean touched,String lang,String heard,String audio,long duration,String summary,boolean pending,JSONArray parts) throws Exception {
        synchronized(Store.class){
        JSONArray notes=all(context),next=new JSONArray();
        JSONObject item=new JSONObject().put("id",id).put("text",text).put("raw",raw==null?"":raw).put("touched",touched).put("lang",lang).put("heard",heard==null?"":heard).put("audio",audio).put("duration",duration).put("created",id).put("summary",summary==null?"":summary).put("pending",pending);
        if(parts!=null)item.put("parts",parts);
        JSONObject previous=find(context,id);
        if(previous!=null){
            if(previous.has("wave"))item.put("wave",previous.optString("wave"));
            if(previous.has("translation"))item.put("translation",previous.optString("translation"));
            if(previous.has("translationLang"))item.put("translationLang",previous.optString("translationLang"));
            if(previous.optBoolean("call"))item.put("call",true);
            if(previous.optBoolean("thought")){item.put("thought",true);item.put("title",previous.optString("title"));item.put("revision",previous.optLong("revision",1));}
            if(previous.has("who"))item.put("who",previous.optString("who"));
        }
        if(id==callId)item.put("call",true);
        next.put(item);
        appendRetained(next,notes,id);
        Store.prefs(context).edit().putString("notes",next.toString()).apply();
            }
    }

    private static void remove(Activity activity,long id) {
        synchronized(Store.class){
        stopPlayback();
        JSONArray notes=all(activity),next=new JSONArray();
        for(int i=0;i<notes.length();i++){JSONObject old=notes.optJSONObject(i);if(old==null)continue;if(old.optLong("id")==id){for(File audio:filesOf(activity,old))if(audio.isFile())audio.delete();}else next.put(old);}
        Store.prefs(activity).edit().putString("notes",next.toString()).apply();
            }
    }

    private static void stamp(Context context,long id,String key,String value) {
        synchronized(Store.class){
        try {
            JSONArray notes=all(context);
            for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null && note.optLong("id")==id)note.put(key,value==null?"":value);}
            Store.prefs(context).edit().putString("notes",notes.toString()).apply();
        } catch(Exception ignored) {}
        poke();
            }
    }


    /** Ordinary voice notes keep their existing 100-entry policy; thoughts are never evicted by it. */
    private static void appendRetained(JSONArray next,JSONArray previous,long excludedId){
        int ordinary=0;
        for(int i=0;i<next.length();i++){JSONObject entry=next.optJSONObject(i);if(entry!=null && !entry.optBoolean("thought"))ordinary++;}
        for(int i=0;i<previous.length();i++){
            JSONObject entry=previous.optJSONObject(i);if(entry==null || entry.optLong("id")==excludedId)continue;
            if(entry.optBoolean("thought"))next.put(entry);else if(ordinary<100){next.put(entry);ordinary++;}
        }
    }

    /** Stored before optional cloud formatting. The original words never depend on its result. */
    static long keepThought(Context context,long requestedId,String raw,long duration) {
        synchronized(Store.class){
        if(raw==null || raw.trim().isEmpty())return -1;
        try {
            JSONArray notes=all(context),next=new JSONArray();
            long id=Store.nextId(notes,requestedId>0?requestedId:System.currentTimeMillis());
            String text=raw.trim();
            JSONObject thought=new JSONObject().put("id",id).put("created",id).put("thought",true)
                .put("text",text).put("raw",raw).put("title",QuickThoughtPolicy.title(text,"Quick thought"))
                .put("duration",Math.max(0,duration)).put("lang",Store.language(context)).put("heard",Store.language(context))
                .put("audio","").put("summary","").put("pending",false).put("revision",1L);
            next.put(thought);
            appendRetained(next,notes,-1);
            // Commit so the UI only says Saved when storage has accepted the words.
            if(!Store.prefs(context).edit().putString("notes",next.toString()).commit()){Store.prefs(context).edit().putString("notes",notes.toString()).commit();return -1;}
            poke();
            return id;
        } catch(Exception error){return -1;}
            }
    }

    static void formatThought(Context context,long id) {
        Context app=context.getApplicationContext();
        JSONObject captured=find(app,id);if(captured==null || !captured.optBoolean("thought"))return;
        final long revision=captured.optLong("revision",1);
        final String raw=captured.optString("raw");
        if(raw.isEmpty() || Vault.get(app).isEmpty())return;
        thoughtWork.incrementAndGet();
        work.execute(()->{
            try {
                String formatted=QuickThoughtPolicy.formatted(raw,new CloudApi(app).touchUp(raw));
                synchronized(Store.class){
                    JSONObject current=find(app,id);
                    // Deletion, a restore or an edit supersedes an in-flight response.
                    if(!QuickThoughtPolicy.canApply(raw,revision,current!=null,current!=null && current.optBoolean("thought"),current==null?"":current.optString("raw"),current==null?-1:current.optLong("revision",1)))return;
                    String before=Store.get(app,"notes","[]");JSONArray old=new JSONArray(before),next=new JSONArray();
                    for(int i=0;i<old.length();i++){
                        JSONObject note=old.optJSONObject(i);if(note==null)continue;
                        if(note.optLong("id")==id){note.put("text",formatted).put("title",QuickThoughtPolicy.title(formatted,"Quick thought")).put("revision",revision+1);}
                        next.put(note);
                    }
                    if(!Store.prefs(app).edit().putString("notes",next.toString()).commit())Store.prefs(app).edit().putString("notes",before).commit();
                }
                poke();
            } catch(Exception ignored){/* The original is already durable. */}
            finally{thoughtWork.decrementAndGet();}
        });
    }

    static boolean editThought(Context context,long id,String text) {
        synchronized(Store.class){
        if(text==null || text.trim().isEmpty())return false;
        try {
            JSONObject current=find(context,id);if(current==null || !current.optBoolean("thought"))return false;
            String before=Store.get(context,"notes","[]");JSONArray old=new JSONArray(before),next=new JSONArray();
            for(int i=0;i<old.length();i++){
                JSONObject note=old.optJSONObject(i);if(note==null)continue;
                if(note.optLong("id")==id)note.put("text",text.trim()).put("title",QuickThoughtPolicy.title(text,"Quick thought")).put("revision",note.optLong("revision",1)+1);
                next.put(note);
            }
            boolean saved=Store.prefs(context).edit().putString("notes",next.toString()).commit();if(saved)poke();else Store.prefs(context).edit().putString("notes",before).commit();return saved;
        } catch(Exception ignored){return false;}
            }
    }

    private static void thoughtList(Activity activity,LinearLayout body,Runnable refresh) {
        if(!Store.quickThoughts(activity))return;
        body.addView(Daybook.text(activity,"Ideas captured when no input is active.",13,Daybook.muted(activity),false));Ui.space(body,8);
        Button recordThought=Daybook.button(activity,"Record thought",true,v->{
            if(Dictation.isWorking() || VoiceTake.running() || Translate.going() || LineWire.running() || CaptureActivity.visible){toast(activity,"Finish the current recording first.");return;}
            if(!Store.quickThoughts(activity))return;
            activity.startActivity(new android.content.Intent(activity,CaptureActivity.class).putExtra("destination","thought"));
        });
        body.addView(recordThought,new LinearLayout.LayoutParams(-2,Ui.dp(activity,44)));Ui.space(body,14);
        JSONArray notes=all(activity);String bucket="";int shown=0;
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.optJSONObject(i);if(note==null || !note.optBoolean("thought"))continue;
            shown++;
            String group=bucketOf(note);
            if(!group.equals(bucket)){bucket=group;if(shown>1)Ui.space(body,8);body.addView(Daybook.text(activity,group,13,Daybook.muted(activity),true));Ui.space(body,6);}
            LinearLayout card=Daybook.card(activity);card.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,14),Ui.dp(activity,12));
            TextView title=Daybook.text(activity,titleOf(note),16,Daybook.ink(activity),false);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(title);Ui.space(card,4);
            card.addView(Daybook.text(activity,when(note),12,Daybook.muted(activity),false));Ui.space(card,8);
            TextView preview=Daybook.text(activity,note.optString("text"),14,Daybook.muted(activity),false);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(preview);
            long id=note.optLong("id");card.setOnClickListener(v->{Daybook.feedback(v);open=id;original=false;refresh.run();});body.addView(card);Ui.space(body,8);
        }
        if(shown==0){LinearLayout empty=Daybook.card(activity);empty.setPadding(Ui.dp(activity,16),Ui.dp(activity,18),Ui.dp(activity,16),Ui.dp(activity,18));empty.addView(Daybook.text(activity,"No thoughts yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"Record a thought here, or tap the edge dot outside VonoTation when no input is active. Your thought is saved here.",13,Daybook.muted(activity),false));body.addView(empty);}
    }

    private static void thoughtDetail(Activity activity,LinearLayout body,JSONObject note,Runnable refresh) {
        if(!Store.quickThoughts(activity)){back();return;}
        body.addView(Daybook.text(activity,titleOf(note),22,Daybook.ink(activity),true));Ui.space(body,4);body.addView(Daybook.text(activity,when(note),12,Daybook.muted(activity),false));Ui.space(body,16);
        body.addView(Daybook.text(activity,original?"Original transcript":"Your thought",12,Daybook.muted(activity),true));Ui.space(body,6);
        TextView words=Daybook.text(activity,note.optString(original?"raw":"text"),17,Daybook.ink(activity),false);words.setLineSpacing(Ui.dp(activity,4),1);words.setTextIsSelectable(true);body.addView(words);Ui.space(body,18);
        LinearLayout actions=Ui.row(activity);
        actions.addView(quiet(activity,original?"Organized":"Original",v->{original=!original;refresh.run();}));
        actions.addView(quiet(activity,"Edit",v->{
            android.widget.EditText input=new android.widget.EditText(activity);input.setText(note.optString("text"));input.setMinLines(3);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("Edit thought").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
            dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(editThought(activity,note.optLong("id"),input.getText().toString())){dialog.dismiss();refresh.run();}else input.setError("Add some text, or reopen this thought.");}));dialog.show();
        }));
        actions.addView(quiet(activity,"Delete",v->new android.app.AlertDialog.Builder(activity).setTitle("Delete this thought?").setMessage("Removes the thought and its original transcript from this phone.").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->{remove(activity,note.optLong("id"));back();refresh.run();}).show()));
        body.addView(actions);
    }

    private static final Runnable pulse=new Runnable(){
        public void run(){
            boolean again=false;
            if(VoiceTake.noteRunning() && !VoiceTake.callRunning() && liveDigits!=null && liveDigits.isAttachedToWindow()){roll(liveDigits,face(VoiceTake.elapsed()));if(liveMeter!=null)liveMeter.show(VoiceTake.liveBars(24),1f);again=true;}
            if(VoiceTake.callRunning()){
                if(liveTime!=null)liveTime.setText(clock(VoiceTake.elapsed()));
                if(liveWave!=null)liveWave.show(VoiceTake.liveBars(48),1f);
                again=true;
            }
            if(playing>=0 && player!=null){
                float p=totalDuration<=0?0:Math.min(1f,position()/(float)totalDuration);
                if(playWave!=null)playWave.show(null,p);
                if(playTime!=null)playTime.setText(clock(position())+" / "+clock(totalDuration));
                again=true;
            }
            if(again)tick.postDelayed(this,200);
        }
    };
    private static void armTick(){tick.removeCallbacks(pulse);tick.post(pulse);}
    private static long position(){long at=playedBefore;try{if(player!=null)at+=player.getCurrentPosition();}catch(Exception ignored){}return at;}
    private static void nudge(int delta){if(player==null)return;try{int next=player.getCurrentPosition()+delta;if(next<0)next=0;int dur=player.getDuration();if(dur>0 && next>dur)next=dur;player.seekTo(next);}catch(Exception ignored){}}

    static final class Wave extends android.view.View {
        private int[] bars=new int[]{1,2,3,2,1};
        private float progress;
        private int tone;
        private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Wave(Context context){super(context);setMinimumHeight(Ui.dp(context,40));}
        void tone(int color){tone=color;}
        void show(int[] next,float at){if(next!=null && next.length>0)bars=next;progress=at;invalidate();}
        @Override protected void onDraw(android.graphics.Canvas canvas){
            int w=getWidth(),h=getHeight(),n=Math.max(1,bars.length);
            float gap=Ui.dp(getContext(),2),bw=Math.max(2,(w-gap*(n-1))/n);
            int played=tone!=0?tone:Daybook.accent(getContext()),rest=tone!=0?((tone & 0x00ffffff)|0x66000000):Daybook.line(getContext());
            for(int i=0;i<n;i++){
                float x=i*(bw+gap);
                int level=i<bars.length?bars[i]:1;
                float bh=Math.max(Ui.dp(getContext(),4),h*(0.18f+0.82f*Math.max(0,Math.min(9,level))/9f));
                float top=(h-bh)/2f;
                paint.setColor((i+0.5f)/n<=progress?played:rest);
                canvas.drawRoundRect(x,top,x+bw,top+bh,bw/2f,bw/2f,paint);
            }
        }
    }
}
