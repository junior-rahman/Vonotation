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
    private static long thoughtSourceShown=-1;
    private static final java.util.Set<String> thoughtDaysOpen=new java.util.HashSet<>(),thoughtDaysClosed=new java.util.HashSet<>();
    private static final java.util.Set<Long> thoughtSummaryUpgrades=new java.util.HashSet<>();
    private static android.net.ConnectivityManager.NetworkCallback thoughtNetwork;
    private static android.content.SharedPreferences.OnSharedPreferenceChangeListener thoughtRetryPrefs;
    private static volatile Runnable thoughtRetryAction;
    private static volatile boolean busy;
    private static List<File> queue=new ArrayList<>();
    private static int queueAt;
    private static MediaPlayer player;
    private static boolean paused,loading,seeking;
    private static int playbackGeneration;
    private static float playbackSpeed=1f;
    private static android.widget.SeekBar playSeek;
    private static final java.util.concurrent.ExecutorService mediaWork=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static String search="";
    private static boolean trashVisible,pinnedOnly;
    private static int visibleLimit=60;
    private static long undoTrash=-1;
    private static long[] queueSpans=new long[0];
    private static long playing=-1,liveId=-1;
    private static volatile boolean draining;
    private static boolean translating;
    private static final java.util.concurrent.atomic.AtomicInteger thoughtWork=new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.HashSet<Long> recoveringThoughts=new java.util.HashSet<>();
    private static Wave liveWave,playWave;
    private static TextView liveTime,playTime;
    private static long totalDuration,playedBefore;
    private static final Handler tick=new Handler(Looper.getMainLooper());
    private static boolean callsOnly,thoughtsOnly;
    private static java.lang.ref.WeakReference<Activity> screen=new java.lang.ref.WeakReference<>(null);
    static void callsMode(boolean on){callsOnly=on;thoughtsOnly=false;}
    static void thoughtsMode(){callsOnly=false;thoughtsOnly=true;}
    static boolean hasThought(Context context,long id){synchronized(Store.class){JSONObject note=find(context,id);return note!=null && note.optBoolean("thought");}}
    static boolean thoughtOpened(Context context){JSONObject note=find(context,open);return note!=null && note.optBoolean("thought");}
    static boolean opened(){return open>=0 || trashVisible;}
    /** Updater gate includes queued thought formatting until its guarded save finishes. */
    static boolean updateBusy(){return busy || draining || transcribing>=0 || thoughtWork.get()>0;}
    private static Runnable onChange=()->{};
    private static final java.util.concurrent.ExecutorService work=java.util.concurrent.Executors.newSingleThreadExecutor();

    private Notes() {}

    static boolean back(){if(open<0){if(trashVisible){trashVisible=false;return true;}return false;}stopPlayback();fixing=false;original=false;translating=false;thoughtSourceShown=-1;open=-1;return true;}
    static void stop(){stopPlayback();}
    static void recordingChanged(){poke();}
    static boolean finishRecording(){return finishRecording(liveId);}
    static boolean finishRecording(long id){return id>0 && liveId==id && VoiceTake.finishNote(id);}
    /** Only the current explicit Cancel action may discard a voice note. */
    static boolean cancelRecording(Context context,long id){
        if(!AppProduct.isNotes() || id<=0 || liveId!=id || !VoiceTake.cancelNote(id))return false;
        liveId=-1;remove(context,id);poke();return true;
    }
    private static boolean ownsNote(JSONObject note){return note!=null && !note.optBoolean("trashed") && (AppProduct.isNotes()!=note.optBoolean("thought"));}
    static void resumeThoughtSummaries(Context context){if(!AppProduct.isNotes())ThoughtDailySummaries.resume(context);}
    static void pauseThoughtSummaries(){ThoughtDailySummaries.pause();}
    static void refreshThoughtSummaries(){if(ThoughtDailySummaries.active())poke();}

    static void show(Activity activity,LinearLayout body,Runnable refresh) { show(activity,body,null,refresh); }
    static void show(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        screen=new java.lang.ref.WeakReference<>(activity);onChange=refresh;
        catchUp(activity.getApplicationContext());
        if(AppProduct.isNotes()==thoughtsOnly)return;
        if(open>=0 && !ownsNote(find(activity,open)))back();
        if(thoughtsOnly && !Store.quickThoughts(activity))return;
        if(trashVisible && AppProduct.isNotes()){if(dock!=null)dock.removeAllViews();showTrash(activity,body,refresh);}else if(open<0)list(activity,body,dock,refresh);else detail(activity,body,refresh);
    }

    private static void list(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        if(thoughtsOnly){thoughtList(activity,body,refresh);return;}
        if(!callsOnly){
            body.addView(Daybook.text(activity,"Audio saves on this phone. Transcription needs a connection and provider key.",13,Daybook.muted(activity),false));
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
            String hint=VoiceTake.bothSides()?"Call audio capture is active. Listen back to check that both voices are audible.":"Microphone capture is active. Speakerphone may help; check both voices in playback.";
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
        if(AppProduct.isNotes()){
            android.widget.EditText query=Daybook.field(activity,search,"Search title or transcript",false);
            query.setSingleLine(true);query.setContentDescription("Search recordings");body.addView(query);
            LinearLayout options=Ui.row(activity);
            options.addView(quiet(activity,pinnedOnly?"All recordings":"Pinned",v->{pinnedOnly=!pinnedOnly;visibleLimit=60;refresh.run();}));
            options.addView(quiet(activity,"Recently deleted",v->{trashVisible=true;stopPlayback();refresh.run();}));body.addView(options);
            if(undoTrash>0){long restoreId=undoTrash;body.addView(Daybook.button(activity,"Undo last deletion",false,v->{if(restoreSaved(activity,restoreId)){undoTrash=-1;refresh.run();}}));}
            LinearLayout results=Ui.column(activity);body.addView(results);
            renderResults(activity,results,refresh);
            query.addTextChangedListener(new android.text.TextWatcher(){
                private Runnable pending;
                public void beforeTextChanged(CharSequence text,int start,int count,int after){}
                public void onTextChanged(CharSequence text,int start,int before,int count){search=text.toString();visibleLimit=60;if(pending!=null)tick.removeCallbacks(pending);pending=()->{if(results.isAttachedToWindow())renderResults(activity,results,refresh);};tick.postDelayed(pending,180);}
                public void afterTextChanged(android.text.Editable text){}
            });return;
        }
        renderResults(activity,body,refresh);
    }
    private static void renderResults(Activity activity,LinearLayout body,Runnable refresh){
        body.removeAllViews();JSONArray notes=all(activity);
        if(notes.length()==0 && !VoiceTake.running()){
            LinearLayout empty=Daybook.card(activity);empty.setPadding(Ui.dp(activity,16),Ui.dp(activity,18),Ui.dp(activity,16),Ui.dp(activity,18));
            if(callsOnly){empty.addView(Daybook.text(activity,"No calls yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"Enabled call capture depends on this phone. Check a short call in playback before relying on it.",13,Daybook.muted(activity),false));}
            else{empty.addView(Daybook.text(activity,"Nothing recorded yet.",18,Daybook.ink(activity),true));Ui.space(empty,7);empty.addView(Daybook.text(activity,"Tap Record. Audio saves on this phone; a connection and provider key are needed for transcription.",13,Daybook.muted(activity),false));}
            body.addView(empty);return;
        }
        String bucket="";
        int shown=0;
        List<JSONObject> matches=new ArrayList<>();
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.optJSONObject(i);if(note==null || note.optBoolean("trashed"))continue;
            if(note.optBoolean("thought") || (callsOnly?!note.optBoolean("call"):note.optBoolean("call")))continue;
            if(pinnedOnly&&!note.optBoolean("pinned"))continue;
            if(!NotebookPolicy.matches(search,titleOf(note),note.optString("text"),note.optString("raw"),note.optString("who")))continue;
            matches.add(note);
        }
        matches.sort((a,b)->{int pinned=Boolean.compare(b.optBoolean("pinned"),a.optBoolean("pinned"));return pinned!=0?pinned:Long.compare(b.optLong("created",b.optLong("id")),a.optLong("created",a.optLong("id")));});
        boolean keyed=!Vault.get(activity).isEmpty(),connected=online(activity);
        for(int i=0;i<Math.min(visibleLimit,matches.size());i++){
            JSONObject note=matches.get(i);shown++;
            String group=note.optBoolean("pinned")?"Pinned":bucketOf(note);
            if(!group.equals(bucket)){bucket=group;if(i>0)Ui.space(body,8);body.addView(Daybook.text(activity,group,13,Daybook.muted(activity),true));Ui.space(body,6);}
            long id=note.optLong("id");
            LinearLayout card=Daybook.card(activity);card.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,12),Ui.dp(activity,12));
            card.setClipToOutline(true);
            LinearLayout row=Ui.row(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout words=Ui.column(activity);
            boolean coming=transcribing==id;
            TextView line=Daybook.text(activity,titleOf(note),16,coming?Daybook.muted(activity):Daybook.ink(activity),false);
            line.setMaxLines(2);line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(line);
            Ui.space(words,4);
            String who=note.optString("who","");
            String kind=note.optBoolean("call")?(who.isEmpty()?"Call":who):"";
            String meta=when(note)+" · "+clock(note.optLong("duration"))+(kind.isEmpty()?"":" · "+kind)+" · "+name(note.optString("lang","auto"));
            TextView metaLine=Daybook.text(activity,meta,12,Daybook.muted(activity),false);
            metaLine.setMaxLines(1);metaLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
            words.addView(metaLine);
            TextView state=Daybook.text(activity,status(activity,note,keyed,connected),12,Daybook.muted(activity),false);
            VocadingIcons.attach(state,coming?"transcribing":note.optBoolean("recording")?"recording":note.optBoolean("pending")||note.optBoolean("source_interrupted")?"retry":"saved",0);words.addView(state);
            row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            boolean on=playing==id&&!paused;
            Button play=Daybook.button(activity,on?"Pause":"Play",false,v->togglePlay(activity,note,refresh));
            if(filesOf(activity,note).isEmpty())play.setEnabled(false);
            LinearLayout.LayoutParams playLp=new LinearLayout.LayoutParams(Ui.dp(activity,100),Ui.dp(activity,48));playLp.leftMargin=Ui.dp(activity,8);
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
        if(matches.size()>visibleLimit)body.addView(Daybook.button(activity,"Show more recordings",false,v->{visibleLimit+=60;renderResults(activity,body,refresh);}));
        if(shown==0 && (!search.trim().isEmpty()||pinnedOnly)){body.addView(Daybook.text(activity,"No matching recordings.",16,Daybook.muted(activity),false));return;}
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
        boolean on=playing==id&&!paused;
        body.addView(Daybook.text(activity,titleOf(note),22,Daybook.ink(activity),true));
        Ui.space(body,4);
        body.addView(Daybook.text(activity,(note.optBoolean("call")?"Call · ":"")+when(note)+" · "+name(note.optString("heard","").isEmpty()?note.optString("lang","auto"):note.optString("heard","")),12,Daybook.muted(activity),false));
        Ui.space(body,8);
        body.addView(Daybook.text(activity,status(activity,note,!Vault.get(activity).isEmpty(),online(activity)),13,Daybook.muted(activity),true));
        Ui.space(body,12);
        playTime=Daybook.text(activity,(playing==id?clock(position()):"0:00")+" / "+clock(note.optLong("duration")),16,Daybook.ink(activity),true);
        body.addView(playTime);
        Ui.space(body,8);
        playWave=new Wave(activity);
        playWave.show(barsOf(note.optString("wave")),playing==id && totalDuration>0?position()/(float)totalDuration:0);
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
        playSeek=new android.widget.SeekBar(activity);playSeek.setMax(1000);playSeek.setContentDescription("Playback position");
        playSeek.setProgress(playing==id&&totalDuration>0?(int)(1000*position()/totalDuration):0);playSeek.setEnabled(playing==id&&!loading);
        playSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(android.widget.SeekBar bar){seeking=true;}
            public void onStopTrackingTouch(android.widget.SeekBar bar){seeking=false;seekTo(totalDuration*bar.getProgress()/1000);}
            public void onProgressChanged(android.widget.SeekBar bar,int progress,boolean user){if(user&&playTime!=null)playTime.setText(clock(totalDuration*progress/1000)+" / "+clock(totalDuration));}
        });body.addView(playSeek,new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));
        body.addView(quiet(activity,"Speed · "+playbackSpeed+"×",v->new android.app.AlertDialog.Builder(activity).setTitle("Playback speed").setItems(new String[]{"0.75×","1×","1.25×","1.5×","2×"},(dialog,index)->{playbackSpeed=new float[]{.75f,1f,1.25f,1.5f,2f}[index];applySpeed();refresh.run();}).show()));
        if(loading&&playing==id)body.addView(Daybook.text(activity,"Loading audio…",13,Daybook.muted(activity),false));
        Ui.space(body,16);
        String summary=note.optString("summary","");
        if(!coming && !summary.isEmpty()){
            TextView lead=Daybook.text(activity,summary,16,Daybook.ink(activity),false);
            lead.setLineSpacing(Ui.dp(activity,3),1);lead.setTextIsSelectable(true);body.addView(lead);
            Ui.space(body,14);
        }
        if(!coming&&note.optBoolean("partial_transcript")){body.addView(Daybook.text(activity,note.optBoolean("replacement_pending")?"Replacement incomplete · your previous transcript is kept.":"Partial transcript · retry to fill in missing source segments.",13,Daybook.muted(activity),false));Ui.space(body,6);}
        String shown=coming?"Writing it up…":note.optBoolean("pending")&&note.optString("text").isEmpty()?status(activity,note,!Vault.get(activity).isEmpty(),online(activity))+". Use More → Retry transcription when ready.":note.optString("text","").isEmpty()?"The words will show up here.":note.optString("text");
        if(!coming && !note.optBoolean("pending") && !note.optString("text","").isEmpty())body.addView(Daybook.text(activity,"Everything you said",12,Daybook.muted(activity),true));
        Ui.space(body,4);
        TextView transcript=Daybook.text(activity,shown,17,note.optString("text","").isEmpty()?Daybook.muted(activity):Daybook.ink(activity),false);
        transcript.setLineSpacing(Ui.dp(activity,4),1);transcript.setTextIsSelectable(!coming && !note.optString("text","").isEmpty());
        body.addView(transcript);
        String issue=note.optString("transcription_error",note.optString("capture_error"));if(!coming&&!issue.isEmpty()){Ui.space(body,8);body.addView(Daybook.text(activity,issue,13,Daybook.muted(activity),false));}
        String translation=note.optString("translation","");
        if(!translation.isEmpty()){
            Ui.space(body,14);
            body.addView(Daybook.text(activity,"Translation · "+name(note.optString("translationLang","")),12,Daybook.muted(activity),true));
            Ui.space(body,4);
            TextView other=Daybook.text(activity,translation,16,Daybook.ink(activity),false);other.setTextIsSelectable(true);body.addView(other);
        }
        Ui.space(body,18);
        LinearLayout actions=Ui.row(activity);actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        actions.addView(quiet(activity,"Edit",v->editDialog(activity,note,refresh)));
        actions.addView(quiet(activity,"Copy",v->{String text=shareText(note);((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(titleOf(note),text));toast(activity,"Copied");}));
        actions.addView(quiet(activity,"Share",v->new android.app.AlertDialog.Builder(activity).setTitle("Share recording").setItems(new String[]{"Text","Source audio"},(d,which)->{if(which==0){android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,shareText(note));activity.startActivity(android.content.Intent.createChooser(send,"Share text"));}else shareAudio(activity,note);}).show()));
        actions.addView(quiet(activity,"More",v->new android.app.AlertDialog.Builder(activity).setTitle("Recording actions").setItems(new String[]{note.optBoolean("pinned")?"Unpin":"Pin","Retry transcription","Summary","Translate","Language","Move to Recently deleted"},(d,which)->{
            if(which==0){togglePin(activity,id);refresh.run();}
            if(which==1){if(note.optBoolean("user_edited"))new android.app.AlertDialog.Builder(activity).setTitle("Transcribe original audio again?").setMessage("Your edited transcript stays unchanged. The original transcription may be updated.").setNegativeButton("Cancel",null).setPositiveButton("Transcribe",(dd,w)->retranscribe(activity,note,note.optString("lang","auto"),refresh)).show();else if(note.optBoolean("pending")){resetRetry(activity,id);writeUp(activity.getApplicationContext(),id);}else retranscribe(activity,note,note.optString("lang","auto"),refresh);}
            if(which==2)summarize(activity,note);
            if(which==3){translating=!translating;fixing=false;refresh.run();}
            if(which==4){fixing=!fixing;translating=false;refresh.run();}
            if(which==5)confirmDelete(activity,note,refresh);
        }).show()));body.addView(actions);
        if(translating){Ui.space(body,8);languageChoices(activity,body,note.optString("translationLang",""),code->{translating=false;translateNote(activity,note,code);});}
        if(fixing){Ui.space(body,8);languageChoices(activity,body,note.optString("lang","auto"),code->retranscribe(activity,note,code,refresh));}
    }

    private static String status(Context context,JSONObject note,boolean keyed,boolean connected){
        return NotebookPolicy.status(note.optBoolean("recording"),liveId==note.optLong("id")&&VoiceTake.notePaused(),!filesOf(context,note).isEmpty(),note.optBoolean("source_interrupted")||!note.optString("capture_error").isEmpty(),transcribing==note.optLong("id"),note.optBoolean("partial_transcript"),!note.optString("transcription_error").isEmpty(),note.optBoolean("pending"),keyed,connected);
    }
    private static String shareText(JSONObject note){return titleOf(note)+"\n\n"+(note.optString("summary").isEmpty()?"":note.optString("summary")+"\n\n")+note.optString("text");}
    static boolean editSaved(Context context,long id,String title,String transcript){
        synchronized(Store.class){try{JSONObject current=find(context,id);if(!ownsNote(current)||current.optBoolean("recording"))return false;
            return updateCaptured(context,current,item->{String clean=title.trim();item.put("title",clean.substring(0,Math.min(300,clean.length())));if(!transcript.equals(item.optString("text"))){item.put("text",transcript).put("user_edited",true).put("pending",false).put("summary","").put("translation","").put("transcription_error","");}});
        }catch(Exception error){return false;}}
    }
    private static void editDialog(Activity activity,JSONObject note,Runnable refresh){
        if(note.optBoolean("recording")){toast(activity,"Finish recording before editing.");return;}
        LinearLayout fields=Ui.column(activity);int pad=Ui.dp(activity,20);fields.setPadding(pad,pad,pad,pad);
        android.widget.EditText title=Daybook.field(activity,titleOf(note),"Title",false);title.setSingleLine(true);fields.addView(title);
        android.widget.EditText text=Daybook.field(activity,note.optString("text"),"Transcript",false);text.setMinLines(4);text.setMaxLines(9);fields.addView(text);
        fields.addView(Daybook.text(activity,"Original transcript and source audio are kept.",13,Daybook.muted(activity),false));
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(fields);
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("Edit recording").setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(editSaved(activity,note.optLong("id"),title.getText().toString(),text.getText().toString())){dialog.dismiss();refresh.run();}else text.setError("Could not save. The recording may have changed or storage is full.");}));dialog.show();
    }
    static boolean togglePin(Context context,long id){synchronized(Store.class){try{JSONObject current=find(context,id);return ownsNote(current)&&updateCaptured(context,current,item->item.put("pinned",!item.optBoolean("pinned")));}catch(Exception error){return false;}}}
    static boolean trashSaved(Context context,long id){synchronized(Store.class){try{JSONObject current=find(context,id);if(!ownsNote(current)||current.optBoolean("recording"))return false;boolean saved=updateCaptured(context,current,item->item.put("trashed",true).put("trashed_at",System.currentTimeMillis()));if(saved&&playing==id)stopPlayback();return saved;}catch(Exception error){return false;}}}
    static boolean restoreSaved(Context context,long id){synchronized(Store.class){try{JSONArray before=all(context),next=new JSONArray(before.toString());boolean found=false;for(int i=0;i<next.length();i++){JSONObject item=next.optJSONObject(i);if(item!=null&&item.optLong("id")==id&&item.optBoolean("trashed")){item.remove("trashed");item.remove("trashed_at");item.put("revision",item.optLong("revision",1)+1);found=true;}}return found&&commitNotes(context,before,next);}catch(Exception error){return false;}}}
    private static void showTrash(Activity activity,LinearLayout body,Runnable refresh){
        body.addView(Daybook.button(activity,"Back to recordings",false,v->{trashVisible=false;refresh.run();}));
        body.addView(Daybook.text(activity,"Recently deleted",22,Daybook.ink(activity),true));
        body.addView(Daybook.text(activity,"Kept until you restore or permanently delete. These recordings still use phone storage.",13,Daybook.muted(activity),false));
        JSONArray all=all(activity);int count=0;
        for(int i=0;i<all.length();i++){JSONObject note=all.optJSONObject(i);if(note==null||!note.optBoolean("trashed")||note.optBoolean("thought"))continue;count++;long id=note.optLong("id");
            LinearLayout row=Daybook.card(activity);row.addView(Daybook.text(activity,titleOf(note),16,Daybook.ink(activity),true));
            row.addView(Daybook.button(activity,"Restore",false,v->{if(restoreSaved(activity,id)){if(undoTrash==id)undoTrash=-1;refresh.run();}else toast(activity,"Could not restore. Check available storage.");}));
            row.addView(Daybook.button(activity,"Delete permanently",false,v->new android.app.AlertDialog.Builder(activity).setTitle("Delete permanently?").setMessage("This removes the text and source audio. It cannot be undone.").setNegativeButton("Keep",null).setPositiveButton("Delete permanently",(d,w)->{remove(activity,id);refresh.run();}).show()));body.addView(row);Ui.space(body,8);
        }
        if(count==0)body.addView(Daybook.text(activity,"No deleted recordings.",16,Daybook.muted(activity),false));
    }
    private static void shareAudio(Activity activity,JSONObject note){
        if(note.optBoolean("recording")){toast(activity,"Finish recording before sharing.");return;}
        toast(activity,"Preparing source audio…");Context app=activity.getApplicationContext();long id=note.optLong("id");
        mediaWork.execute(()->{try{List<File> sources;
            synchronized(Store.class){JSONObject current=find(app,id);if(!ownsNote(current))throw new java.io.IOException("The recording is no longer available.");List<File> files=filesOf(app,current);JSONArray declared=current.optJSONArray("parts");if(declared!=null&&declared.length()>files.size())throw new java.io.IOException("A source segment is missing. No incomplete copy was shared.");sources=new ArrayList<>(files);}
            android.net.Uri uri=RecordingShare.prepare(app,sources);
            synchronized(Store.class){if(!ownsNote(find(app,id)))throw new java.io.IOException("The recording is no longer available.");}
            activity.runOnUiThread(()->{if(activity.isDestroyed()||activity.isFinishing())return;android.content.Intent send=new android.content.Intent(android.content.Intent.ACTION_SEND).setType(uri.getLastPathSegment().endsWith(".zip")?"application/zip":"audio/mp4").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(android.content.ClipData.newRawUri("Source audio",uri));activity.startActivity(android.content.Intent.createChooser(send,"Share source audio"));});
        }catch(Exception error){toast(app,error.getMessage()==null?"Could not prepare source audio.":error.getMessage());}});
    }

    private static TextView quiet(Activity activity,String label,android.view.View.OnClickListener click) {
        TextView word=Daybook.text(activity,label,13,Daybook.accent(activity),true);
        word.setPadding(0,Ui.dp(activity,8),Ui.dp(activity,14),Ui.dp(activity,8));word.setMinHeight(Ui.dp(activity,48));word.setGravity(android.view.Gravity.CENTER_VERTICAL);
        if(!label.equals("Copy")&&!label.equals("Share"))VocadingIcons.label(word,label,Daybook.accent(activity));
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
        if(!AppProduct.isNotes())return;
        if(Translate.going()){toast(activity,"Translation is still going. Finish it first.");return;}
        if(VoiceTake.callRunning() || LineWire.running()){toast(activity,"A call is being recorded. Wait until it ends.");return;}
        if(VoiceTake.thoughtRunning()){toast(activity,"A Quick Thought is recording. Finish it with the floating control first.");return;}
        if(VoiceTake.noteRunning()){
            long id=liveId;
            if(!VoiceTake.finishNote(id))return;
            NoteRecordingService.finalizing(activity,id);
            if(liveDigits!=null){liveDigits.animate().cancel();liveDigits.setTranslationY(0);liveDigits.setAlpha(1f);liveDigits.setTextSize(16);liveDigits.setText("Saving…");shownFace="";}
            if(liveMeter!=null)liveMeter.setVisibility(android.view.View.GONE);
            if(beat!=null){beat.cancel();beat=null;}
            return;
        }
        if(busy || NoteRecordingService.busy())return;
        choosing=false;
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},22);return;}
        stopPlayback();
        Context app=activity.getApplicationContext();
        long id; synchronized(Store.class){id=Store.nextId(all(app),System.currentTimeMillis());while(noteDeleted(app,id))id=Store.nextId(all(app),id+1);}
        final long captureId=id;
        final String language=pick;
        liveId=captureId;
        // Starting the microphone is owned by the foreground Service. A wake lock
        // or an accessibility service is not a substitute for mic FGS eligibility.
        NoteRecordingService.begin(activity,captureId,()->{
            if(liveId!=captureId){NoteRecordingService.completed(app,captureId);return;}
            VoiceTake.beginNote(app,new VoiceTake.Listener(){
                public void listening(){if(liveDigits!=null && liveDigits.isAttachedToWindow())armTick();poke();}
                public void failed(String message){
                    if(liveId==captureId)liveId=-1;
                    markRecordingFailed(app,captureId,message);
                    NoteRecordingService.completed(app,captureId);toast(app,message);poke();
                }
                public void finished(File file){}
                public void partReady(List<File> files){remember(app,captureId,files,language,true);}
                public void finishedAll(List<File> files,boolean captureComplete){
                    try {
                        if(liveId==captureId)liveId=-1;
                        remember(app,captureId,files,language,true);
                        stamp(app,captureId,"wave",barsText(VoiceTake.bars()));
                        if(!captureComplete)stamp(app,captureId,"capture_error","Recording ended before every audio segment could be finalized.");
                        toast(app,captureComplete?(online(app)?"Saved. Writing it up.":"Saved on this phone. I'll write it up when you're online."):"The recording was interrupted. Surviving source audio is saved.");
                    } finally { NoteRecordingService.completed(app,captureId); }
                    writeUp(app,captureId);
                }
            },dir(app),captureId);
            if(VoiceTake.noteRunning()){
                // A process death before the first three-minute segment must
                // still leave a row from which its source audio can be recovered.
                try{String source=captureId+"-0.m4a";save(app,captureId,"","",false,language,"",source,0,"",true,new JSONArray().put(source));if(find(app,captureId)==null)throw new java.io.IOException("The recording recovery record was not committed.");}
                catch(Exception failure){VoiceTake.finishNote(captureId);NoteRecordingService.finalizing(app,captureId);toast(app,"Recording started, but its recovery record could not be saved. Finishing safely.");}
            }
            if(dockHost!=null && VoiceTake.noteRunning() && !VoiceTake.callRunning()){dockHost.removeAllViews();dockHost.addView(actionPill(activity,refresh),new android.widget.FrameLayout.LayoutParams(-2,-2));armTick();}
            else refresh.run();
        },message->{if(liveId==captureId)liveId=-1;toast(app,message);poke();});
    }
    private static void markRecordingFailed(Context context,long id,String message){
        synchronized(Store.class){try{JSONObject captured=find(context,id);if(!ownsNote(captured))return;updateCaptured(context,captured,item->item.put("recording",false).put("pending",!filesOf(context,item).isEmpty()).put("source_interrupted",true).put("capture_error",message==null?"The microphone could not finish this recording.":message));}catch(Exception ignored){}}
    }

    private static android.widget.FrameLayout dockHost;
    private static Runnable onRefresh;
    private static TextView liveDigits;
    private static Wave liveMeter;
    private static android.animation.ObjectAnimator beat;
    private static String shownFace="";
    private static android.view.View actionPill(Activity activity,Runnable refresh){
        boolean timing=VoiceTake.noteRunning() && !VoiceTake.callRunning();
        boolean held=!timing && (Translate.going() || VoiceTake.callRunning() || VoiceTake.thoughtRunning() || LineWire.running());
        LinearLayout pill=Ui.column(activity);
        pill.setBackground(Daybook.ripple(activity,Daybook.action(activity),26));
        pill.setElevation(Ui.dp(activity,8));
        pill.setMinimumWidth(Ui.dp(activity,168));
        pill.setPadding(Ui.dp(activity,22),Ui.dp(activity,10),Ui.dp(activity,22),Ui.dp(activity,10));
        if(held)pill.setAlpha(0.4f);
        if(!timing){
            liveDigits=null;liveMeter=null;shownFace="";
            if(beat!=null){beat.cancel();beat=null;}
            TextView label=Daybook.text(activity,(busy||NoteRecordingService.busy())?"Saving…":"Record",16,Daybook.actionInk(activity),true);
            label.setGravity(android.view.Gravity.CENTER);
            label.setMinHeight(Ui.dp(activity,32));
            VocadingIcons.attach(label,(busy||NoteRecordingService.busy())?"saving":held?"disabled":activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED?"mic-blocked":"ready",0);
            pill.addView(label);
            pill.setOnClickListener(v->{Daybook.feedback(v);record(activity,refresh);});
            pill.setContentDescription((busy||NoteRecordingService.busy())?"Saving":"Record");pill.setEnabled(!busy&&!NoteRecordingService.busy()&&!held);
            return pill;
        }
        TextView title=Daybook.text(activity,VoiceTake.notePaused()?"Paused":"Recording",12,Daybook.actionInk(activity),true);
        title.setGravity(android.view.Gravity.CENTER);
        VocadingIcons.attach(title,VoiceTake.notePaused()?"pause":"recording",0);pill.addView(title);
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
        beat.setDuration(700);beat.setRepeatMode(android.animation.ValueAnimator.REVERSE);beat.setRepeatCount(android.animation.ValueAnimator.INFINITE);if(!VoiceTake.notePaused())beat.start();
        liveDigits=Daybook.text(activity,face(VoiceTake.elapsed()),22,Daybook.actionInk(activity),true);
        liveDigits.setTypeface(android.graphics.Typeface.MONOSPACE,android.graphics.Typeface.BOLD);
        liveDigits.setGravity(android.view.Gravity.CENTER);
        shownFace=face(VoiceTake.elapsed());
        row.addView(liveDigits);
        pill.addView(row);
        Ui.space(pill,6);
        liveMeter=new Wave(activity);liveMeter.tone(Daybook.actionInk(activity));liveMeter.show(VoiceTake.liveBars(24),1f);
        pill.addView(liveMeter,new LinearLayout.LayoutParams(Ui.dp(activity,148),Ui.dp(activity,28)));
        LinearLayout controls=Ui.row(activity);controls.setGravity(android.view.Gravity.CENTER);
        Button pause=Daybook.button(activity,VoiceTake.notePaused()?"Resume":"Pause",false,v->{Daybook.feedback(v);if(NoteRecordingService.togglePause(activity,liveId,Notes::recordingChanged))v.setEnabled(false);});
        pause.setEnabled(!VoiceTake.pauseChanging());controls.addView(pause,new LinearLayout.LayoutParams(Ui.dp(activity,86),Ui.dp(activity,48)));
        Button finish=Daybook.button(activity,"Finish",false,v->{Daybook.feedback(v);record(activity,refresh);});controls.addView(finish,new LinearLayout.LayoutParams(Ui.dp(activity,86),Ui.dp(activity,48)));pill.addView(controls);
        pill.setContentDescription(VoiceTake.notePaused()?"Recording paused":"Recording in progress");
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

    /** Started by the call watcher; each source path stays bound to the owned call id. */
    static void beginCall(Context context) { beginCall(context,System.currentTimeMillis()); }
    static boolean beginCall(Context context,long id) {
        if(!AppProduct.isNotes())return false;
        if(NoteRecordingService.busy())return false;
        if(VoiceTake.running() || VoiceTake.updateBusy())return false;
        if(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){toast(context,"Allow microphone access first.");return false;}
        liveId=id;callId=id;
        if(!keepCallFiles(context,id,new ArrayList<>(),false))return false;
        VoiceTake.beginCall(context,new VoiceTake.Listener(){
            public void listening(){poke();}
            public void failed(String message){liveId=-1;keepCallFiles(context,id,new ArrayList<>(),true);toast(context,message);poke();}
            public void finished(File file){}
            public void partReady(List<File> files){keepCallFiles(context,id,files,false);}
            public void finishedAll(List<File> files,boolean captureComplete){liveId=-1;keepCallFiles(context,id,files,true);stamp(context,id,"wave",barsText(VoiceTake.bars()));if(!captureComplete)stamp(context,id,"capture_error","Recording ended before every audio segment could be finalized.");toast(context,captureComplete?"Call saved. Writing it up.":"The call recording was interrupted. Surviving source audio is saved.");}
        },dir(context),id);
        return VoiceTake.callRunning();
    }

    /** Merge sealed wireless and microphone sources without resetting text or changing call identity. */
    static boolean keepCallFiles(Context context,long id,List<File> sources,boolean complete){
        synchronized(Store.class){try{
            if(id<=0 || noteDeleted(context,id))return false;
            JSONArray previous=all(context);JSONObject old=find(context,id);
            if(old!=null && (!old.optBoolean("call") || old.optBoolean("thought")))return false;
            List<File> files=old==null?new ArrayList<>():filesOf(context,old);
            if(sources!=null)for(File file:sources)if(file!=null&&file.isFile()&&file.length()>0&&!files.contains(file))files.add(file);
            if(old!=null&&old.optBoolean("recording")==!complete&&String.valueOf(old.opt("parts")).equals(names(files).toString()))return true;
            JSONObject item=old==null?new JSONObject().put("id",id).put("created",id).put("call",true).put("text","").put("raw","").put("summary","").put("lang","auto").put("heard",""):new JSONObject(old.toString());
            item.put("audio",files.isEmpty()?"":files.get(0).getName()).put("parts",names(files)).put("duration",Math.max(item.optLong("duration"),durations(files))).put("recording",!complete).put("pending",complete&&!files.isEmpty()).put("retry_after",0).put("revision",old==null?1:old.optLong("revision",1)+1);
            JSONArray next=new JSONArray().put(item);appendRetained(next,previous,id);
            if(!commitNotes(context,previous,next))return false;
        }catch(Exception unavailable){return false;}}
        poke();if(complete)writeUp(context,id);return true;
    }
    static void tagCall(Context context,long id,String who){
        if(who==null||who.trim().isEmpty())return;
        synchronized(Store.class){JSONObject note=find(context,id);if(note!=null&&note.optBoolean("call"))stamp(context,id,"who",who.trim());}
    }
    static void keepFile(Context context,File audio) {
        if(audio==null||!audio.isFile()||audio.length()<1000)return;
        long id;try{id=Long.parseLong(audio.getName().substring(0,audio.getName().indexOf('-')));}catch(Exception invalid){id=System.currentTimeMillis();}
        keepCallFiles(context,id,java.util.Collections.singletonList(audio),true);
    }

    private static void retranscribe(Activity activity,JSONObject note,String code,Runnable refresh) {
        if(busy||draining)return;
        if(filesOf(activity,note).isEmpty()){Toast.makeText(activity,"The recording is missing.",Toast.LENGTH_SHORT).show();return;}
        fixing=false;busy=true;transcribing=note.optLong("id");refresh.run();
        Context app=activity.getApplicationContext();
        work.execute(()->{
            try {
                JSONObject source=new JSONObject(note.toString());source.remove("segment_transcripts");
                Transcription result=transcribeNote(app,source,code);result.replacement=true;
                String text=organize(app,result.raw),summary=recap(app,text);
                updateCaptured(app,note,item->{applyTranscription(item,result,code,text,summary);});
            }catch(Exception error){toast(app,offlineMessage(app,error));}
            finally{busy=false;transcribing=-1;poke();catchUp(app);}
        });
    }

    private static void confirmDelete(Activity activity,JSONObject note,Runnable refresh) {
        if(note.optBoolean("recording")){toast(activity,"Finish recording before deleting.");return;}
        new android.app.AlertDialog.Builder(activity).setTitle("Move to Recently deleted?").setMessage("Text and source audio are kept until you restore or permanently delete them.").setNegativeButton("Keep",null).setPositiveButton("Move",(d,w)->{if(trashSaved(activity,note.optLong("id"))){undoTrash=note.optLong("id");back();refresh.run();}else toast(activity,"Could not save deletion. The recording was kept.");}).show();
    }

    private static void togglePlay(Activity activity,JSONObject note,Runnable refresh) {
        long id=note.optLong("id");
        if(VoiceTake.updateBusy()||LineWire.updateBusy()){toast(activity,"Finish recording before playing audio.");return;}
        if(playing==id&&loading){paused=!paused;refresh.run();return;}
        if(playing==id&&player!=null){
            try{if(paused){player.start();paused=false;applySpeed();PlayWatch.show(activity.getApplicationContext(),titleOf(note));armTick();}else{player.pause();paused=true;PlayWatch.hide();}refresh.run();return;}catch(RuntimeException failed){stopPlayback();}
        }
        stopPlayback();List<File> files=filesOf(activity,note);
        if(files.isEmpty()){toast(activity,"The recording is missing.");return;}
        final int generation=playbackGeneration;playing=id;paused=false;loading=true;queue=new ArrayList<>(files);refresh.run();
        mediaWork.execute(()->{
            long[] spans=new long[files.size()];long total=0;for(int i=0;i<files.size();i++){spans[i]=Math.max(0,audioDuration(files.get(i)));total+=spans[i];}
            final long duration=total;
            tick.post(()->{if(generation!=playbackGeneration||playing!=id)return;
                if(duration<=0){stopPlayback();toast(activity,"The source audio could not be read.");poke();return;}
                queueSpans=spans;totalDuration=duration;queueAt=0;playedBefore=0;playQueue(activity,refresh,0);armTick();
            });
        });
    }
    private static void playQueue(Activity activity,Runnable refresh,int seek) {
        if(queue==null || queueAt>=queue.size()){stopPlayback();poke();return;}
        final int generation=playbackGeneration;loading=true;
        try {
            MediaPlayer next=new MediaPlayer();player=next;
            next.setAudioAttributes(new android.media.AudioAttributes.Builder().setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(android.media.AudioAttributes.USAGE_MEDIA).build());
            next.setDataSource(queue.get(queueAt).getAbsolutePath());
            next.setOnPreparedListener(mp->{
                if(player!=mp||generation!=playbackGeneration)return;loading=false;
                int span=Math.max(0,mp.getDuration());if(queueAt<queueSpans.length)queueSpans[queueAt]=span;
                if(seek>0)mp.seekTo(Math.min(seek,Math.max(0,span-1)));
                applySpeed();if(!paused){mp.start();JSONObject current=find(activity,playing);PlayWatch.show(activity.getApplicationContext(),current==null?"Recording":titleOf(current));armTick();}poke();
            });
            next.setOnErrorListener((mp,what,extra)->{if(player==mp&&generation==playbackGeneration){stopPlayback();toast(activity,"Could not play this audio segment. Your source is kept.");poke();}return true;});
            next.setOnCompletionListener(mp->{if(player!=mp||generation!=playbackGeneration)return;playedBefore+=queueAt<queueSpans.length?queueSpans[queueAt]:0;mp.release();player=null;queueAt++;playQueue(activity,refresh,0);});
            next.prepareAsync();
        }catch(Exception error){stopPlayback();toast(activity,"Couldn't play that recording.");poke();}
    }
    private static void applySpeed(){if(player==null||loading)return;try{player.setPlaybackParams(new android.media.PlaybackParams().setSpeed(playbackSpeed).setPitch(1f));if(paused)player.pause();}catch(RuntimeException unavailable){Activity visible=screen.get();if(visible!=null)toast(visible,"This audio does not support that speed.");}}
    private static void stopPlayback(){playbackGeneration++;playing=-1;paused=false;loading=false;seeking=false;playSeek=null;queueSpans=new long[0];queue=new ArrayList<>();totalDuration=0;playedBefore=0;if(player!=null){try{player.release();}catch(Exception ignored){}player=null;}PlayWatch.hide();}
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
                updateCaptured(activity,note,item->{item.put("text",text).put("raw",source).put("touched",!text.equals(source));});
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
        if(!note.optString("title").trim().isEmpty())return note.optString("title").trim();
        if(note.optBoolean("thought")){String saved=note.optString("title","").trim();return saved.isEmpty()?QuickThoughtPolicy.title(thoughtBody(note),"Quick thought"):saved;}
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
        if(note.optBoolean("pending"))return "Transcription pending · open for details.";
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
            try{String summary=new CloudApi(app).noteSummary(text);if(summary==null||summary.isEmpty())throw new java.io.IOException("No summary.");updateCaptured(app,note,item->item.put("summary",summary));}
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
            try{String result=new CloudApi(app).translate(text,sourceLang,to);updateCaptured(app,note,item->item.put("translation",result).put("translationLang",code));}
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
    static File sourceDirectory(Context context){return dir(context);}
    static long nextThoughtId(Context context){synchronized(Store.class){long id=Store.nextId(all(context),System.currentTimeMillis());while(thoughtDeleted(context,id))id=Store.nextId(all(context),id+1);return id;}}

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
        catchUp(context,false);
    }
    static void catchUpThoughts(Context context){if(!AppProduct.isNotes())catchUp(context,true);}
    static boolean hasPendingThoughts(Context context){
        if(AppProduct.isNotes())return false;
        JSONArray notes=all(context);for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optBoolean("thought")&&!note.optBoolean("recording")&&note.optBoolean("pending")&&!filesOf(context,note).isEmpty())return true;}return false;
    }
    private static void catchUp(Context context,boolean thoughtsOnlyDrain) {
        if(context==null || (AppProduct.isNotes() && thoughtsOnlyDrain))return;
        if(!AppProduct.isNotes())ThoughtJob.schedule(context.getApplicationContext());
        ensureThoughtRetry(context);
        JSONArray notes=all(context);
        for(int i=0;i<notes.length();i++){
            JSONObject note=notes.optJSONObject(i);if(!ownsNote(note))continue;
            if(thoughtsOnlyDrain&&!note.optBoolean("thought"))continue;
            if(note.optBoolean("thought"))ensureThoughtRetry(context);
            if(note.optBoolean("recording")){
                if(FlowAccessibility.thoughtCaptureActive(note.optLong("id"))||VoiceTake.updateBusy()||LineWire.updateBusy())continue;
                // Recover sealed sources after process death; never inspect media metadata on the UI thread.
                recoverRecording(context,note);continue;
            }
            if(NoteWorkPolicy.eligible(note.optBoolean("pending"),note.optBoolean("recording"),note.optLong("retry_after"),System.currentTimeMillis())){writeUp(context,note.optLong("id"));return;}
        }
    }

    /** One process-lifetime retry hook; it never opens a microphone or an activity. */
    private static synchronized void ensureThoughtRetry(Context context){
        Context app=context.getApplicationContext();
        if(thoughtRetryAction==null)thoughtRetryAction=()->catchUp(app,!AppProduct.isNotes());
        if(thoughtRetryPrefs==null){
            thoughtRetryPrefs=(prefs,key)->{if("api_secret".equals(key)||"api_iv".equals(key)||"api_base".equals(key)||"notes_transcribe_model".equals(key))requestThoughtRetry();};
            Store.prefs(app).registerOnSharedPreferenceChangeListener(thoughtRetryPrefs);
        }
        if(thoughtNetwork!=null)return;
        android.net.ConnectivityManager cm=(android.net.ConnectivityManager)app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return;
        android.net.ConnectivityManager.NetworkCallback callback=new android.net.ConnectivityManager.NetworkCallback(){
            @Override public void onAvailable(android.net.Network network){requestThoughtRetry();}
            @Override public void onCapabilitiesChanged(android.net.Network network,android.net.NetworkCapabilities caps){if(caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED))requestThoughtRetry();}
        };
        try{cm.registerDefaultNetworkCallback(callback);thoughtNetwork=callback;}catch(RuntimeException unavailable){/* Resume and explicit Retry remain available. */}
    }
    private static void requestThoughtRetry(){Runnable task=thoughtRetryAction;if(task!=null){tick.removeCallbacks(task);tick.post(task);}}

    private static void recoverRecording(Context context,JSONObject captured){
        final Context app=context.getApplicationContext();final long id=captured.optLong("id"),revision=captured.optLong("revision",1);
        synchronized(recoveringThoughts){if(!recoveringThoughts.add(id))return;}
        thoughtWork.incrementAndGet();
        work.execute(()->{
            boolean committed=false;
            try{
                List<File> recovered=filesOf(app,captured);File[] candidates=dir(app).listFiles((folder,name)->name.matches(Long.toString(id)+"-[0-9]+\\.m4a"));
                if(candidates!=null){java.util.Arrays.sort(candidates,(a,b)->Long.compare(segmentIndex(a),segmentIndex(b)));for(File file:candidates)if(!recovered.contains(file)&&file.isFile()&&file.length()>0)recovered.add(file);}
                long duration=durations(recovered);
                synchronized(Store.class){
                    JSONObject current=find(app,id);
                    if(current==null||!current.optBoolean("recording")||current.optLong("revision",1)!=revision||FlowAccessibility.thoughtCaptureActive(id)||VoiceTake.updateBusy()||LineWire.updateBusy())return;
                    String before=Store.get(app,"notes","[]");JSONArray notes=new JSONArray(before);
                    for(int i=0;i<notes.length();i++){JSONObject item=notes.optJSONObject(i);if(item!=null&&item.optLong("id")==id)item.put("recording",false).put("pending",!recovered.isEmpty()&&(item.optBoolean("pending")||item.optString("raw").isEmpty()||item.optBoolean("partial_transcript"))).put("audio",recovered.isEmpty()?"":recovered.get(0).getName()).put("parts",names(recovered)).put("duration",duration>0?duration:item.optLong("duration")).put("source_interrupted",true).put("capture_error","Recording interrupted. Surviving source audio is saved.").put("thought_capture_complete",false).put("revision",revision+1);}
                    committed=Store.prefs(app).edit().putString("notes",notes.toString()).commit();if(!committed)Store.prefs(app).edit().putString("notes",before).commit();
                }
            }catch(Exception ignored){/* Original files remain on this phone for another recovery attempt. */}
            finally{synchronized(recoveringThoughts){recoveringThoughts.remove(id);}thoughtWork.decrementAndGet();}
            if(committed){poke();ThoughtJob.schedule(app);catchUp(app);}
        });
    }
    private static long segmentIndex(File file){try{String name=file.getName();return Long.parseLong(name.substring(name.lastIndexOf('-')+1,name.length()-4));}catch(RuntimeException invalid){return Long.MAX_VALUE;}}

    static void writeUpThought(Context context,long id){if(!AppProduct.isNotes())writeUp(context.getApplicationContext(),id);}
    private static void thoughtToast(Context context,String message){Activity activity=screen.get();if(activity!=null&&!activity.isDestroyed()&&!activity.isFinishing()&&activity.hasWindowFocus())toast(context,message);}

    private static void writeUp(Context context,long id) {
        JSONObject waiting=find(context,id);if(!ownsNote(waiting) || waiting.optBoolean("recording"))return;
        if(!online(context)||Vault.get(context).isEmpty())return;
        synchronized(Notes.class){if(draining||busy)return;draining=true;transcribing=id;}
        poke();
        work.execute(()->{
            JSONObject[] processing={waiting};
            try {
                JSONObject note=find(context,id);if(!ownsNote(note)||note.optBoolean("recording"))return;processing[0]=note;
                String requestedLanguage=note.optString("retry_language",note.optString("lang","auto"));
                Transcription result=transcribeNote(context,note,requestedLanguage);
                if(note.optBoolean("thought")){
                    int saved=saveThoughtTranscript(context,note,result);
                    if(saved==THOUGHT_TRANSCRIPT_SAVED && !result.raw.isEmpty() && result.complete)formatThought(context,id);
                }else{
                    String text=organize(context,result.raw),summary=recap(context,text);
                    updateCaptured(context,note,item->applyTranscription(item,result,requestedLanguage,text,summary));
                }
            }catch(Exception error){
                // A durable cooldown makes one bad item yield to every other eligible recording.
                JSONObject captured=processing[0];if(captured!=null)try{updateCaptured(context,captured,item->{int attempt=item.optInt("retry_count",0)+1;item.put("pending",true).put("retry_count",attempt).put("retry_after",System.currentTimeMillis()+NoteWorkPolicy.retryDelay(attempt)).put("transcription_error",offlineMessage(context,error));});}catch(Exception ignored){}
            }finally{synchronized(Notes.class){draining=false;transcribing=-1;}poke();catchUp(context);}
        });
    }

    private static final int THOUGHT_TRANSCRIPT_UNCHANGED=0,THOUGHT_TRANSCRIPT_SAVED=1,THOUGHT_TRANSCRIPT_FILTERED=2;
    private static int saveThoughtTranscript(Context context,JSONObject captured,Transcription result){
        String raw=result.raw,heard=result.heard;boolean completeTranscription=result.complete;
        if(raw==null)return THOUGHT_TRANSCRIPT_UNCHANGED;
        // Measuring is local, uses a stable canonical width, and never holds the note-storage lock.
        // A skipped/uncertain segment is not evidence that the complete source was a short thought.
        boolean filter=completeTranscription && captured.optBoolean("thought_capture_complete") && !captured.optBoolean("source_interrupted") && captured.optInt("minimum_thought_lines",0)==2
            && captured.optString("raw").trim().isEmpty() && captured.optString("text").trim().isEmpty()
            && !ThoughtLength.minimumMet(raw);
        synchronized(Store.class){
            try{
                long id=captured.optLong("id"),revision=captured.optLong("revision",1);JSONObject current=find(context,id);
                if(current==null||!current.optBoolean("thought")||current.optBoolean("recording")||!current.optBoolean("pending")||current.optLong("revision",1)!=revision||!current.optString("raw").equals(captured.optString("raw"))||!current.optString("text").equals(captured.optString("text"))||!current.optString("audio").equals(captured.optString("audio"))||current.optInt("minimum_thought_lines",0)!=captured.optInt("minimum_thought_lines",0)||current.optBoolean("thought_capture_complete")!=captured.optBoolean("thought_capture_complete")||current.optBoolean("source_interrupted")!=captured.optBoolean("source_interrupted")||!String.valueOf(current.opt("parts")).equals(String.valueOf(captured.opt("parts"))))return THOUGHT_TRANSCRIPT_UNCHANGED;
                String before=Store.get(context,"notes","[]");JSONArray notes=new JSONArray(before);
                if(filter && completeThoughtSources(context,current,filesOf(context,current))){
                    JSONArray kept=new JSONArray();for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")!=id)kept.put(note);}
                    String previousDeleted=Store.get(context,"thought_deletions","[]");List<File> source=filesOf(context,current);
                    // Deletion and its tombstone commit together before any source file is removed.
                    if(!Store.prefs(context).edit().putString("notes",kept.toString()).putString("thought_deletions",deletedThoughts(context,id).toString()).commit()){
                        Store.prefs(context).edit().putString("notes",before).putString("thought_deletions",previousDeleted).commit();return THOUGHT_TRANSCRIPT_UNCHANGED;
                    }
                    DailySummaryCache.invalidateEntry(context,"thought",id);
                    tick.post(()->{if(playing==id)stopPlayback();});
                    for(File audio:source)if(audio.isFile())audio.delete();
                    return THOUGHT_TRANSCRIPT_FILTERED;
                }
                for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id){applyTranscription(note,result,note.optString("lang","auto"),raw.trim(),QuickThoughtPolicy.summary(raw));if(!note.optBoolean("user_edited"))note.put("title",QuickThoughtPolicy.title(raw,"Quick thought"));note.put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",true).put("summary_retry_after",0L).put("revision",revision+1);}}
                if(Store.prefs(context).edit().putString("notes",notes.toString()).commit())return THOUGHT_TRANSCRIPT_SAVED;
                Store.prefs(context).edit().putString("notes",before).commit();return THOUGHT_TRANSCRIPT_UNCHANGED;
            }catch(Exception unavailable){return THOUGHT_TRANSCRIPT_UNCHANGED;}
        }
    }
    /** Destructive filtering requires every declared immutable segment, in the exact transcribed order. */
    private static boolean completeThoughtSources(Context context,JSONObject note,List<File> used){
        try{
            JSONArray declared=note.optJSONArray("parts");if(declared==null || declared.length()==0 || used==null || used.size()!=declared.length())return false;
            File folder=dir(context).getCanonicalFile();java.util.HashSet<String> unique=new java.util.HashSet<>();
            for(int i=0;i<declared.length();i++){
                String name=declared.optString(i);if(name.isEmpty() || !unique.add(name) || !name.equals(used.get(i).getName()))return false;
                File expected=new File(folder,name).getCanonicalFile(),actual=used.get(i).getCanonicalFile();
                if(!folder.equals(expected.getParentFile()) || !actual.equals(expected) || !actual.isFile() || actual.length()==0)return false;
            }
            return true;
        }catch(Exception unavailable){return false;}
    }

    private static void remember(Context context,long id,List<File> files,String lang,boolean pending) {
        try {
            JSONArray parts=names(files);
            if(parts.length()==0)return;
            save(context,id,"","",false,lang,"",files.get(0).getName(),durations(files),"",pending,parts);
        } catch(Exception ignored) {}
        poke();
    }

    private static final class Transcription {
        final JSONArray segments=new JSONArray();String raw="",heard="",error="",configuration="";boolean complete=true,replacement;
    }
    /** Successes are cached per immutable source and provider/language; retry only missing pieces. */
    private static Transcription transcribeNote(Context context,JSONObject note,String language) throws Exception {
        Transcription result=new Transcription();result.replacement=note.optBoolean("replacement_pending");
        String chosen=language==null?"auto":language,lang="auto".equals(chosen)?null:chosen;
        String configuration=chosen+"\n"+Store.get(context,"notes_transcribe_model","whisper-large-v3")+"\n"+Store.get(context,"api_base",CloudApi.DEFAULT_BASE);
        result.configuration=configuration;
        JSONArray cached=configuration.equals(note.optString("transcription_config"))?note.optJSONArray("segment_transcripts"):null;
        JSONArray declared=note.optJSONArray("parts");if(declared==null||declared.length()==0)declared=new JSONArray().put(note.optString("audio"));
        StringBuilder raw=new StringBuilder();java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<declared.length();i++){
            String name=declared.optString(i);if(name.isEmpty()||!seen.add(name)){result.complete=false;result.error="A source audio segment is missing.";continue;}
            File file=sourceFile(context,name);JSONObject segment=null;
            if(cached!=null&&file!=null&&file.isFile()&&file.length()>0)for(int j=0;j<cached.length();j++){JSONObject candidate=cached.optJSONObject(j);if(candidate!=null&&name.equals(candidate.optString("source"))&&candidate.optBoolean("complete")&&candidate.optLong("bytes")==file.length()){segment=new JSONObject(candidate.toString());break;}}
            if(segment==null){
                segment=new JSONObject().put("source",name).put("complete",false).put("text","");
                String[] detected={""};
                try{
                    if(file==null||!file.isFile()||file.length()==0)throw new java.io.IOException("A source audio segment is missing.");
                    String piece=new CloudApi(context).transcribe(file,true,null,lang,detected,"notes_transcribe_model");
                    // Auto respects the recognizer's language. Arabic script alone is never evidence of Bengali.
                    segment.put("bytes",file.length()).put("text",piece==null?"":piece.trim()).put("heard",detected[0]==null?"":detected[0]).put("complete",true);
                }catch(Exception error){segment.put("error",offlineMessage(context,error));}
            }
            result.segments.put(segment);
            if(!segment.optBoolean("complete")){result.complete=false;if(result.error.isEmpty())result.error=segment.optString("error","One source segment could not be transcribed.");}
            String piece=segment.optString("text").trim();if(!piece.isEmpty()){if(raw.length()>0)raw.append('\n');raw.append(piece);}
            if(result.heard.isEmpty())result.heard=segment.optString("heard");
        }
        if(result.segments.length()==0){result.complete=false;result.error="The recording is missing.";}
        result.raw=raw.toString().trim();return result;
    }
    private static void applyTranscription(JSONObject item,Transcription result,String language,String text,String summary)throws Exception{
        boolean hasExisting=!item.optString("raw").trim().isEmpty()||!item.optString("text").trim().isEmpty();
        if(result.replacement&&result.complete&&result.raw.trim().isEmpty()&&hasExisting){
            result.complete=false;result.error="No speech was recognized in the replacement. Your previous transcript was kept.";
            // Empty cached replacements must remain retryable instead of repeating a cached empty result.
            for(int i=0;i<result.segments.length();i++){JSONObject segment=result.segments.optJSONObject(i);if(segment!=null&&segment.optString("text").trim().isEmpty())segment.put("complete",false).put("error",result.error);}
        }
        int attempt=result.complete?0:item.optInt("retry_count",0)+1;
        boolean keepExisting=result.replacement&&!result.complete&&hasExisting;
        if(!keepExisting){
            if(!item.optBoolean("user_edited"))item.put("text",text).put("touched",!text.equals(result.raw)).put("summary",summary==null?"":summary);
            item.put("raw",result.raw).put("lang",language).put("heard",result.heard);item.remove("retry_language");
        }else item.put("retry_language",language);
        item.put("segment_transcripts",result.segments).put("partial_transcript",!result.complete).put("pending",!result.complete).put("replacement_pending",keepExisting).put("transcription_error",result.error)
            .put("retry_count",attempt).put("retry_after",result.complete?0:System.currentTimeMillis()+NoteWorkPolicy.retryDelay(attempt));
        // Only matching immutable source/provider snapshots may reuse successful pieces.
        item.put("transcription_config",result.configuration);
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
        File file=sourceFile(context,name);
        if(file!=null&&file.isFile()&&!files.contains(file))files.add(file);
    }

    private static File sourceFile(Context context,String name){try{File folder=dir(context).getCanonicalFile(),file=new File(folder,name).getCanonicalFile();return folder.equals(file.getParentFile())?file:null;}catch(Exception unavailable){return null;}}

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
                .put("created",id).put("revision",1L).put("summary","").put("pending",false).put("translation",translation==null?"":translation).put("translationLang",targetCode==null?"":targetCode);
            if(parts!=null)item.put("parts",parts);
            next.put(item);
            appendRetained(next,notes,id);
            commitNotes(context,notes,next);
        } catch(Exception ignored) {}
            }
    }

    /** Creation is reserved for source handoff; async text workers only use updateCaptured. */
    private static void save(Context context,long id,String text,String raw,boolean touched,String lang,String heard,String audio,long duration,String summary,boolean pending,JSONArray parts) throws Exception {
        synchronized(Store.class){
            if(noteDeleted(context,id))return;
            JSONArray notes=all(context),next=new JSONArray();JSONObject previous=find(context,id);
            JSONObject item=previous==null?new JSONObject().put("id",id).put("created",id).put("text",text).put("raw",raw==null?"":raw).put("touched",touched).put("lang",lang).put("heard",heard==null?"":heard).put("summary",summary==null?"":summary):new JSONObject(previous.toString());
            item.put("audio",audio).put("duration",duration).put("pending",pending).put("recording",VoiceTake.noteRunning()).put("retry_after",0).put("revision",previous==null?1:previous.optLong("revision",1)+1);
            if(parts!=null)item.put("parts",parts);
            next.put(item);appendRetained(next,notes,id);commitNotes(context,notes,next);
        }
    }
    interface NoteMutation{void apply(JSONObject note)throws Exception;}
    /** A late provider response can update only the exact existing revision it read. */
    private static boolean updateCaptured(Context context,JSONObject captured,NoteMutation mutation)throws Exception{
        synchronized(Store.class){
            long id=captured.optLong("id");JSONObject current=find(context,id);
            if(!ownsNote(captured) || !ownsNote(current))return false;
            if(!NoteWorkPolicy.canApply(captured.toString(),current==null?null:current.toString()))return false;
            JSONArray previous=all(context),next=new JSONArray();
            for(int i=0;i<previous.length();i++){JSONObject item=previous.optJSONObject(i);if(item==null)continue;if(item.optLong("id")==id){mutation.apply(item);item.put("revision",item.optLong("revision",1)+1);}next.put(item);}
            return commitNotes(context,previous,next);
        }
    }
    /** Commit metadata before deleting audio that the retention limit made unreachable. */
    private static boolean commitNotes(Context context,JSONArray previous,JSONArray next){
        String before=previous.toString();
        if(!Store.prefs(context).edit().putString("notes",next.toString()).commit()){Store.prefs(context).edit().putString("notes",before).commit();return false;}
        java.util.HashSet<Long> retainedIds=new java.util.HashSet<>();java.util.HashSet<String> retainedFiles=new java.util.HashSet<>();
        for(int i=0;i<next.length();i++){JSONObject item=next.optJSONObject(i);if(item==null)continue;retainedIds.add(item.optLong("id"));for(File audio:filesOf(context,item))retainedFiles.add(audio.getAbsolutePath());}
        for(int i=0;i<previous.length();i++){JSONObject item=previous.optJSONObject(i);if(item==null||retainedIds.contains(item.optLong("id"))||item.optBoolean("recording")||item.optBoolean("pending"))continue;for(File audio:filesOf(context,item))if(!retainedFiles.contains(audio.getAbsolutePath()))audio.delete();}
        return true;
    }
    private static void resetRetry(Context context,long id){
        synchronized(Store.class){try{JSONObject current=find(context,id);if(current!=null)updateCaptured(context,current,item->item.put("retry_after",0).put("retry_count",0));}catch(Exception ignored){}}
    }
    private static boolean noteDeleted(Context context,long id){
        JSONObject existing=find(context,id);if(existing!=null&&existing.optBoolean("trashed"))return true;
        if(thoughtDeleted(context,id))return true;
        try{JSONArray ids=new JSONArray(Store.get(context,"note_deletions","[]"));for(int i=0;i<ids.length();i++)if(ids.optLong(i)==id)return true;}catch(Exception ignored){}return false;
    }
    private static JSONArray deletedNotes(Context context,long id){JSONArray next=new JSONArray().put(id);try{JSONArray old=new JSONArray(Store.get(context,"note_deletions","[]"));for(int i=0;i<old.length()&&next.length()<10000;i++)if(old.optLong(i)!=id)next.put(old.optLong(i));}catch(Exception ignored){}return next;}

    private static void remove(Context activity,long id) {
        synchronized(Store.class){
        stopPlayback();
        JSONArray notes=all(activity),next=new JSONArray();List<File> removedFiles=new ArrayList<>();boolean thought=false;
        for(int i=0;i<notes.length();i++){JSONObject old=notes.optJSONObject(i);if(old==null)continue;if(old.optLong("id")==id){removedFiles.addAll(filesOf(activity,old));thought=old.optBoolean("thought");}else next.put(old);}
        String previousDeleted=Store.get(activity,"thought_deletions","[]"),previousNoteDeleted=Store.get(activity,"note_deletions","[]");android.content.SharedPreferences.Editor edit=Store.prefs(activity).edit().putString("notes",next.toString()).putString("note_deletions",deletedNotes(activity,id).toString());
        if(thought)edit.putString("thought_deletions",deletedThoughts(activity,id).toString());
        if(!edit.commit()){Store.prefs(activity).edit().putString("notes",notes.toString()).putString("thought_deletions",previousDeleted).putString("note_deletions",previousNoteDeleted).commit();toast(activity,"Could not delete this note. Check available phone storage.");return;}
        if(thought)DailySummaryCache.invalidateEntry(activity,"thought",id);
        for(File audio:removedFiles)if(audio.isFile())audio.delete();
            }
    }

    static boolean thoughtDeleted(Context context,long id){synchronized(Store.class){try{JSONArray ids=new JSONArray(Store.get(context,"thought_deletions","[]"));for(int i=0;i<ids.length();i++)if(ids.optLong(i)==id)return true;}catch(Exception ignored){}return false;}}
    private static JSONArray deletedThoughts(Context context,long id){JSONArray next=new JSONArray().put(id);try{JSONArray old=new JSONArray(Store.get(context,"thought_deletions","[]"));for(int i=0;i<old.length()&&next.length()<10000;i++)if(old.optLong(i)!=id)next.put(old.optLong(i));}catch(Exception ignored){}return next;}
    static void discardThoughtCapture(Context context,long id){synchronized(Store.class){JSONObject note=find(context,id);if(note!=null&&note.optBoolean("thought")&&(note.optBoolean("recording")||note.optBoolean("pending")))remove(context,id);}poke();}

    private static void stamp(Context context,long id,String key,String value) {
        synchronized(Store.class){
        try {
            JSONArray notes=all(context);
            for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null && note.optLong("id")==id)note.put(key,value==null?"":value).put("revision",note.optLong("revision",1)+1);}
            Store.prefs(context).edit().putString("notes",notes.toString()).apply();
        } catch(Exception ignored) {}
        poke();
            }
    }


    /** Adding a recording never evicts another recording or deletes its source audio. */
    private static void appendRetained(JSONArray next,JSONArray previous,long excludedId){
        for(int i=0;i<previous.length();i++){
            JSONObject entry=previous.optJSONObject(i);if(entry==null || entry.optLong("id")==excludedId)continue;
            next.put(entry);
        }
    }

    /** Stored before optional cloud formatting. The original words never depend on its result. */
    static long keepThought(Context context,long requestedId,String raw,long duration) {
        return keepThought(context,requestedId,raw,duration,null);
    }
    static long keepThought(Context context,long requestedId,String raw,long duration,File source) {
        if(AppProduct.isNotes())return -1;
        List<File> copied=new ArrayList<>();
        synchronized(Store.class){
        if(raw==null || raw.trim().isEmpty())return -1;
        try {
            JSONArray notes=all(context),next=new JSONArray();
            long id=Store.nextId(notes,requestedId>0?requestedId:System.currentTimeMillis());
            List<File> audio=adoptThoughtFiles(context,id,source==null?null:java.util.Collections.singletonList(source),copied);
            String text=raw.trim();
            JSONObject thought=new JSONObject().put("id",id).put("created",id).put("thought",true)
                .put("text",text).put("raw",raw).put("title",QuickThoughtPolicy.title(text,"Quick thought"))
                .put("duration",Math.max(Math.max(0,duration),durations(audio))).put("lang",Store.language(context)).put("heard",Store.language(context))
                .put("audio",audio.isEmpty()?"":audio.get(0).getName()).put("parts",names(audio)).put("summary",QuickThoughtPolicy.summary(raw)).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",true).put("summary_retry_after",0L).put("pending",false).put("revision",1L);
            next.put(thought);
            appendRetained(next,notes,-1);
            // Commit so the UI only says Saved when storage has accepted the words.
            if(!Store.prefs(context).edit().putString("notes",next.toString()).commit()){Store.prefs(context).edit().putString("notes",notes.toString()).commit();for(File file:copied)file.delete();return -1;}
            poke();
            return id;
        } catch(Exception error){for(File file:copied)file.delete();return -1;}
            }
    }

    /** Finalized source segments are committed before transcription; repeating a callback updates one note. */
    static long keepThoughtAudio(Context context,long requestedId,List<File> sources,long duration,boolean complete){
        return keepThoughtAudio(context,requestedId,sources,duration,complete,complete);
    }
    /** Failed/interrupted capture can save its sealed subset without becoming eligible for a length discard. */
    static long keepThoughtAudio(Context context,long requestedId,List<File> sources,long duration,boolean complete,boolean captureComplete){
        if(AppProduct.isNotes())return -1;
        long saved=-1;List<File> copied=new ArrayList<>();
        synchronized(Store.class){
            if(requestedId<=0||thoughtDeleted(context,requestedId))return -1;
            try{
                JSONArray previous=all(context);JSONObject old=find(context,requestedId);
                if(old!=null&&!old.optBoolean("thought"))return -1;
                if(old!=null&&!old.optBoolean("recording")&&!old.optBoolean("pending")&&!old.optString("raw").isEmpty())return requestedId;
                List<File> files=adoptThoughtFiles(context,requestedId,sources,copied);
                if(complete&&files.isEmpty())return -1;
                boolean verifiedCaptureComplete=complete&&captureComplete&&sources!=null&&!sources.isEmpty()&&files.size()==sources.size();
                if(verifiedCaptureComplete&&old!=null){
                    java.util.HashSet<String> keptNames=new java.util.HashSet<>();for(File file:files)keptNames.add(file.getName());
                    JSONArray priorParts=old.optJSONArray("parts");if(priorParts!=null)for(int i=0;i<priorParts.length();i++)if(!keptNames.contains(priorParts.optString(i)))verifiedCaptureComplete=false;
                }
                if(old!=null&&old.optBoolean("recording")==!complete&&old.optBoolean("pending")==complete&&old.optBoolean("thought_capture_complete")==verifiedCaptureComplete&&String.valueOf(old.opt("parts")).equals(names(files).toString())&&old.optLong("duration")>=Math.max(0,duration))return requestedId;
                JSONObject note=old==null?new JSONObject().put("id",requestedId).put("created",requestedId).put("thought",true).put("raw","").put("text","").put("title","Quick thought").put("summary","").put("lang","auto").put("heard",""):new JSONObject(old.toString());
                if(old==null&&!complete)note.put("minimum_thought_lines",2);
                if(complete&&!verifiedCaptureComplete)note.put("source_interrupted",true).put("capture_error","Recording interrupted. Surviving source audio is saved.");
                note.put("audio",files.isEmpty()?"":files.get(0).getName()).put("parts",names(files)).put("duration",Math.max(Math.max(0,duration),durations(files)))
                    .put("recording",!complete).put("pending",complete).put("thought_capture_complete",verifiedCaptureComplete).put("revision",old==null?1:old.optLong("revision",1)+1);
                JSONArray next=new JSONArray().put(note);appendRetained(next,previous,requestedId);
                if(!Store.prefs(context).edit().putString("notes",next.toString()).commit()){Store.prefs(context).edit().putString("notes",previous.toString()).commit();for(File file:copied)file.delete();return -1;}
                saved=requestedId;
            }catch(Exception unavailable){for(File file:copied)file.delete();return -1;}
        }
        ensureThoughtRetry(context);poke();if(complete){ThoughtJob.schedule(context.getApplicationContext());writeUpThought(context,saved);}return saved;
    }

    /** Compatibility for a failed capture callback; normal final saves set these fields atomically. */
    static void markThoughtInterrupted(Context context,long id){
        synchronized(Store.class){try{JSONObject current=find(context,id);if(current==null||!current.optBoolean("thought")||(current.optBoolean("source_interrupted")&&!current.optString("capture_error").isEmpty()))return;updateCaptured(context,current,item->item.put("source_interrupted",true).put("thought_capture_complete",false).put("capture_error","Recording interrupted. Surviving source audio is saved."));}catch(Exception ignored){}}
        poke();
    }

    /** External finalized files are copied and synced; a failed commit never consumes the caller's source. */
    private static List<File> adoptThoughtFiles(Context context,long id,List<File> sources,List<File> copied) throws Exception{
        List<File> result=new ArrayList<>();if(sources==null)return result;File folder=dir(context).getCanonicalFile();int index=0;
        for(File source:sources){
            if(source==null||!source.isFile()||source.length()==0)continue;File canonical=source.getCanonicalFile();
            if(folder.equals(canonical.getParentFile())){result.add(canonical);index++;continue;}
            String ext=source.getName().toLowerCase(Locale.ROOT).endsWith(".wav")?".wav":".m4a";File target=new File(folder,"thought-"+id+"-"+(index++)+"-"+System.nanoTime()+ext),temporary=new File(target.getPath()+".part");
            try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(temporary)){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.getFD().sync();}
            catch(Exception error){temporary.delete();throw error;}
            if(temporary.length()!=source.length()||!temporary.renameTo(target)){temporary.delete();throw new java.io.IOException("Could not retain the source audio.");}
            copied.add(target);result.add(target);
        }
        return result;
    }

    static void formatThought(Context context,long id) {
        if(AppProduct.isNotes())return;
        Context app=context.getApplicationContext();
        JSONObject captured=find(app,id);if(captured==null || !captured.optBoolean("thought") || captured.optBoolean("user_edited"))return;
        final long revision=captured.optLong("revision",1);
        final String raw=captured.optString("raw");
        if(raw.isEmpty() || Vault.get(app).isEmpty())return;
        thoughtWork.incrementAndGet();
        work.execute(()->{
            try {
                QuickThoughtPolicy.Organized organized=new CloudApi(app).organizeThought(raw);
                synchronized(Store.class){
                    JSONObject current=find(app,id);
                    // Deletion, a restore or an edit supersedes an in-flight response.
                    if(!QuickThoughtPolicy.canApply(raw,revision,current!=null,current!=null && current.optBoolean("thought"),current==null?"":current.optString("raw"),current==null?-1:current.optLong("revision",1)))return;
                    String before=Store.get(app,"notes","[]");JSONArray old=new JSONArray(before),next=new JSONArray();
                    for(int i=0;i<old.length();i++){
                        JSONObject note=old.optJSONObject(i);if(note==null)continue;
                        if(note.optLong("id")==id){note.put("text",organized.body).put("title",organized.title).put("summary",organized.summary).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",!organized.summarized).put("summary_retry_after",0L).put("revision",revision+1);}
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
                if(note.optLong("id")==id)note.put("user_edited",true).put("pending",false).put("text",text.trim()).put("title",QuickThoughtPolicy.title(text,"Quick thought")).put("summary",QuickThoughtPolicy.summary(text)).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",true).put("summary_retry_after",0L).put("revision",note.optLong("revision",1)+1);
                next.put(note);
            }
            boolean saved=Store.prefs(context).edit().putString("notes",next.toString()).commit();if(saved){DailySummaryCache.invalidateEntry(context,"thought",id);poke();}else Store.prefs(context).edit().putString("notes",before).commit();return saved;
        } catch(Exception ignored){return false;}
            }
    }

    private static void thoughtList(Activity activity,LinearLayout body,Runnable refresh) {
        if(!Store.quickThoughts(activity))return;
        List<ThoughtTimeline.Day> days=thoughtDays(activity);List<HistoryDays.Day> completed=new ArrayList<>();for(ThoughtTimeline.Day day:days)if(day.recap!=null)completed.add(day.recap);
        DailySummaryCache.prune(activity,"thought",completed);
        if(days.isEmpty()){Ui.space(body,4);body.addView(Daybook.text(activity,"Thoughts captured with the floating control show up here.",13,Daybook.muted(activity),false));return;}
        String model=ThoughtDailySummaries.model(activity),endpoint=ThoughtDailySummaries.endpoint(activity);
        boolean enabled=Store.bool(activity,"daily_recaps_opt_in",false),keyed=!Vault.get(activity).isEmpty(),online=ThoughtDailySummaries.online(activity);
        String today=HistoryDays.key(System.currentTimeMillis(),java.util.TimeZone.getDefault());java.util.Calendar yesterday=java.util.Calendar.getInstance();yesterday.add(java.util.Calendar.DAY_OF_YEAR,-1);String previous=HistoryDays.key(yesterday.getTimeInMillis(),yesterday.getTimeZone());
        int index=0;
        for(ThoughtTimeline.Day day:days){
            LinearLayout section=Ui.row(activity);section.setGravity(android.view.Gravity.TOP);Daybook.Rail rail=new Daybook.Rail(activity,index++==0);section.addView(rail,new LinearLayout.LayoutParams(Ui.dp(activity,18),-1));LinearLayout content=Ui.column(activity);content.setPadding(Ui.dp(activity,3),0,0,Ui.dp(activity,19));section.addView(content,new LinearLayout.LayoutParams(0,-2,1));
            String title=day.key.equals(today)?"Today":day.key.equals(previous)?"Yesterday":new java.text.SimpleDateFormat("EEEE",Locale.getDefault()).format(new Date(day.time()));
            LinearLayout head=Ui.row(activity);head.setMinimumHeight(Ui.dp(activity,44));head.setBackground(Daybook.ripple(activity,android.graphics.Color.TRANSPARENT,8));LinearLayout labels=Ui.column(activity);labels.addView(Daybook.text(activity,title,15,Daybook.ink(activity),true));Ui.space(labels,3);labels.addView(Daybook.text(activity,new java.text.SimpleDateFormat("EEE, MMM d",Locale.getDefault()).format(new Date(day.time()))+" · "+day.items.size()+" saved",11,Daybook.muted(activity),false));head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=Daybook.text(activity,"⌄",18,Daybook.accent(activity),false);head.addView(arrow);content.addView(head);Ui.space(content,7);
            LinearLayout timeline=Ui.column(activity);timeline.setPadding(0,Ui.dp(activity,10),0,0);
            for(ThoughtTimeline.Item item:day.items){
                android.view.View divider=new android.view.View(activity);divider.setBackgroundColor(Daybook.line(activity));timeline.addView(divider,new LinearLayout.LayoutParams(-1,Math.max(1,Ui.dp(activity,.5f))));
                LinearLayout row=Ui.row(activity);row.setMinimumHeight(Ui.dp(activity,51));row.setPadding(0,Ui.dp(activity,7),0,Ui.dp(activity,7));row.setBackground(Daybook.ripple(activity,android.graphics.Color.TRANSPARENT,7));TextView time=Daybook.text(activity,DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(item.id)),11,Daybook.muted(activity),false);row.addView(time,new LinearLayout.LayoutParams(Ui.dp(activity,67),-2));TextView excerpt=Daybook.text(activity,item.display(),16,item.recording||item.pending?Daybook.muted(activity):Daybook.ink(activity),false);excerpt.setMaxLines(2);excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(excerpt,new LinearLayout.LayoutParams(0,-2,1));row.setContentDescription("Open thought "+item.id+": "+item.display());row.setOnClickListener(v->{Daybook.feedback(v);open=item.id;original=false;thoughtSourceShown=-1;refresh.run();});timeline.addView(row);
            }
            boolean expanded=thoughtDaysOpen.contains(day.key)||(day.key.equals(today)&&!thoughtDaysClosed.contains(day.key));timeline.setVisibility(expanded?android.view.View.VISIBLE:android.view.View.GONE);arrow.setText(expanded?"⌃":"⌄");content.addView(timeline);
            if(enabled){Ui.space(content,10);content.addView(thoughtDayRecap(activity,day,model,endpoint,keyed,online,refresh));}
            head.setContentDescription((expanded?"Hide":"Show")+" timeline for "+day.key);head.setOnClickListener(v->{Daybook.feedback(v);boolean show=timeline.getVisibility()!=android.view.View.VISIBLE;timeline.setVisibility(show?android.view.View.VISIBLE:android.view.View.GONE);if(show){thoughtDaysOpen.add(day.key);thoughtDaysClosed.remove(day.key);Daybook.motion(timeline);}else{thoughtDaysOpen.remove(day.key);thoughtDaysClosed.add(day.key);}arrow.setText(show?"⌃":"⌄");head.setContentDescription((show?"Hide":"Show")+" timeline for "+day.key);});body.addView(section);
        }
        ThoughtDailySummaries.request(activity,completed);
    }

    private static LinearLayout thoughtDayRecap(Activity activity,ThoughtTimeline.Day day,String model,String endpoint,boolean keyed,boolean online,Runnable refresh){
        LinearLayout recap=Daybook.card(activity);recap.setPadding(Ui.dp(activity,12),Ui.dp(activity,10),Ui.dp(activity,12),Ui.dp(activity,10));recap.addView(Daybook.text(activity,"✧  Daily recap",11,Daybook.accent(activity),true));Ui.space(recap,4);
        String cached=day.recap==null?"":DailySummaryCache.get(activity,"thought",day.recap,model,endpoint),failure=day.recap==null?"":ThoughtDailySummaries.failure(day.recap,model,endpoint);
        String text=!cached.isEmpty()?cached:day.recap==null?"Your thoughts will fill in after transcription.":!failure.isEmpty()?"Recap unavailable. Your thoughts are under the day.":!keyed?"Your words are under the day. Add a provider key if you want a recap.":!online?"Waiting for a connection. Your thoughts are saved.":"Creating your daily recap…";
        recap.addView(Daybook.text(activity,text,13,cached.isEmpty()?Daybook.muted(activity):Daybook.ink(activity),false));
        if(!cached.isEmpty()||!failure.isEmpty()){String status=failure.isEmpty()?"AI recap · "+thoughtModelName(model):failure;recap.addView(Daybook.text(activity,status,11,Daybook.muted(activity),false));}
        if(!failure.isEmpty()&&day.recap!=null)recap.addView(Daybook.button(activity,"Retry recap",false,v->{ThoughtDailySummaries.retry(activity,day.recap);refresh.run();}),new LinearLayout.LayoutParams(-1,Ui.dp(activity,44)));
        return recap;
    }
    private static String thoughtModelName(String model){for(int i=0;i<ModelCatalog.CLEANUP_IDS.length;i++)if(ModelCatalog.CLEANUP_IDS[i].equals(model))return ModelCatalog.CLEANUP_NAMES[i];return model;}
    private static List<ThoughtTimeline.Day> thoughtDays(Context context){
        List<ThoughtTimeline.Item> items=new ArrayList<>();JSONArray notes=all(context);
        for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optBoolean("thought")&&note.optLong("id")>0)items.add(new ThoughtTimeline.Item(note.optLong("id"),titleOf(note),note.optString("text"),note.optString("raw"),note.optLong("revision",1),note.optBoolean("recording"),note.optBoolean("pending"),note.optBoolean("source_interrupted")));}
        return ThoughtTimeline.group(items,java.util.TimeZone.getDefault());
    }
    static List<HistoryDays.Day> thoughtRecapDays(Context context){List<HistoryDays.Day> days=new ArrayList<>();for(ThoughtTimeline.Day day:thoughtDays(context))if(day.recap!=null)days.add(day.recap);return days;}
    private static String thoughtBody(JSONObject note){String text=note.optString("text");return text.trim().isEmpty()?note.optString("raw"):text;}

    /** Replace old transcript-like summaries alone; edits and source audio never go through organization. */
    private static void upgradeThoughtSummary(Context context,JSONObject shown){
        int version=shown.optInt("summary_version",0);boolean upgrade=version<QuickThoughtPolicy.SUMMARY_VERSION;
        if(version>QuickThoughtPolicy.SUMMARY_VERSION || (!upgrade&&!shown.optBoolean("summary_local")) || shown.optBoolean("recording") || shown.optBoolean("pending"))return;
        final Context app=context.getApplicationContext();final long id=shown.optLong("id"),revision=shown.optLong("revision",1);
        final String raw=shown.optString("raw"),text=shown.optString("text"),parts=String.valueOf(shown.opt("parts")),audio=shown.optString("audio");
        final String source=text.trim().isEmpty()?raw:text;if(source.trim().isEmpty())return;
        final String fallback=upgrade?QuickThoughtPolicy.summary(source):shown.optString("summary");
        if(upgrade)synchronized(Store.class){
            JSONObject current=find(app,id);if(!thoughtSnapshot(current,revision,raw,text,parts,audio) || current.optInt("summary_version",0)>=QuickThoughtPolicy.SUMMARY_VERSION)return;
            try{
                String before=Store.get(app,"notes","[]");JSONArray notes=new JSONArray(before);
                for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id)note.put("summary",fallback).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",true).put("summary_retry_after",0L);}
                if(!Store.prefs(app).edit().putString("notes",notes.toString()).commit()){Store.prefs(app).edit().putString("notes",before).commit();return;}
                shown.put("summary",fallback).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",true).put("summary_retry_after",0L);
            }catch(Exception unavailable){return;}
        }
        // An already minimal phrase needs no paraphrase. Offline summaries remain eligible on reconnect.
        if(TranscriptGuard.words(source).size()<=12 || shown.optLong("summary_retry_after",0)>System.currentTimeMillis() || Vault.get(app).isEmpty() || !ThoughtDailySummaries.online(app))return;
        final String model=ThoughtDailySummaries.model(app),endpoint=ThoughtDailySummaries.endpoint(app),credential=summaryCredential(app);
        synchronized(thoughtSummaryUpgrades){if(!thoughtSummaryUpgrades.add(id))return;}
        boolean reserved=false;
        synchronized(Store.class){
            JSONObject current=find(app,id);
            if(thoughtSnapshot(current,revision,raw,text,parts,audio) && current.optString("summary").equals(fallback) && current.optBoolean("summary_local") && current.optLong("summary_retry_after",0)<=System.currentTimeMillis())try{
                String before=Store.get(app,"notes","[]");JSONArray notes=new JSONArray(before);
                for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id)note.put("summary_retry_after",System.currentTimeMillis()+300_000L);}
                reserved=Store.prefs(app).edit().putString("notes",notes.toString()).commit();if(!reserved)Store.prefs(app).edit().putString("notes",before).commit();
            }catch(Exception unavailable){reserved=false;}
        }
        if(!reserved){synchronized(thoughtSummaryUpgrades){thoughtSummaryUpgrades.remove(id);}return;}
        thoughtWork.incrementAndGet();
        work.execute(()->{
            try{
                if(!summaryProviderMatches(app,model,endpoint,credential))return;
                synchronized(Store.class){JSONObject current=find(app,id);if(!thoughtSnapshot(current,revision,raw,text,parts,audio) || !current.optString("summary").equals(fallback) || !current.optBoolean("summary_local"))return;}
                QuickThoughtPolicy.Summary result=new CloudApi(app).thoughtSummaryResult(source);if(!result.accepted)return;String summary=result.text;
                synchronized(Store.class){
                    JSONObject current=find(app,id);
                    if(!thoughtSnapshot(current,revision,raw,text,parts,audio) || !current.optString("summary").equals(fallback) || !current.optBoolean("summary_local") || !summaryProviderMatches(app,model,endpoint,credential))return;
                    String before=Store.get(app,"notes","[]");JSONArray notes=new JSONArray(before);
                    for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id)note.put("summary",summary).put("summary_version",QuickThoughtPolicy.SUMMARY_VERSION).put("summary_local",false).put("summary_retry_after",0L);}
                    if(!Store.prefs(app).edit().putString("notes",notes.toString()).commit())Store.prefs(app).edit().putString("notes",before).commit();
                }
            }catch(Exception unavailable){/* Keep the concise local gist and retry after the cooldown. */}
            finally{synchronized(thoughtSummaryUpgrades){thoughtSummaryUpgrades.remove(id);}thoughtWork.decrementAndGet();poke();}
        });
    }
    private static String summaryCredential(Context app){return Store.get(app,"api_secret","")+":"+Store.get(app,"api_iv","");}
    private static boolean summaryProviderMatches(Context app,String model,String endpoint,String credential){return model.equals(ThoughtDailySummaries.model(app))&&endpoint.equals(ThoughtDailySummaries.endpoint(app))&&credential.equals(summaryCredential(app));}
    private static boolean thoughtSnapshot(JSONObject current,long revision,String raw,String text,String parts,String audio){
        return current!=null && current.optBoolean("thought") && !current.optBoolean("recording") && !current.optBoolean("pending") && current.optLong("revision",1)==revision && current.optString("raw").equals(raw) && current.optString("text").equals(text) && String.valueOf(current.opt("parts")).equals(parts) && current.optString("audio").equals(audio);
    }

    private static void thoughtDetail(Activity activity,LinearLayout body,JSONObject note,Runnable refresh) {
        if(!Store.quickThoughts(activity)){back();return;}
        upgradeThoughtSummary(activity,note);
        long id=note.optLong("id");boolean recording=note.optBoolean("recording"),coming=transcribing==id;
        body.addView(Daybook.text(activity,titleOf(note),22,Daybook.ink(activity),true));Ui.space(body,4);body.addView(Daybook.text(activity,when(note),12,Daybook.muted(activity),false));Ui.space(body,16);
        String summary=note.optString("summary");
        if(!original&&!summary.isEmpty()){
            body.addView(Daybook.text(activity,"Summary",12,Daybook.muted(activity),true));Ui.space(body,6);
            TextView recap=Daybook.text(activity,summary,16,Daybook.ink(activity),false);recap.setLineSpacing(Ui.dp(activity,3),1);recap.setTextIsSelectable(true);body.addView(recap);Ui.space(body,16);
        }
        if(!original&&summary.isEmpty()&&!recording&&!note.optBoolean("pending")&&!thoughtBody(note).trim().isEmpty()){
            body.addView(Daybook.text(activity,"Summary",12,Daybook.muted(activity),true));Ui.space(body,6);
            body.addView(Daybook.text(activity,"A concise summary will fill in when your provider is connected. Your full transcript is saved below.",14,Daybook.muted(activity),false));Ui.space(body,16);
        }
        body.addView(Daybook.text(activity,original?"Original transcript":"Full transcript",12,Daybook.muted(activity),true));Ui.space(body,6);
        if(!coming&&note.optBoolean("partial_transcript")){body.addView(Daybook.text(activity,"Partial transcript · some source segments still need transcription.",13,Daybook.muted(activity),false));Ui.space(body,6);}
        String text=note.optString(original?"raw":"text");
        if(!original&&!recording&&!note.optBoolean("pending")&&text.trim().isEmpty())text=note.optString("raw");
        if(text.isEmpty())text=recording?"Recording. Your source audio is being kept on this phone.":coming?"Writing it up…":note.optBoolean("pending")?"Saved on this phone. The words will fill in when you are online and your provider is connected.":note.optBoolean("source_interrupted")?"The recording was interrupted before a finalized source segment could be recovered.":"No speech was recognized. Your source audio is still saved.";
        String issue=note.optString("transcription_error",note.optString("capture_error"));if(!coming&&!issue.isEmpty()){body.addView(Daybook.text(activity,issue,13,Daybook.muted(activity),false));Ui.space(body,6);}
        TextView words=Daybook.text(activity,text,17,Daybook.ink(activity),false);words.setLineSpacing(Ui.dp(activity,4),1);words.setTextIsSelectable(true);body.addView(words);Ui.space(body,18);
        LinearLayout actions=Ui.row(activity);
        if(!note.optString("raw").isEmpty())actions.addView(quiet(activity,original?"Organized":"Original",v->{original=!original;refresh.run();}));
        if(!thoughtBody(note).isEmpty())actions.addView(quiet(activity,"Edit",v->{
            android.widget.EditText input=new android.widget.EditText(activity);input.setText(thoughtBody(note));input.setMinLines(3);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(activity).setTitle("Edit thought").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
            dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{if(editThought(activity,note.optLong("id"),input.getText().toString())){dialog.dismiss();refresh.run();}else input.setError("Add some text, or reopen this thought.");}));dialog.show();
        }));
        if(!filesOf(activity,note).isEmpty()&&!recording)actions.addView(quiet(activity,thoughtSourceShown==id?"Hide audio":"Source audio",v->{if(thoughtSourceShown==id){thoughtSourceShown=-1;stopPlayback();}else thoughtSourceShown=id;refresh.run();}));
        actions.addView(quiet(activity,"Delete",v->new android.app.AlertDialog.Builder(activity).setTitle("Delete this thought?").setMessage("Removes the thought, its original transcript and source audio from this phone.").setNegativeButton("Keep",null).setPositiveButton("Delete",(d,w)->{remove(activity,id);back();refresh.run();}).show()));
        body.addView(actions);
        if(!recording&&!coming&&(note.optString("raw").isEmpty()||note.optBoolean("partial_transcript"))&&!filesOf(activity,note).isEmpty())body.addView(quiet(activity,"Retry transcription",v->{retryThought(activity,id);refresh.run();}));
        if(thoughtSourceShown==id&&!recording){Ui.space(body,12);thoughtSourcePlayer(activity,body,note,refresh);}
    }

    private static void retryThought(Context context,long id){
        if(AppProduct.isNotes())return;
        synchronized(Store.class){try{String before=Store.get(context,"notes","[]");JSONArray notes=new JSONArray(before);boolean found=false;for(int i=0;i<notes.length();i++){JSONObject note=notes.optJSONObject(i);if(note!=null&&note.optLong("id")==id&&note.optBoolean("thought")&&!note.optBoolean("recording")){note.put("pending",true).put("retry_after",0).put("retry_count",0).put("revision",note.optLong("revision",1)+1);found=true;}}if(!found)return;if(!Store.prefs(context).edit().putString("notes",notes.toString()).commit()){Store.prefs(context).edit().putString("notes",before).commit();return;}}catch(Exception unavailable){return;}}
        ThoughtJob.schedule(context.getApplicationContext());writeUpThought(context,id);
    }

    /** Uses the existing note player, revealed only by an explicit detail action. */
    private static void thoughtSourcePlayer(Activity activity,LinearLayout body,JSONObject note,Runnable refresh){
        boolean on=playing==note.optLong("id")&&!paused;
        body.addView(Daybook.text(activity,"Source audio",12,Daybook.muted(activity),true));Ui.space(body,8);
        playTime=Daybook.text(activity,(playing==note.optLong("id")?clock(position()):"0:00")+" / "+clock(note.optLong("duration")),16,Daybook.ink(activity),true);body.addView(playTime);Ui.space(body,8);
        playWave=new Wave(activity);playWave.show(barsOf(note.optString("wave")),playing==note.optLong("id")&&totalDuration>0?position()/(float)totalDuration:0);body.addView(playWave,new LinearLayout.LayoutParams(-1,Ui.dp(activity,56)));Ui.space(body,12);
        LinearLayout controls=Ui.row(activity);controls.setGravity(android.view.Gravity.CENTER);
        TextView back5=Daybook.text(activity,"−5s",14,Daybook.muted(activity),true);back5.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8));back5.setOnClickListener(v->{Daybook.feedback(v);nudge(-5000);});controls.addView(back5);
        LinearLayout.LayoutParams mid=new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1);mid.leftMargin=Ui.dp(activity,8);mid.rightMargin=Ui.dp(activity,8);controls.addView(Daybook.button(activity,on?"Pause":"Play",true,v->togglePlay(activity,note,refresh)),mid);
        TextView forward=Daybook.text(activity,"+5s",14,Daybook.muted(activity),true);forward.setPadding(Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8),Ui.dp(activity,8));forward.setOnClickListener(v->{Daybook.feedback(v);nudge(5000);});controls.addView(forward);body.addView(controls);if(on)armTick();
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
            if(playing>=0 && player!=null && !paused && !loading){
                float p=totalDuration<=0?0:Math.min(1f,position()/(float)totalDuration);
                if(playWave!=null)playWave.show(null,p);
                if(!seeking){if(playTime!=null)playTime.setText(clock(position())+" / "+clock(totalDuration));if(playSeek!=null)playSeek.setProgress(totalDuration<=0?0:(int)(1000*position()/totalDuration));}
                again=true;
            }
            if(again)tick.postDelayed(this,200);
        }
    };
    private static void armTick(){tick.removeCallbacks(pulse);tick.post(pulse);}
    private static long position(){long at=playedBefore;try{if(player!=null&&!loading)at+=player.getCurrentPosition();}catch(Exception ignored){}return at;}
    private static void nudge(int delta){seekTo(position()+delta);}
    private static void seekTo(long target){
        if(player==null||loading||queue.isEmpty())return;Activity activity=screen.get();if(activity==null||activity.isDestroyed())return;
        try{
            PlaybackPosition at=PlaybackPosition.locate(queueSpans,Math.max(0,Math.min(Math.max(0,totalDuration-1),target)));
            if(at.index==queueAt){player.seekTo((int)at.offset);return;}
            MediaPlayer old=player;player=null;old.release();queueAt=at.index;playedBefore=at.before;playQueue(activity,onChange,(int)at.offset);if(!paused)armTick();
        }catch(RuntimeException error){toast(activity,"Could not seek in this recording.");}
    }

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
