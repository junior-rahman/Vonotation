package app.voiceflow;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/** Face-to-face or one-way. The mic runs by itself: words appear as they are spoken, then the other language is said aloud. */
final class Translate {
    private static final String[] CODES={"en","bn","hi","es","ne"};
    private static final int TOGETHER=0,LISTEN=1;
    private static String from="en",to="bn";
    private static int mode=TOGETHER;
    private static boolean busy,listening,speaking,session,paused,hushed,askedMic;
    private static CaptureGate.Lease earLease;
    private static int sessionKind=TOGETHER,earGen,speakGen;
    private static String live="",liveSide="",heardTag="",lastSaid="";
    private static long lastSaidAt;
    private static TextView liveYours,liveTheirs;
    private static SpeechRecognizer ear;
    private static TextToSpeech voice;
    private static final ArrayList<Turn> turns=new ArrayList<>();
    private static final java.util.concurrent.ExecutorService work=java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final class Turn {
        final String spokenCode,targetCode,original;
        String translation;
        Turn(String spokenCode,String targetCode,String original,String translation){this.spokenCode=spokenCode;this.targetCode=targetCode;this.original=original;this.translation=translation;}
    }

    private Translate() {}
    static void stop(){paused=false;session=false;listening=false;speaking=false;hushed=false;live="";turns.clear();VoiceTake.cancel();dropEar();busy=false;if(voice!=null){voice.stop();voice.shutdown();voice=null;}Runnable refresh=onChange;if(refresh!=null)new android.os.Handler(android.os.Looper.getMainLooper()).post(refresh);}
    static boolean going(){return session;}
    private static Runnable onChange;

    static void show(Activity activity,LinearLayout body,android.widget.FrameLayout dock,Runnable refresh) {
        onChange=refresh;
        if(mode!=LISTEN)mode=TOGETHER;
        liveYours=null;liveTheirs=null;
        TextView which=Daybook.text(activity,mode==LISTEN?"Just listening  ·  tap for both of you":"Both of you  ·  tap to only listen",13,Daybook.accent(activity),true);
        which.setOnClickListener(v->{Daybook.feedback(v);mode=mode==LISTEN?TOGETHER:LISTEN;endListen();refresh.run();});
        body.addView(which);Ui.space(body,12);
        boolean oneWay=mode==LISTEN;
        if(oneWay){pairRow(activity,body,refresh);listenMode(activity,body,refresh);}
        else togetherMode(activity,body,refresh);
        if(dock!=null){dock.removeAllViews();android.widget.Button start=startButton(activity,refresh,oneWay);Daybook.pill(start,!(session && !paused));dock.addView(start,new android.widget.FrameLayout.LayoutParams(-2,Ui.dp(activity,52)));}
        if(session && !paused)body.post(()->arm(activity,refresh,oneWay));
    }

