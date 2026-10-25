package app.voiceflow;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class CaptureActivity extends Activity implements Dictation.Listener {
    static boolean visible;
    private static CaptureActivity foreground;
    private Dictation dictation;
    private TextView status,subtitle,notice,timer;
    private EditText editor;
    private Button primary,secondary;
    private VoitationGlyph wave;
    private String destination,raw="";
    private boolean finishedResult,delivered,started,interrupted,startPending;
    private long startedAt,duration,thoughtId=-1;
    private boolean thought,cancelled;
    private final Handler handler=new Handler();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); if(!visible) Store.removeStaleAudio(this);
        destination=getIntent().getStringExtra("destination"); if(destination==null) destination="app";
        thought="thought".equals(destination);
        // Mode is fixed when opened. A later focus change never routes a thought into an input.
        if(thought && !Store.quickThoughts(this)){finish();return;}
        Window window=getWindow(); window.setGravity(Gravity.BOTTOM); window.setDimAmount(.22f); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        LinearLayout root=Ui.column(this); root.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24)); root.setBackground(Daybook.shape(this,Daybook.surface(this),28));
        LinearLayout top=Ui.row(this); top.addView(Ui.text(this,"Voitation",20,Daybook.ink(this),true),new LinearLayout.LayoutParams(0,-2,1)); timer=Ui.text(this,"00:00",13,Daybook.muted(this),false); top.addView(timer); root.addView(top); Ui.space(root,22);
        status=Ui.text(this,"Ready when you are",25,Daybook.ink(this),true); root.addView(status); Ui.space(root,8); subtitle=Ui.text(this,Ui.languageName(this)+" · "+(Store.engine(this).equals("cloud")?"Cloud speech":"Android speech"),13,Daybook.muted(this),false); root.addView(subtitle);if(thought){status.setText("Capture a thought");subtitle.setText("Quick thoughts · Saved automatically");}
        wave=new VoitationGlyph(this); wave.setColor(Daybook.ink(this)); root.addView(wave,new LinearLayout.LayoutParams(-1,Ui.dp(this,62)));
        editor=new EditText(this); editor.setTextSize(18); editor.setTextColor(Daybook.ink(this)); editor.setGravity(Gravity.TOP); editor.setHint("Your words will appear here…"); editor.setHintTextColor(Daybook.muted(this)); editor.setPadding(Ui.dp(this,14),Ui.dp(this,14),Ui.dp(this,14),Ui.dp(this,14)); editor.setBackground(Ui.glass(this,16,Glass.DAYBOOK_TRANSLUCENT)); editor.setMinLines(3); editor.setMaxLines(6); editor.setFocusable(false); editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); root.addView(editor,new LinearLayout.LayoutParams(-1,-2));
        Ui.space(root,12); notice=Ui.text(this,"",12,Daybook.muted(this),false); notice.setVisibility(android.view.View.GONE); root.addView(notice); Ui.space(root,12);
        LinearLayout buttons=Ui.row(this); secondary=Ui.button(this,"Close",false,v->finish()); primary=Ui.button(this,"Stop recording",true,v->{ if(finishedResult) accept(); else if(dictation!=null && started) { primary.setEnabled(false); dictation.stop(); } else retry(); });
        tintButton(secondary,false);tintButton(primary,true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,Ui.dp(this,54),1); p.rightMargin=Ui.dp(this,10); buttons.addView(secondary,p); buttons.addView(primary,new LinearLayout.LayoutParams(0,Ui.dp(this,54),1.6f)); root.addView(buttons); Ui.space(root,12); root.addView(Ui.text(this,thought?"Your thought is saved after transcription. The original words are kept; formatting can never replace them. Audio is deleted after transcription.":"Review your words before inserting. Audio is deleted after transcription; if Cloud fails, a private copy is kept for 24 hours so you can retry from Home.",11,Daybook.muted(this),false));
        setContentView(root); window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,600)),WindowManager.LayoutParams.WRAP_CONTENT);
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10); else handler.postDelayed(()->retry(),250);
    }
    private void tintButton(Button button,boolean active) {
        int ink=active?Daybook.actionInk(this):Daybook.ink(this);
        int ripple=(active?Daybook.actionInk(this):Daybook.accent(this)) & 0x00ffffff;
        button.setTextColor(ink);
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ripple|(active?0x30000000:0x18000000)),Ui.glass(this,20,active?Glass.DAYBOOK_TRANSLUCENT_ACTION:Glass.DAYBOOK_TRANSLUCENT),Ui.shape(android.graphics.Color.WHITE,20,this)));
    }
    private void retry() {
        if(isFinishing() || isDestroyed())return;
        if(foreground!=this){startPending=true;return;}
        startPending=false;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            if(shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);
            else startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
            return;
        }
        if(Vault.get(this).isEmpty()){error("Add your API key in Settings to use cloud transcription.");return;}
        if(Dictation.isWorking() || VoiceTake.running() || Translate.going() || LineWire.running()){error("Another recording is still active. Finish it first.");return;}
        if(dictation!=null) dictation.close();finishedResult=false;delivered=false;cancelled=false;thoughtId=-1;interrupted=false;started=true; editor.setText(""); editor.setFocusable(false); editor.setFocusableInTouchMode(false); notice.setVisibility(android.view.View.GONE); primary.setText("Stop recording"); primary.setEnabled(true); startedAt=android.os.SystemClock.elapsedRealtime(); wave.setActive(true);
        dictation=new Dictation(this,this,false,thought); dictation.start(); handler.post(tick);
    }
    private final Runnable tick=new Runnable() { public void run() { if(!started || finishedResult || isFinishing()) return; long s=(android.os.SystemClock.elapsedRealtime()-startedAt)/1000; timer.setText(String.format(java.util.Locale.ROOT,"%02d:%02d",s/60,s%60)); handler.postDelayed(this,500); } };
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) { super.onRequestPermissionsResult(request,permissions,results); if(request==10) { if(results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) retry(); else { started=false; error("Microphone access is needed for dictation. Tap Allow mic to open app settings."); primary.setText("Allow mic"); } } }
    @Override public void state(String text) { wave.setStage(text);
        status.setText(text);
        boolean processing=!(text.startsWith("Listening") || text.startsWith("Starting"));
        if(processing) {
            // Clear processing stages with a usable Cancel; nothing is delivered after Cancel.
            wave.setStage(text); primary.setEnabled(false); timer.setText(FlowAccessibility.shortStage(text));
            secondary.setText("Cancel"); secondary.setOnClickListener(v->cancelProcessing());
        }
    }
    private void cancelProcessing() {
        cancelled=true;
        if(dictation!=null){dictation.cancel();dictation=null;}
        started=false; finishedResult=false; wave.setActive(false); status.setText("Cancelled"); notice.setText("Processing stopped. Any saved recovery audio is available in Voitation."); notice.setVisibility(android.view.View.VISIBLE);
        primary.setText("Try again"); primary.setEnabled(true); secondary.setText("Close"); secondary.setOnClickListener(v->finish());
    }
    @Override public void partial(String text) { editor.setText(text); editor.setSelection(editor.length()); }
    @Override public void level(float level) { wave.setLevel(level); }
    @Override public void result(String raw,String text,String warning) {
        secondary.setText("Close"); secondary.setOnClickListener(v->finish());
        if(cancelled)return;
        wave.setStage("Done");this.raw=raw; duration=android.os.SystemClock.elapsedRealtime()-startedAt; started=false; finishedResult=true; wave.setStage("Done");
        String shown=thought?raw:text;
        editor.setText(shown); editor.setFocusable(true); editor.setFocusableInTouchMode(true);
        if(thought){
            thoughtId=Notes.keepThought(getApplicationContext(),System.currentTimeMillis(),raw,duration);
            delivered=thoughtId>0;status.setText(delivered?"Thought saved.":"Your words, ready.");
            subtitle.setText(delivered?"Thoughts · "+Cleaner.words(raw)+" words":"Could not save yet. Keep this card open and try Save thought.");
            primary.setText(delivered?"Save changes":"Save thought");
            if(delivered){if(dictation!=null)dictation.acknowledgeSaved();Notes.formatThought(getApplicationContext(),thoughtId);}
        } else {status.setText("Your words, ready."); subtitle.setText("Review or edit · "+Cleaner.words(text)+" words");primary.setText(destination.equals("app")?"Keep text":"Insert text");if(interrupted){delivered=Recovery.keep(this,raw,text,duration,false);if(delivered&&dictation!=null)dictation.acknowledgeSaved();}}
        primary.setEnabled(true); secondary.setText("Close");
        if(!warning.isEmpty()) { notice.setText(warning); notice.setVisibility(android.view.View.VISIBLE); }
    }
    @Override public void error(String message) {
        DictationProblem problem=DictationProblem.of(message);android.util.Log.w("VonoTation","In-app dictation blocked: "+problem.title+" | "+problem.detail);
        secondary.setText("Close"); secondary.setOnClickListener(v->finish()); started=false; wave.setStage(DictationProblem.FIX_MICROPHONE.equals(problem.fix)?"Blocked":"Retry"); status.setText(problem.fix!=null?problem.title:"Let’s try again"); notice.setText(problem.fix!=null?DictationProblem.before(!DictationProblem.FIX_MICROPHONE.equals(problem.fix),false).detail:message); notice.setVisibility(android.view.View.VISIBLE);
        if(problem.fix!=null){final String fix=problem.fix;primary.setText(DictationProblem.FIX_API_KEY.equals(fix)?"Open Settings":"Allow microphone");primary.setEnabled(true);primary.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_FIX,fix));finish();});}
        else { primary.setText("Try again"); primary.setEnabled(true); }
    }
    private void accept() {
        String text=editor.getText().toString().trim(); if(text.isEmpty()) { notice.setText("Add some text or try another recording."); notice.setVisibility(android.view.View.VISIBLE); return; }
        if(thought){
            boolean newlySaved=false;
            if(!delivered){thoughtId=Notes.keepThought(this,System.currentTimeMillis(),raw,duration);delivered=thoughtId>0;newlySaved=delivered;}
            if(!delivered){notice.setText("Could not save your thought. Your words are still here. Try again.");notice.setVisibility(android.view.View.VISIBLE);return;}
            if(!Notes.hasThought(this,thoughtId)){notice.setText("This thought was deleted. Your words are still here to copy.");notice.setVisibility(android.view.View.VISIBLE);return;}
            if(QuickThoughtPolicy.hasUserEdits(raw,text)){
                if(!Notes.editThought(this,thoughtId,text)){notice.setText("Could not save your changes. Your words are still here. Try again.");notice.setVisibility(android.view.View.VISIBLE);return;}
            }else if(newlySaved)Notes.formatThought(getApplicationContext(),thoughtId);
            if(dictation!=null)dictation.acknowledgeSaved();
            // An untouched raw editor must not overwrite organized text or supersede its in-flight worker.
            finish();return;
        }
        synchronized(Store.class){if(!delivered)delivered=Store.save(this,raw,text,duration);if(delivered&&dictation!=null)dictation.acknowledgeSaved();DraftState.set(text);}finish();
    }
    @Override protected void onResume(){super.onResume();if(thought && !Store.quickThoughts(this)){finish();return;}foreground=this;visible=true;if(startPending){startPending=false;handler.postDelayed(()->retry(),250);}if(FlowAccessibility.instance!=null)FlowAccessibility.instance.updateBubble();}
    @Override protected void onPause() { super.onPause();if(foreground==this){foreground=null;visible=false;}if(started && dictation!=null){interrupted=true;dictation.stop();}if(FlowAccessibility.instance!=null)FlowAccessibility.instance.updateBubble(); }
    @Override public void onDestroy() {
        if(foreground==this){foreground=null;visible=false;}handler.removeCallbacksAndMessages(null);if(dictation!=null){
            if(started && thought && !cancelled){
                final android.content.Context app=getApplicationContext();final long elapsed=android.os.SystemClock.elapsedRealtime()-startedAt;
                final Dictation finishing=dictation;
                dictation.finishDetached(new Dictation.Listener(){
                    public void state(String value){}public void partial(String value){}public void level(float value){}
                    public void result(String source,String text,String warning){long id=Notes.keepThought(app,System.currentTimeMillis(),source,elapsed);if(id>0){finishing.acknowledgeSaved();Notes.formatThought(app,id);android.widget.Toast.makeText(app,"Thought saved.",android.widget.Toast.LENGTH_SHORT).show();}else android.widget.Toast.makeText(app,"Could not save your thought.",android.widget.Toast.LENGTH_LONG).show();finishing.close();}
                    public void error(String message){android.widget.Toast.makeText(app,message,android.widget.Toast.LENGTH_LONG).show();finishing.close();}
                });
            }else if(started)dictation.finishDetached(android.os.SystemClock.elapsedRealtime()-startedAt);else dictation.close();
        }
        if(finishedResult && !delivered && !thought && Recovery.keep(this,raw,editor.getText().toString(),duration,false)&&dictation!=null)dictation.acknowledgeSaved();
        if(FlowAccessibility.instance!=null) FlowAccessibility.instance.updateBubble(); super.onDestroy();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) { super.onConfigurationChanged(configuration); getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,600)),WindowManager.LayoutParams.WRAP_CONTENT); }
}