    private static void pairRow(Activity activity,LinearLayout body,Runnable refresh) {
        LinearLayout pair=Ui.row(activity);pair.setGravity(Gravity.CENTER_VERTICAL);
        pair.addView(languageButton(activity,from,code->{from=code;retune();refresh.run();}),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        pair.addView(Daybook.button(activity,"⇄",false,v->{swap();retune();refresh.run();}),new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,44)));
        pair.addView(languageButton(activity,to,code->{to=code;retune();refresh.run();}),new LinearLayout.LayoutParams(0,Ui.dp(activity,44),1));
        body.addView(pair);Ui.space(body,12);
    }

    private static void swap(){String language=from;from=to;to=language;}

    private static void togetherMode(Activity activity,LinearLayout body,Runnable refresh) {
        int viewport=faceViewport(activity,body);
        int chrome=Ui.dp(activity,20+44+14+12+22);
        int gap=Ui.dp(activity,8),midH=Ui.dp(activity,40);
        int room=viewport-chrome-midH-gap*2-Ui.dp(activity,6);
        if(room<Ui.dp(activity,250))room=Ui.dp(activity,250);
        int bottom=room*56/100;
        int top=room-bottom;
        int limit=bottom>=Ui.dp(activity,206)&&top>=Ui.dp(activity,148)?2:1;
        LinearLayout theirs=Ui.column(activity);
        theirs.setBackground(Daybook.shape(activity,Daybook.soft(activity),20));
        theirs.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,14),Ui.dp(activity,12));
        theirs.setGravity(Gravity.CENTER_VERTICAL);
        theirs.setRotation(180f);
        theirs.setClipChildren(true);
        theirs.setContentDescription(Notes.name(to)+" side, upside down for the person across from you");
        fillSide(activity,theirs,to,true,limit,code->{to=code;retune();refresh.run();});
        body.addView(theirs,new LinearLayout.LayoutParams(-1,top));
        Ui.space(body,8);
        LinearLayout mid=Ui.row(activity);mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.addView(Daybook.text(activity,phase(),13,Daybook.muted(activity),false),new LinearLayout.LayoutParams(0,-2,1));
        mid.addView(Daybook.button(activity,"⇄",false,v->{swap();retune();refresh.run();}),new LinearLayout.LayoutParams(Ui.dp(activity,48),Ui.dp(activity,40)));
        body.addView(mid);
        Ui.space(body,8);
        LinearLayout yours=Daybook.card(activity);yours.setPadding(Ui.dp(activity,14),Ui.dp(activity,12),Ui.dp(activity,14),Ui.dp(activity,12));
        yours.setClipChildren(true);
        android.widget.ScrollView words=new android.widget.ScrollView(activity);
        words.setFillViewport(true);words.setVerticalScrollBarEnabled(false);
        LinearLayout lines=Ui.column(activity);
        fillSide(activity,lines,from,false,limit,code->{from=code;retune();refresh.run();});
        words.addView(lines,new android.widget.ScrollView.LayoutParams(-1,-2));
        yours.addView(words,new LinearLayout.LayoutParams(-1,-1));
        body.addView(yours,new LinearLayout.LayoutParams(-1,bottom));
    }

    /** Scroll viewport, so the two halves plus Listen stay on screen. */
    private static int faceViewport(Activity activity,LinearLayout body) {
        android.view.View frame=body.getParent() instanceof android.view.View?(android.view.View)body.getParent():null;
        android.view.View scroller=frame!=null&&frame.getParent() instanceof android.view.View?(android.view.View)frame.getParent():null;
        if(scroller!=null&&scroller.getHeight()>0)return scroller.getHeight();
        return activity.getResources().getDisplayMetrics().heightPixels-Ui.dp(activity,168);
    }

    private static void listenMode(Activity activity,LinearLayout body,Runnable refresh) {
        String status=phase();
        body.addView(Daybook.text(activity,status,14,Daybook.muted(activity),false));
        TextView hearing=Daybook.text(activity,live.isEmpty()?" ":live,18,Daybook.accent(activity),false);
        hearing.setMaxLines(3);liveYours=hearing;
        if(!from.equals(liveSide) || live.isEmpty())hearing.setVisibility(android.view.View.GONE);
        Ui.space(body,8);body.addView(hearing);
        ArrayList<Turn> shown=new ArrayList<>();
        for(int i=Math.max(0,turns.size()-6);i<turns.size();i++)shown.add(turns.get(i));
        if(shown.isEmpty()){
            Ui.space(body,12);
            LinearLayout hint=Daybook.card(activity);hint.setPadding(Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14),Ui.dp(activity,14));
            hint.addView(Daybook.text(activity,"Tap Start, then just talk. It says the translation out loud.",14,Daybook.ink(activity),false));
            body.addView(hint);return;
        }
        Ui.space(body,12);
        for(int i=shown.size()-1;i>=0;i--){
            Turn turn=shown.get(i);boolean latest=i==shown.size()-1;
            LinearLayout card=Daybook.card(activity);card.setPadding(Ui.dp(activity,12),Ui.dp(activity,10),Ui.dp(activity,12),Ui.dp(activity,10));
            TextView heard=Daybook.text(activity,turn.original,latest?15:13,Daybook.muted(activity),false);heard.setTextIsSelectable(true);card.addView(heard);
            Ui.space(card,4);
            LinearLayout line=Ui.row(activity);line.setGravity(Gravity.CENTER_VERTICAL);
            TextView said=Daybook.text(activity,turn.translation,latest?18:15,Daybook.ink(activity),latest);said.setTextIsSelectable(true);
            line.addView(said,new LinearLayout.LayoutParams(0,-2,1));
            if(latest)line.addView(hear(activity,turn.translation,turn.targetCode));
            card.addView(line);
            body.addView(card);Ui.space(body,8);
        }
    }

    private static void fillSide(Activity activity,LinearLayout pane,String side,boolean flipped,int limit,java.util.function.Consumer<String> onPick) {
        ArrayList<String> lines=sideLines(side,limit);
        if(flipped){
            String line=lines.isEmpty()?"":lines.get(lines.size()-1);
            if(line.isEmpty() && live.isEmpty())pane.addView(Daybook.text(activity,"The translation shows here.",15,Daybook.muted(activity),false));
            else if(!line.isEmpty()){
                TextView text=Daybook.text(activity,line,22,Daybook.ink(activity),true);
                text.setGravity(Gravity.CENTER);text.setTextIsSelectable(true);
                pane.addView(text,new LinearLayout.LayoutParams(-1,-2));
            }
            pane.addView(liveLine(activity,side));
            Ui.space(pane,6);
            pane.addView(languageTag(activity,side,onPick));
            return;
        }
        pane.addView(languageTag(activity,side,onPick));
        Ui.space(pane,6);
        if(lines.isEmpty() && live.isEmpty())pane.addView(Daybook.text(activity,session?"Just talk. Either language.":"Tap Start. Either of you can talk.",13,Daybook.muted(activity),false));
        else for(int i=0;i<lines.size();i++)addWords(activity,pane,lines.get(i),i==lines.size()-1 && live.isEmpty(),side);
        pane.addView(liveLine(activity,side));
    }

    private static TextView liveLine(Activity activity,String side) {
        boolean mine=side.equals(liveSide) && !live.isEmpty();
        TextView line=Daybook.text(activity,mine?live:" ",18,Daybook.accent(activity),false);
        line.setMaxLines(3);line.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if(!mine)line.setVisibility(android.view.View.GONE);
        if(side.equals(from))liveYours=line;else liveTheirs=line;
        return line;
    }

    private static void paintLive() {
        boolean yours=from.equals(liveSide) && !live.isEmpty();
        boolean theirs=to.equals(liveSide) && !live.isEmpty();
        if(liveYours!=null && liveYours.isAttachedToWindow()){liveYours.setText(yours?live:" ");liveYours.setVisibility(yours?android.view.View.VISIBLE:android.view.View.GONE);}
        if(liveTheirs!=null && liveTheirs.isAttachedToWindow()){liveTheirs.setText(theirs?live:" ");liveTheirs.setVisibility(theirs?android.view.View.VISIBLE:android.view.View.GONE);}
    }

    private static String phase() {
        if(!session)return "Tap Start";
        if(paused)return "Paused";
        if(speaking)return "Saying the other language…";
        if(busy)return "Translating both ways…";
        return mode==LISTEN?"Listening":"Conversation · either language";
    }

    private static ArrayList<String> sideLines(String side,int limit) {
        ArrayList<String> lines=new ArrayList<>();
        int start=Math.max(0,turns.size()-8);
        for(int i=start;i<turns.size();i++){
            Turn turn=turns.get(i);
            if(side.equals(turn.targetCode) && fits(side,turn.translation))lines.add(turn.translation);
            else if(side.equals(turn.spokenCode) && fits(side,turn.original))lines.add(turn.original);
        }
        if(limit<1)limit=1;
        if(lines.size()>limit)return new ArrayList<>(lines.subList(lines.size()-limit,lines.size()));
        return lines;
    }

    /** A line only belongs on a side when it is actually written in that language. */
    private static boolean fits(String side,String text){
        if(text==null)return false;
        String trimmed=text.trim();
        return !trimmed.isEmpty() && !"…".equals(trimmed) && !LanguagePolicy.wrongScript(side,trimmed);
    }

    private static void addWords(Activity activity,LinearLayout pane,String words,boolean latest,String side) {
        if(pane.getChildCount()>0)Ui.space(pane,8);
        LinearLayout row=Ui.row(activity);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text=Daybook.text(activity,words,latest?18:14,latest?Daybook.ink(activity):Daybook.muted(activity),latest);
        text.setMaxLines(latest?4:1);text.setEllipsize(android.text.TextUtils.TruncateAt.END);text.setTextIsSelectable(true);
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        if(latest)row.addView(hear(activity,words,side));
        pane.addView(row);
    }

    private static TextView languageTag(Activity activity,String selected,java.util.function.Consumer<String> onPick) {
        TextView label=Daybook.text(activity,Notes.name(selected),14,Daybook.accent(activity),true);
        label.setMinHeight(Ui.dp(activity,36));label.setGravity(Gravity.CENTER_VERTICAL);
        label.setOnClickListener(v->{Daybook.feedback(v);onPick.accept(next(selected));});
        label.setContentDescription(Notes.name(selected)+". Tap for the next language.");
        return label;
    }

    private static void retune(){dropEar();listening=false;hushed=false;live="";}

    private static android.widget.Button startButton(Activity activity,Runnable refresh,boolean oneWay) {
        boolean live=session && !paused;
        boolean note=VoiceTake.noteRunning() || VoiceTake.thoughtRunning();
        boolean call=VoiceTake.callRunning() || LineWire.running();
        android.widget.Button button=Daybook.button(activity,live?"Pause":"Start",!live && !note && !call,v->{
            if(live){paused=true;endListen();refresh.run();return;}
            if(note){android.widget.Toast.makeText(activity,"A voice note is still recording. Finish it first.",android.widget.Toast.LENGTH_LONG).show();return;}
            if(call){android.widget.Toast.makeText(activity,"A call is being recorded. Wait until it ends.",android.widget.Toast.LENGTH_LONG).show();return;}
            paused=false;arm(activity,refresh,oneWay);refresh.run();
        });
        if(!live && (note||call))button.setAlpha(0.4f);
        return button;
    }

    private static void arm(Activity activity,Runnable refresh,boolean oneWay) {
        if(paused||speaking||hushed||activity.isFinishing())return;
        if(Dictation.isWorking()){Toast.makeText(activity,"Finish your dictation first.",Toast.LENGTH_LONG).show();return;}
        if(CaptureBarrier.installPending(activity)){Toast.makeText(activity,"Finish or cancel the app update before recording.",Toast.LENGTH_LONG).show();return;}
        if(VoiceTake.noteRunning() || VoiceTake.thoughtRunning())return;
        if(VoiceTake.callRunning() || LineWire.running())return;
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            if(!askedMic){askedMic=true;activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},22);}
            return;
        }
        askedMic=false;
        // Conversation must hear both languages. The on-device recognizer stays stuck on one, so this path uses the cloud model and picks the side from the words.
        if(!oneWay){dropEar();if(listening||VoiceTake.running())return;capture(activity,refresh,false);return;}
        if(ear!=null && listening && sessionKind==LISTEN)return;
        if(!SpeechRecognizer.isRecognitionAvailable(activity)){capture(activity,refresh,oneWay);return;}
        dropEar();
        earLease=CaptureBarrier.begin(activity);
        if(earLease==null){Toast.makeText(activity,"Finish or cancel the app update before recording.",Toast.LENGTH_LONG).show();return;}
        session=true;sessionKind=oneWay?LISTEN:TOGETHER;listening=true;hushed=false;
        final int gen=++earGen;
        try{
        ear=SpeechRecognizer.createSpeechRecognizer(activity.getApplicationContext());
        ear.setRecognitionListener(new RecognitionListener(){
            public void onReadyForSpeech(Bundle p){}
            public void onBeginningOfSpeech(){}
            public void onRmsChanged(float r){}
            public void onBufferReceived(byte[] b){}
            public void onEndOfSpeech(){}
            public void onEvent(int t,Bundle p){}
            public void onError(int code){
                if(gen!=earGen||hushed||paused||speaking)return;
                listening=false;
                if(code==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS){session=false;dropEar();refresh.run();return;}
                if(code==SpeechRecognizer.ERROR_CLIENT && !SpeechRecognizer.isRecognitionAvailable(activity)){capture(activity,refresh,oneWay);return;}
                activity.getWindow().getDecorView().postDelayed(()->{if(gen==earGen && session && !paused && !speaking && !hushed)startEar();},280);
            }
            public void onPartialResults(Bundle partial){if(gen==earGen && !hushed && !speaking)showLive(words(partial),oneWay);}
            public void onResults(Bundle results){if(gen==earGen)take(activity,refresh,words(results),oneWay);}
            public void onSegmentResults(Bundle segment){if(gen==earGen)take(activity,refresh,words(segment),oneWay);}
            public void onEndOfSegmentedSession(){if(gen==earGen && session && !paused && !speaking && !hushed)startEar();}
            public void onLanguageDetection(Bundle bundle){String tag=bundle==null?null:bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE);if(tag!=null)heardTag=tag;}
        });
        startEar();
        }catch(RuntimeException unavailable){dropEar();session=false;listening=false;Toast.makeText(activity,"The speech engine could not start. Try again.",Toast.LENGTH_LONG).show();refresh.run();}
    }

    private static void startEar() {
        if(ear==null||paused||speaking||hushed)return;
        listening=true;
        try{ear.startListening(listenIntent(sessionKind==LISTEN));}catch(RuntimeException ignored){listening=false;}
    }

    private static Intent listenIntent(boolean oneWay) {
        Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,600);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,450);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,400);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,locale(oneWay?from:from).toLanguageTag());
        if(Build.VERSION.SDK_INT>=33)intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION,true);
        if(!oneWay && Build.VERSION.SDK_INT>=34){
            ArrayList<String> allowed=new ArrayList<>();
            allowed.add(locale(from).toLanguageTag());
            String other=locale(to).toLanguageTag();
            if(!allowed.contains(other))allowed.add(other);
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,allowed);
            intent.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,allowed);
        }
        return intent;
    }

    private static String words(Bundle bundle) {
        if(bundle==null)return "";
        ArrayList<String> list=bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list==null||list.isEmpty()||list.get(0)==null?"":list.get(0).trim();
    }

    private static void showLive(String text,boolean oneWay) {
        if(text.isEmpty())return;
        live=text;
        String side=oneWay?from:LanguagePolicy.sideFor(text,heardTag,from,to);
        liveSide=side==null?from:side;
        paintLive();
    }

    private static void take(Activity activity,Runnable refresh,String text,boolean oneWay) {
        if(text.isEmpty()||hushed||speaking)return;
        long now=android.os.SystemClock.elapsedRealtime();
        if(text.equals(lastSaid) && now-lastSaidAt<2000)return;
        lastSaid=text;lastSaidAt=now;
        live="";paintLive();
        String spoken=oneWay?from:codeOfTag(heardTag);
        if(!oneWay){
            String byScript=LanguagePolicy.sideFor(text,heardTag,from,to);
            if(byScript!=null)spoken=byScript;
        }
        if(spoken==null || (!spoken.equals(from) && !spoken.equals(to)))spoken=oneWay?from:LanguagePolicy.sideFor(text,heardTag,from,to);
        if(spoken==null)spoken=oneWay?from:to;
        String target=spoken.equals(to)?from:to;
        heardTag="";
        Turn turn=new Turn(spoken,target,text,"…");
        turns.add(turn);if(turns.size()>24)turns.remove(0);
        busy=true;refresh.run();
        final String saySide=spoken,hearSide=target;
        work.execute(()->{
            String translated;
            try{translated=new CloudApi(activity).translate(text,Notes.name(saySide),Notes.name(hearSide));}
            catch(Exception error){translated=null;}
            final String line=translated;
            activity.runOnUiThread(()->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                busy=false;
                if(line==null || line.trim().isEmpty() || LanguagePolicy.wrongScript(hearSide,line)){turns.remove(turn);refresh.run();if(session && !paused){if(oneWay)startEar();else capture(activity,refresh,false);}return;}
                turn.translation=line.trim();
                speakOut(activity,refresh,line.trim(),hearSide,oneWay);
            });
        });
    }

    /** Say the translation, then open the mic again for whichever person speaks next. */
    private static void speakOut(Activity activity,Runnable refresh,String line,String hearSide,boolean oneWay) {
        final int gen=++speakGen;
        speaking=true;hushed=true;listening=false;
        VoiceTake.cancel();hush();refresh.run();
        sayThen(activity,line,hearSide,()->{
            if(gen!=speakGen||activity.isFinishing()||activity.isDestroyed())return;
            speaking=false;hushed=false;
            if(session && !paused){if(oneWay)startEar();else capture(activity,refresh,false);}
            else refresh.run();
        });
    }

    private static void hush(){listening=false;try{if(ear!=null)ear.cancel();}catch(RuntimeException ignored){}}

    private static void dropEar(){earGen++;SpeechRecognizer gone=ear;ear=null;if(gone!=null){try{gone.cancel();}catch(RuntimeException ignored){}try{gone.destroy();}catch(RuntimeException ignored){}}CaptureGate.Lease lease=earLease;earLease=null;if(lease!=null)lease.close();}

    private static String codeOfTag(String tag) {
        if(tag==null)return null;
        String t=tag.toLowerCase(Locale.ROOT);
        if(t.startsWith("bn"))return "bn";
        if(t.startsWith("hi"))return "hi";
        if(t.startsWith("ne"))return "ne";
        if(t.startsWith("es"))return "es";
        if(t.startsWith("en"))return "en";
        return null;
    }

    private static void controls(Activity activity,LinearLayout host,Runnable refresh,boolean oneWay) {
        host.addView(Daybook.text(activity,phase(),13,Daybook.muted(activity),false));
    }

    private static void capture(Activity activity,Runnable refresh,boolean oneWay) {
        if(listening||paused||speaking||hushed)return;
        if(Dictation.isWorking()){session=false;paused=true;dropEar();refresh.run();return;}
        if(CaptureBarrier.installPending(activity)){session=false;listening=false;Toast.makeText(activity,"Finish or cancel the app update before recording.",Toast.LENGTH_LONG).show();refresh.run();return;}
        if(activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){if(!askedMic){askedMic=true;activity.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},22);}return;}
        session=true;sessionKind=oneWay?LISTEN:TOGETHER;listening=true;
        VoiceTake.begin(activity,new VoiceTake.Listener(){
            public void listening(){activity.runOnUiThread(refresh);}
            public void failed(String message){activity.runOnUiThread(()->{listening=false;if(keepGoing(message)&&!paused&&!speaking){activity.getWindow().getDecorView().postDelayed(()->{if(session&&!paused&&!speaking)capture(activity,refresh,oneWay);},350);return;}endListen();paused=true;if(message!=null&&!message.isEmpty())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();refresh.run();});}
            public void finished(File file){activity.runOnUiThread(()->{listening=false;interpret(activity,file,refresh,oneWay);});}
        },true);
        refresh.run();
    }

    /** Silence and a missed phrase should not end a conversation. A real failure should. */
    private static boolean keepGoing(String message){
        if(!session || message==null)return false;
        return message.contains("Nothing was recorded") || message.contains("No speech") || message.contains("Didn't catch");
    }

    private static void endListen(){session=false;listening=false;speaking=false;hushed=false;live="";VoiceTake.cancel();dropEar();if(voice!=null)voice.stop();}

    private static void interpret(Activity activity,File file,Runnable refresh,boolean oneWay) {
        listening=false;
        busy=true;refresh.run();
        String sideA=from,sideB=to;
        work.execute(()->{
            try {
                String[] heard=hear(new CloudApi(activity),file,oneWay?sideA:null,sideA,sideB);
                String text=undouble(heard[0]),spoken=heard[1];
                String target=spoken.equals(sideB)?sideA:sideB;
                String translated=new CloudApi(activity).translate(text.trim(),Notes.name(spoken),Notes.name(target));
                file.delete();
                Turn turn=new Turn(spoken,target,text.trim(),translated);
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    turns.add(turn);if(turns.size()>24)turns.remove(0);
                    busy=false;
                    speakOut(activity,refresh,translated,target,oneWay);
                });
            } catch(Exception error) {
                file.delete();
                String message=error.getMessage()==null?"Translation failed.":error.getMessage();
                activity.runOnUiThread(()->{
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    busy=false;speaking=false;
                    boolean quiet=message.contains("wrong alphabet") || message.contains("No speech") || message.contains("Nothing was recorded");
                    if(!quiet && message!=null && !message.isEmpty())Toast.makeText(activity,message,Toast.LENGTH_LONG).show();
                    if(session && !paused){capture(activity,refresh,oneWay);return;}
                    refresh.run();
                });
            }
        });
    }

    /** Whisper often repeats the same sentence. Keep the first copy. */
    private static String undouble(String text) {
        if(text==null)return "";
        String[] parts=text.trim().split("(?<=[.!?])\\s+");
        if(parts.length<2)return text.trim();
        StringBuilder kept=new StringBuilder();
        String previous="";
        for(String part:parts){
            String piece=part.trim();
            if(piece.isEmpty() || sameWords(previous,piece))continue;
            if(kept.length()>0)kept.append(' ');
            kept.append(piece);
            previous=piece;
        }
        return kept.length()==0?text.trim():kept.toString();
    }

    private static boolean sameWords(String a,String b) {
        if(a.isEmpty() || b.isEmpty())return false;
        java.util.HashSet<String> left=wordsOf(a),right=wordsOf(b);
        if(left.isEmpty() || right.isEmpty())return false;
        int shared=0;
        for(String word:right)if(left.contains(word))shared++;
        int smaller=Math.min(left.size(),right.size());
        return shared*10>=smaller*7;
    }

    private static java.util.HashSet<String> wordsOf(String text) {
        java.util.HashSet<String> words=new java.util.HashSet<>();
        for(String word:text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(word.length()>1)words.add(word);
        return words;
    }

    /** hint forces one language. Null means either person may be speaking, and a wrong alphabet is tried again in each selected language. */
    private static String[] hear(CloudApi api,File file,String hint,String sideA,String sideB) throws Exception {
        String[] detected=new String[]{""};
        String text,spoken;
        if(hint!=null){
            text=api.transcribe(file,true,null,hint,detected,"translate_transcribe_model");
            spoken=hint;
        } else {
            text=api.transcribe(file,true,null,null,detected,"translate_transcribe_model",LanguagePolicy.pairPrompt(sideA,sideB));
            spoken=LanguagePolicy.sideFor(text,detected[0],sideA,sideB);
            if(text==null || spoken==null || LanguagePolicy.wrongScript(spoken,text)){
                String best=null,bestSide=null;
                for(String side:new String[]{sideB,sideA}){
                    String[] again=new String[]{""};
                    String attempt=api.transcribe(file,true,null,side,again,"translate_transcribe_model");
                    if(attempt==null || attempt.trim().isEmpty() || LanguagePolicy.wrongScript(side,attempt))continue;
                    best=attempt.trim();bestSide=side;break;
                }
                if(best==null)throw new java.io.IOException("Didn't catch that in "+Notes.name(sideA)+" or "+Notes.name(sideB)+".");
                text=best;spoken=bestSide;
            }
        }
        if(text==null || text.trim().isEmpty()) throw new java.io.IOException("Didn't catch that.");
        return new String[]{text.trim(),spoken};
    }

    private static String next(String selected){int index=0;for(int i=0;i<CODES.length;i++)if(CODES[i].equals(selected))index=i;return CODES[(index+1)%CODES.length];}

    private static android.widget.Button languageButton(Activity activity,String selected,java.util.function.Consumer<String> onPick) {
        return Daybook.button(activity,Notes.name(selected),false,v->onPick.accept(next(selected)));
    }

    private static TextView hear(Activity activity,String text,String code) {
        TextView play=Daybook.text(activity,"Hear",13,Daybook.accent(activity),true);
        play.setMinWidth(Ui.dp(activity,48));play.setMinHeight(Ui.dp(activity,44));play.setGravity(Gravity.CENTER);
        play.setContentDescription("Hear this");
        play.setOnClickListener(v->{Daybook.feedback(v);say(activity,text,code);});
        return play;
    }

    private static void say(Activity activity,String text,String code) { sayThen(activity,text,code,null); }

    private static void sayThen(Activity activity,String text,String code,Runnable after) {
        if(text==null || text.trim().isEmpty()){if(after!=null)after.run();return;}
        Runnable speak=()->{
            if(voice==null){if(after!=null)after.run();return;}
            int ready=voice.setLanguage(locale(code));
            if(ready==TextToSpeech.LANG_MISSING_DATA || ready==TextToSpeech.LANG_NOT_SUPPORTED){
                if(after!=null)after.run();
                Toast.makeText(activity,"This phone has no "+Notes.name(code)+" voice. The words are still on screen.",Toast.LENGTH_LONG).show();
                return;
            }
            voice.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){if(after!=null)activity.runOnUiThread(after);}
                public void onError(String id){if(after!=null)activity.runOnUiThread(after);}
            });
            voice.speak(text,TextToSpeech.QUEUE_FLUSH,null,"voiceflow-"+System.nanoTime());
        };
        if(voice==null)voice=new TextToSpeech(activity.getApplicationContext(),status->{if(status==TextToSpeech.SUCCESS)activity.runOnUiThread(speak);else if(after!=null)activity.runOnUiThread(after);});
        else speak.run();
    }

    private static Locale locale(String code) {
        if(code.equals("en"))return Locale.ENGLISH;
        if(code.equals("es"))return new Locale("es");
        if(code.equals("hi"))return new Locale("hi","IN");
        if(code.equals("ne"))return new Locale("ne","NP");
        return new Locale("bn","BD");
    }
}
