package app.voiceflow;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** Actual native Daybook; preview samples never enter this Activity. */
public final class MainActivity extends Activity {
    /** Set by the bubble/keyboard "Fix" action: DictationProblem.FIX_API_KEY or FIX_MICROPHONE. */
    static final String EXTRA_FIX="voiceflow_fix";
    static String currentDraft="";
    private LinearLayout body,root,header,strip,footer;
    private ScrollView scroll;
    private LinearLayout[] deckPages;
    private ScrollView[] deckScrolls;
    private android.widget.FrameLayout[] deckDocks;
    private CardDeck deck;
    private boolean deckReady;
    private boolean settingsVisible,hasRendered,scrollRestorePending,backupReturnFocus;
    private String panel="",origin="",renderedScreen="home",heardDraft="",preferredDraft="",homeTab="days";
    private EditText heardField,preferredField;
    private int historyScrollY,settingsScrollY,scrollGeneration,part;
    private HorizontalScrollView pagerView;
    private AudioTrack chimeTrack;
    private boolean quietScroll;
    private final java.util.Set<String> expandedDays=new java.util.HashSet<>(),collapsedDays=new java.util.HashSet<>(),expandedEntries=new java.util.HashSet<>();
    private final java.util.Map<String,DayCard> dayCards=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,View> settingRows=new java.util.LinkedHashMap<>();
    private final java.util.concurrent.ExecutorService summaryWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int summaryGeneration;
    private volatile CloudApi summaryApi;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);Store.migrate(this);ClipList.watch(this);if(!CaptureActivity.visible)Store.removeStaleAudio(this);
        if(state!=null){settingsVisible=state.getBoolean("settings_visible",false);backupReturnFocus=state.getBoolean("backup_return_focus",false);panel=state.getString("daybook_panel","");origin=state.getString("daybook_origin","");homeTab=state.getString("home_tab","days");historyScrollY=Math.max(0,state.getInt("history_scroll_y",0));settingsScrollY=Math.max(0,state.getInt("settings_scroll_y",0));DraftState.restore(this,state);restoreSet(state,"days_open",expandedDays);restoreSet(state,"days_closed",collapsedDays);restoreSet(state,"entries_open",expandedEntries);heardDraft=state.getString("dictionary_heard","");preferredDraft=state.getString("dictionary_preferred","");}
        createShell();
        if(state==null)handleFix(getIntent());
        openCalls(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handleFix(intent);openCalls(intent);}
    private void openCalls(Intent intent){if(intent!=null && intent.getBooleanExtra("open_calls",false)){intent.removeExtra("open_calls");settingsVisible=false;panel="";part=1;}}
    /** Opens the setting a dictation problem points to. */
    private void handleFix(Intent intent){
        String fix=intent==null?null:intent.getStringExtra(EXTRA_FIX);if(fix==null)return;intent.removeExtra(EXTRA_FIX);openedForFix=true;
        if(fix.equals(DictationProblem.FIX_API_KEY)){settingsVisible=true;panel="";origin="connection";render();getWindow().getDecorView().post(this::apiDialog);}
        else if(fix.equals(DictationProblem.FIX_MICROPHONE)){settingsVisible=true;panel="";render();getWindow().getDecorView().post(this::manageMicrophone);}
        else if(fix.equals(BubbleHealth.FIX_ACCESSIBILITY)){settingsVisible=false;render();getWindow().getDecorView().post(this::restoreBubble);}
    }
    /** Asks once for notifications (Android 13+) after the microphone is allowed, so the recording notice and alerts can show. */
    private boolean openedForFix;
    private void maybeRequestNotifications(){
        if(openedForFix){openedForFix=false;return;} // never stack a second permission dialog on a Fix screen
        if(android.os.Build.VERSION.SDK_INT<33 || !microphoneAllowed() || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)return;
        if(getPreferences(MODE_PRIVATE).getBoolean("notifications_requested",false))return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested",true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);
    }
    private void restoreSet(Bundle state,String key,java.util.Set<String> target){java.util.ArrayList<String> saved=state.getStringArrayList(key);if(saved!=null)target.addAll(saved);}
    @Override protected void onResume(){super.onResume();DriveBackup.schedule(this);Notes.catchUp(this);LineWatch.start(this,()->{if(!isDestroyed())render();});maybeRequestNotifications();boolean focusBackup=backupReturnFocus;backupReturnFocus=false;render();AppUpdate.resume(this);if(focusBackup&&panel.isEmpty()){View row=settingRows.get("backup");if(row!=null)row.post(row::requestFocusFromTouch);}}
    @Override public void onWindowFocusChanged(boolean hasFocus){super.onWindowFocusChanged(hasFocus);if(hasFocus&&settingsVisible&&panel.equals("permissions"))render();}
    @Override protected void onPause(){AppUpdate.pause(this);saveScrollPosition();rememberDictionaryDraft();stopSummaryWork();super.onPause();}
    @Override protected void onDestroy(){if(retryDictation!=null){retryDictation.cancel();retryDictation=null;}Notes.stop();Translate.stop();scrollGeneration++;stopSummaryWork();summaryWorker.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){saveScrollPosition();rememberDictionaryDraft();state.putInt("history_scroll_y",historyScrollY);state.putInt("settings_scroll_y",settingsScrollY);state.putBoolean("settings_visible",settingsVisible);state.putBoolean("backup_return_focus",backupReturnFocus);state.putString("daybook_panel",panel);state.putString("daybook_origin",origin);state.putString("home_tab",homeTab);DraftState.save(this,state);state.putStringArrayList("days_open",new java.util.ArrayList<>(expandedDays));state.putStringArrayList("days_closed",new java.util.ArrayList<>(collapsedDays));state.putStringArrayList("entries_open",new java.util.ArrayList<>(expandedEntries));state.putString("dictionary_heard",heardDraft);state.putString("dictionary_preferred",preferredDraft);super.onSaveInstanceState(state);}
    @Override public void onBackPressed(){if(!panel.isEmpty())backToSettings();else if(settingsVisible){settingsVisible=false;render();}else if(Notes.opened()){Notes.back();render();}else super.onBackPressed();}
    private void createShell() {
        root=Ui.column(this);root.setBackground(new Daybook.Backdrop(this));getWindow().setStatusBarColor(Daybook.paper(this));getWindow().setNavigationBarColor(Daybook.paper(this));
        boolean light=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(light?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        root.setOnApplyWindowInsetsListener((view,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}return insets;});
        header=Ui.row(this);header.setPadding(Ui.dp(this,20),Ui.dp(this,18),Ui.dp(this,20),0);FrameLayout headFrame=new FrameLayout(this);headFrame.addView(header,new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,520)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));root.addView(headFrame,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=Ui.column(this);body.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,8));
        FrameLayout center=new FrameLayout(this);center.addView(body,new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,520)),-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));scroll.addView(center);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        strip=Ui.column(this);strip.setPadding(0,0,0,0);root.addView(strip,new LinearLayout.LayoutParams(-1,0,1));
        footer=Ui.row(this);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(Ui.dp(this,20),Ui.dp(this,6),Ui.dp(this,16),Ui.dp(this,8));root.addView(footer,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
    }
    private String screen(){return !settingsVisible?"home":panel.isEmpty()?"settings":"panel:"+panel;}
    private void render() {
        if(body==null)return;if("advanced".equals(panel))panel="";
        int expectedCards=Store.quickThoughts(this)?5:4;
        if(deckPages==null || deckPages.length!=expectedCards)deckReady=false;
        if(part>=expectedCards)part=0;
        if(!Store.quickThoughts(this) && Notes.thoughtOpened(this))Notes.back();
        boolean detail=!settingsVisible && Notes.opened();
        boolean deckHome=!settingsVisible && !detail;
        if(deckHome && deckReady && strip!=null && strip.getChildCount()>0){fillDeck();return;}
        saveScrollPosition();rememberDictionaryDraft();renderedScreen=screen();hasRendered=true;stopSummaryWork();dayCards.clear();settingRows.clear();heardField=null;preferredField=null;body.removeAllViews();header.removeAllViews();if(footer!=null)footer.removeAllViews();if(strip!=null)strip.removeAllViews();deckReady=false;deck=null;
        if(settingsVisible){((View)header.getParent()).setVisibility(View.VISIBLE);if(footer!=null)footer.setVisibility(View.GONE);TextView back=Daybook.text(this,"‹",30,Daybook.accent(this),false);back.setGravity(Gravity.CENTER);back.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,14));back.setContentDescription(panel.isEmpty()?"Back to Home":"Back to Settings");back.setTooltipText(back.getContentDescription());back.setOnClickListener(v->{Daybook.feedback(v);if(panel.isEmpty()){settingsVisible=false;render();}else backToSettings();});LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44));bp.rightMargin=Ui.dp(this,6);header.addView(back,bp);header.addView(Daybook.text(this,panel.isEmpty()?"Settings":panelTitle(panel),24,Daybook.ink(this),true),new LinearLayout.LayoutParams(0,-2,1));}
        else{((View)header.getParent()).setVisibility(View.GONE);footer.setVisibility(View.VISIBLE);Daybook.Mark mark=new Daybook.Mark(this);LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,27));mp.rightMargin=Ui.dp(this,8);footer.addView(mark,mp);footer.addView(Daybook.text(this,"VonoTation",18,Daybook.ink(this),true),new LinearLayout.LayoutParams(0,-2,1));Daybook.SettingsIcon gear=new Daybook.SettingsIcon(this);gear.setBackground(Daybook.ripple(this,Daybook.surface(this),14));gear.setContentDescription("Settings");gear.setTooltipText("Settings");gear.setOnClickListener(v->{Daybook.feedback(v);Notes.stop();Translate.stop();settingsVisible=true;panel="";render();Daybook.motion(body);});footer.addView(gear,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));}
        detail=!settingsVisible && Notes.opened();
        homeTab=detail && part==1?"calls":"days";
        deckHome=!settingsVisible && !detail;
        if(scroll!=null){scroll.setVisibility(deckHome?View.GONE:View.VISIBLE);((LinearLayout.LayoutParams)scroll.getLayoutParams()).weight=deckHome?0:1;}
        if(strip!=null){strip.setVisibility(deckHome?View.VISIBLE:View.GONE);((LinearLayout.LayoutParams)strip.getLayoutParams()).weight=deckHome?1:0;strip.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,8));}
        if(settingsVisible){if(panel.isEmpty())settings();else settingsPanel();}
        else if(detail){if(part==4)Notes.thoughtsMode();else Notes.callsMode(part==1);Notes.show(this,body,this::render);}
        else parts();
        if(deckHome)deckReady=true;
        restoreScrollPosition();
    }
    private void fillDeck() {
        int[] held=new int[deckScrolls.length];
        for(int i=0;i<deckScrolls.length;i++)held[i]=deckScrolls[i].getScrollY();
        for(LinearLayout page:deckPages)page.removeAllViews();
        dayCards.clear();
        Notes.callsMode(false);Notes.show(this,deckPages[0],deckDocks[0],this::render);
        Calls.show(this,deckPages[1],deckDocks[1],this::render);
        Translate.show(this,deckPages[2],deckDocks[2],this::render);
        historyHost=deckPages[3];history();historyHost=null;
        if(deckPages.length>4){Notes.thoughtsMode();Notes.show(this,deckPages[4],this::render);}
        for(int i=0;i<3;i++)if(deckDocks[i]!=null)deckDocks[i].setVisibility(i==part?View.VISIBLE:View.GONE);
        if(deck!=null && deck.front!=part){deck.front=part;deck.place(false);}
        for(int i=0;i<deckScrolls.length;i++){int stay=held[i];ScrollView page=deckScrolls[i];page.post(()->page.scrollTo(0,stay));}
    }
    private String panelTitle(String key){if(key.equals("transcription"))return "Transcription";if(key.equals("cleanup"))return "Cleanup options";if(key.equals("language"))return "Language";if(key.equals("size"))return "Bubble size";if(key.equals("dictionary"))return "Personal dictionary";if(key.equals("permissions"))return "Permissions";return "Advanced";}
    private void saveScrollPosition(){if(scroll==null||!hasRendered||scrollRestorePending)return;if(renderedScreen.equals("home"))historyScrollY=scroll.getScrollY();else if(renderedScreen.equals("settings"))settingsScrollY=scroll.getScrollY();}
    private void restoreScrollPosition(){final int generation=++scrollGeneration,position=!settingsVisible?historyScrollY:panel.isEmpty()?settingsScrollY:0;scrollRestorePending=true;scroll.post(()->{if(generation!=scrollGeneration||isDestroyed())return;Runnable restore=()->{if(generation!=scrollGeneration||isDestroyed())return;scroll.scrollTo(0,position);scrollRestorePending=false;};if(!scroll.isLayoutRequested()){restore.run();return;}scroll.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){android.view.ViewTreeObserver observer=scroll.getViewTreeObserver();if(observer.isAlive())observer.removeOnGlobalLayoutListener(this);restore.run();}});});}
    private void backToSettings(){rememberDictionaryDraft();String focus=origin;panel="";settingsVisible=true;render();Daybook.motion(body);View row=settingRows.get(focus);if(row!=null)row.post(row::requestFocusFromTouch);}
    private void openPanel(String key){origin=key;panel=key;settingsVisible=true;render();Daybook.motion(body);}
    private void parts() {
        String[] titles=Store.quickThoughts(this)?new String[]{"Voice notes","Call","Translate","Dictation","Thoughts"}:new String[]{"Voice notes","Call","Translate","Dictation"};
        int count=titles.length;if(part>=count)part=0;
        deckPages=new LinearLayout[count];
        deckScrolls=new ScrollView[count];
        android.widget.FrameLayout[] cards=new android.widget.FrameLayout[count];
        deckDocks=new android.widget.FrameLayout[count];
        int widthDp=getResources().getConfiguration().screenWidthDp;
        boolean wide=widthDp>=600;
        for(int i=0;i<count;i++){
            cards[i]=new android.widget.FrameLayout(this);
            cards[i].setTag(i);
            cards[i].setBackground(Daybook.shape(this,Daybook.surface(this),28));
            cards[i].setClipChildren(false);
            LinearLayout face=Ui.column(this);
            face.setPadding(Ui.dp(this,18),Ui.dp(this,16),Ui.dp(this,18),Ui.dp(this,10));
            face.addView(Daybook.text(this,titles[i],22,Daybook.ink(this),true));
            Ui.space(face,10);
            deckScrolls[i]=new ScrollView(this);deckScrolls[i].setFillViewport(true);deckScrolls[i].setVerticalScrollBarEnabled(false);
            deckPages[i]=Ui.column(this);
            deckScrolls[i].addView(deckPages[i],new ViewGroup.LayoutParams(-1,-2));
            face.addView(deckScrolls[i],new LinearLayout.LayoutParams(-1,0,1));
            cards[i].addView(face,new android.widget.FrameLayout.LayoutParams(-1,-1));
            if(i<3)deckDocks[i]=new android.widget.FrameLayout(this);
        }
        Notes.callsMode(false);Notes.show(this,deckPages[0],deckDocks[0],this::render);
        Calls.show(this,deckPages[1],deckDocks[1],this::render);
        Translate.show(this,deckPages[2],deckDocks[2],this::render);
        historyHost=deckPages[3];history();historyHost=null;
        if(deckPages.length>4){Notes.thoughtsMode();Notes.show(this,deckPages[4],this::render);}
        strip.setClipChildren(false);
        deck=new CardDeck(this);
        deck.front=part;deck.two=wide;deck.setClipChildren(false);
        for(int i=0;i<count;i++){
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,-1);
            lp.leftMargin=Ui.dp(this,10);lp.rightMargin=Ui.dp(this,10);lp.topMargin=Ui.dp(this,wide?28:64);lp.bottomMargin=Ui.dp(this,36);
            deck.addView(cards[i],lp);
        }
        deck.listener=(from,to)->{part=to;Haptics.fire(this,Haptics.POP);chime(from,to);deck.postDelayed(()->Haptics.fire(this,Haptics.RELEASE),170);for(int i=0;i<3;i++)if(deckDocks[i]!=null)deckDocks[i].setVisibility(i==to?View.VISIBLE:View.GONE);};
        android.widget.FrameLayout stage=new android.widget.FrameLayout(this);
        stage.setClipChildren(false);
        stage.addView(deck,new android.widget.FrameLayout.LayoutParams(-1,-1));
        for(int i=0;i<3;i++){
            deckDocks[i].setVisibility(i==part?View.VISIBLE:View.GONE);
            deckDocks[i].setElevation(Ui.dp(this,24));
            android.widget.FrameLayout.LayoutParams over=new android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
            over.bottomMargin=Ui.dp(this,2);over.rightMargin=0;
            stage.addView(deckDocks[i],over);
        }
        LinearLayout.LayoutParams deckLp=new LinearLayout.LayoutParams(wide?Ui.dp(this,460):-1,-1);
        if(wide)deckLp.gravity=Gravity.CENTER_HORIZONTAL;
        strip.addView(stage,deckLp);
        deck.post(()->deck.place(false));
    }
    private void chime(int from,int to){
        float[] note={196f,262f,330f,392f,466f};
        int rate=22050,count=(int)(rate*0.18f);
        byte[] pcm=new byte[count*2];
        for(int i=0;i<count;i++){
            float t=i/(float)count;
            float env=t<0.12f?t/0.12f:(float)Math.pow(1f-(t-0.12f)/0.88f,1.7f);
            float hz=note[from]+(note[to]-note[from])*Math.min(1f,t*1.4f);
            double sample=Math.sin(2*Math.PI*hz*i/rate)*0.82+Math.sin(4*Math.PI*hz*i/rate)*0.12;
            int s=(int)Math.max(-32767,Math.min(32767,sample*env*6400));
            pcm[i*2]=(byte)(s&255);pcm[i*2+1]=(byte)((s>>8)&255);
        }
        try{
            if(chimeTrack!=null){chimeTrack.pause();chimeTrack.flush();chimeTrack.release();}
            chimeTrack=new AudioTrack(AudioManager.STREAM_MUSIC,rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT,pcm.length,AudioTrack.MODE_STATIC);
            chimeTrack.write(pcm,0,pcm.length);chimeTrack.setVolume(0.5f);chimeTrack.play();
        }catch(RuntimeException ignored){}
    }
    private LinearLayout historyHost;
    private void history() {
        LinearLayout host=historyHost!=null?historyHost:body;
        String model=Store.get(this,"polish_model",CloudApi.DEFAULT_POLISH),endpoint=Store.get(this,"api_base",CloudApi.DEFAULT_BASE),mode=Store.engine(this);boolean enabled=Store.bool(this,"daily_recaps_opt_in",false);
        java.util.List<HistoryDays.Day> days=historyDays();DailySummaryCache.prune(this,days);
        pendingBanners();
        if(days.isEmpty()){Ui.space(host,4);host.addView(Daybook.text(this,"What you dictate with the bubble shows up here.",13,Daybook.muted(this),false));return;}
        String today=HistoryDays.key(System.currentTimeMillis(),java.util.TimeZone.getDefault());java.util.Calendar yesterday=java.util.Calendar.getInstance();yesterday.add(java.util.Calendar.DAY_OF_YEAR,-1);String previous=HistoryDays.key(yesterday.getTimeInMillis(),yesterday.getTimeZone());
        java.util.List<HistoryDays.Day> pending=new java.util.ArrayList<>();boolean canSummarize=enabled&&!mode.equals("offline")&&!Vault.get(this).isEmpty();
        int index=0;for(HistoryDays.Day day:days){LinearLayout section=Ui.row(this);section.setGravity(Gravity.TOP);Daybook.Rail rail=new Daybook.Rail(this,index++==0);section.addView(rail,new LinearLayout.LayoutParams(Ui.dp(this,18),-1));LinearLayout content=Ui.column(this);content.setPadding(Ui.dp(this,3),0,0,Ui.dp(this,19));section.addView(content,new LinearLayout.LayoutParams(0,-2,1));
            String title=day.key.equals(today)?"Today":day.key.equals(previous)?"Yesterday":new java.text.SimpleDateFormat("EEEE",java.util.Locale.getDefault()).format(new Date(day.time()));
            LinearLayout dayHead=Ui.row(this);dayHead.setMinimumHeight(Ui.dp(this,44));dayHead.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,8));LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,title,15,Daybook.ink(this),true));Ui.space(labels,3);labels.addView(Daybook.text(this,new java.text.SimpleDateFormat("EEE, MMM d",java.util.Locale.getDefault()).format(new Date(day.time()))+" · "+day.entries.size()+" saved",11,Daybook.muted(this),false));dayHead.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView arrow=Daybook.text(this,"⌄",18,Daybook.accent(this),false);dayHead.addView(arrow);content.addView(dayHead);Ui.space(content,7);
            DayCard views=new DayCard();LinearLayout recap=Daybook.card(this);recap.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));recap.addView(Daybook.text(this,"✧  Daily recap",11,Daybook.accent(this),true));Ui.space(recap,4);views.summary=Daybook.text(this,"",13,Daybook.ink(this),false);views.status=Daybook.text(this,"",11,Daybook.muted(this),false);recap.addView(views.summary);recap.addView(views.status);views.status.setVisibility(View.GONE);
            String cached=DailySummaryCache.get(this,day,model,endpoint);if(!cached.isEmpty())views.summary.setText(cached);else{views.summary.setText(canSummarize?"Creating your daily recap…":mode.equals("offline")?"On-device mode · AI recaps paused":Vault.get(this).isEmpty()?"Your words are under the day. Add a provider key if you want a recap.":"Your words are under the day.");views.summary.setTextColor(Daybook.muted(this));if(canSummarize)pending.add(day);}
            views.retry=Daybook.button(this,"Retry recap",false,v->render());views.retry.setVisibility(View.GONE);recap.addView(views.retry,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));dayCards.put(day.key,views);
            LinearLayout timeline=Ui.column(this);timeline.setPadding(0,Ui.dp(this,10),0,0);for(HistoryDays.Entry entry:day.entries)addHistoryEntry(timeline,entry);
            boolean open=expandedDays.contains(day.key)||(day.key.equals(today)&&!collapsedDays.contains(day.key));timeline.setVisibility(open?View.VISIBLE:View.GONE);arrow.setText(open?"⌃":"⌄");content.addView(timeline);if(enabled){Ui.space(content,10);content.addView(recap);}dayHead.setContentDescription((open?"Hide":"Show")+" timeline for "+day.key);dayHead.setOnClickListener(v->{Daybook.feedback(v);boolean show=timeline.getVisibility()!=View.VISIBLE;timeline.setVisibility(show?View.VISIBLE:View.GONE);if(show){expandedDays.add(day.key);collapsedDays.remove(day.key);Daybook.motion(timeline);}else{expandedDays.remove(day.key);collapsedDays.add(day.key);}arrow.setText(show?"⌃":"⌄");dayHead.setContentDescription((show?"Hide":"Show")+" timeline for "+day.key);});host.addView(section);
        }if(!pending.isEmpty() && summaryApi==null)createDailySummaries(pending,model,endpoint);
    }
    private void addHistoryEntry(LinearLayout timeline,HistoryDays.Entry entry) {
        separator(timeline);String key=Long.toString(entry.id);LinearLayout item=Ui.column(this);LinearLayout summary=Ui.row(this);summary.setMinimumHeight(Ui.dp(this,51));summary.setPadding(0,Ui.dp(this,7),0,Ui.dp(this,7));summary.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,7));TextView time=Daybook.text(this,DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(entry.id)),11,Daybook.muted(this),false);summary.addView(time,new LinearLayout.LayoutParams(Ui.dp(this,67),-2));TextView excerpt=Daybook.text(this,entry.text,16,Daybook.ink(this),false);excerpt.setMaxLines(2);excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);summary.addView(excerpt,new LinearLayout.LayoutParams(0,-2,1));item.addView(summary);
        LinearLayout detail=Ui.column(this);detail.setPadding(Ui.dp(this,67),0,0,Ui.dp(this,11));boolean changed=!entry.raw.equals(entry.text);if(changed){detail.addView(Daybook.text(this,"INSERTED",10,Daybook.accent(this),true));Ui.space(detail,3);}TextView full=Daybook.text(this,entry.text,17,Daybook.ink(this),false);full.setTextIsSelectable(true);detail.addView(full);
        if(changed){Ui.space(detail,10);detail.addView(Daybook.text(this,"HEARD (BEFORE CLEANUP)",10,Daybook.accent(this),true));Ui.space(detail,3);TextView heard=Daybook.text(this,entry.raw,15,Daybook.muted(this),false);heard.setTextIsSelectable(true);heard.setContentDescription("Heard before cleanup: "+entry.raw);detail.addView(heard);}
        else{Ui.space(detail,4);detail.addView(Daybook.text(this,"Same as heard · no cleanup changes",10,Daybook.muted(this),false));}
        Ui.space(detail,8);LinearLayout actions=Ui.row(this);Button copy=Daybook.button(this,"Copy",false,v->Ui.copy(this,entry.text));copy.setContentDescription("Copy dictation "+entry.id);Button delete=Daybook.button(this,"Delete",false,v->{Store.delete(this,entry.id);expandedEntries.remove(key);render();});delete.setContentDescription("Delete dictation "+entry.id);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(this,44),1);cp.rightMargin=Ui.dp(this,8);actions.addView(copy,cp);actions.addView(delete,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));detail.addView(actions);boolean open=expandedEntries.contains(key);detail.setVisibility(open?View.VISIBLE:View.GONE);summary.setContentDescription((open?"Close":"Open")+" dictation "+entry.id);summary.setOnClickListener(v->{Daybook.feedback(v);boolean show=detail.getVisibility()!=View.VISIBLE;detail.setVisibility(show?View.VISIBLE:View.GONE);if(show){expandedEntries.add(key);Daybook.motion(detail);}else expandedEntries.remove(key);summary.setContentDescription((show?"Close":"Open")+" dictation "+entry.id);});item.addView(detail);timeline.addView(item);
    }
    private final class DayCard{TextView summary,status;Button retry;}
    private java.util.List<HistoryDays.Day> historyDays(){java.util.List<HistoryDays.Entry> entries=new java.util.ArrayList<>();JSONArray all=Store.history(this);for(int i=0;i<all.length();i++){JSONObject item=all.optJSONObject(i);if(item!=null&&item.optLong("id")>0)entries.add(new HistoryDays.Entry(item.optLong("id"),item.optString("text"),item.optInt("words"),item.optString("raw",item.optString("text"))));}return HistoryDays.group(entries,java.util.TimeZone.getDefault());}
    private void stopSummaryWork(){summaryGeneration++;CloudApi api=summaryApi;summaryApi=null;if(api!=null)api.cancel();}
    private void createDailySummaries(java.util.List<HistoryDays.Day> days,String model,String endpoint){
        final int generation=summaryGeneration;CloudApi api=new CloudApi(this);summaryApi=api;
        summaryWorker.execute(()->{try{for(HistoryDays.Day day:days){if(generation!=summaryGeneration || Thread.currentThread().isInterrupted())return;try{String recap=api.summarizeDay(day,model);runOnUiThread(()->{if(generation!=summaryGeneration || settingsVisible)return;HistoryDays.Day current=null;for(HistoryDays.Day candidate:historyDays())if(candidate.key.equals(day.key))current=candidate;if(current==null || !HistoryDays.fingerprint(current,model,endpoint).equals(HistoryDays.fingerprint(day,model,endpoint))){render();return;}DailySummaryCache.put(this,day,model,endpoint,recap);DayCard card=dayCards.get(day.key);if(card!=null){card.summary.setText(recap);card.summary.setTextColor(Daybook.ink(this));card.status.setText("AI recap · "+modelName(ModelCatalog.CLEANUP_NAMES,ModelCatalog.CLEANUP_IDS,model));card.status.setVisibility(View.VISIBLE);card.retry.setVisibility(View.GONE);}});}catch(Exception error){runOnUiThread(()->{if(generation!=summaryGeneration || settingsVisible)return;for(HistoryDays.Day pending:days){DayCard card=dayCards.get(pending.key);if(card!=null && DailySummaryCache.get(this,pending,model,endpoint).isEmpty()){card.summary.setText("Recap unavailable. Your dictations are below.");card.summary.setTextColor(Daybook.muted(this));card.status.setText(error.getMessage()==null?"Check your connection and API settings.":error.getMessage());card.status.setVisibility(View.VISIBLE);card.retry.setVisibility(View.VISIBLE);}}});break;}}}finally{CloudApi done=api;runOnUiThread(()->{if(summaryApi==done)summaryApi=null;});}});
    }
    /** Round5: four expandable cards (one open at a time). Copper and Sand tokens unchanged; structure only. */
    private static String openCard="cloud";
    private void settings() {
        boolean keyed=!Vault.get(this).isEmpty();
        // Cloud setup
        LinearLayout cloud=card("cloud","Cloud setup",keyed?"Key saved":"Needs key");
        if(cloud!=null){
            settingRow(cloud,"API key",keyed?KeyMask.preview(Vault.get(this)):"Add key","connection",v->apiDialog());
            if(keyed){
                TextView saved=Daybook.text(this,"Key saved, ending in …"+lastFour(Vault.get(this))+".",11,Daybook.muted(this),false);cloud.addView(saved);
                Ui.space(cloud,8);
                TextView probe=Daybook.text(this,"",13,Daybook.ink(this),false);
                Button test=Daybook.button(this,"Test connection",false,v->probeConnection(v,probe));
                test.setContentDescription("Test the saved API key");
                cloud.addView(test,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));
                cloud.addView(probe);
            }
            else hint(cloud,"Dictation, notes and translation all use this key.");
        }
        LinearLayout models=card("models","Models",modelSummary());
        if(models!=null){
            models.addView(Daybook.text(this,"Dictation",13,Daybook.accent(this),true));
            Ui.space(models,4);
            models.addView(Daybook.text(this,"What the keyboard hears, then the model that cleans it up.",12,Daybook.muted(this),false));
            modelOptions(models,ModelCatalog.TRANSCRIPT_NAMES,ModelCatalog.TRANSCRIPT_IDS,"transcribe_model",CloudApi.DEFAULT_TRANSCRIBE);
            Ui.space(models,8);
            modelOptions(models,ModelCatalog.CLEANUP_NAMES,ModelCatalog.CLEANUP_IDS,"polish_model",CloudApi.DEFAULT_POLISH);
            Ui.space(models,12);
            models.addView(Daybook.text(this,"Notes",13,Daybook.accent(this),true));
            Ui.space(models,4);
            models.addView(Daybook.text(this,"Large v3 is the accurate multilingual model. OSS 120B writes the note back without changing the words.",12,Daybook.muted(this),false));
            modelOptions(models,ModelCatalog.TRANSCRIPT_NAMES,ModelCatalog.TRANSCRIPT_IDS,"notes_transcribe_model","whisper-large-v3");
            Ui.space(models,8);
            modelOptions(models,ModelCatalog.CLEANUP_NAMES,ModelCatalog.CLEANUP_IDS,"notes_polish_model","openai/gpt-oss-120b");
            Ui.space(models,12);
            models.addView(Daybook.text(this,"Translation",13,Daybook.accent(this),true));
            Ui.space(models,4);
            models.addView(Daybook.text(this,"Speech model, then the model that writes the other language. Leave these on Large v3 and OSS 120B for Bengali.",12,Daybook.muted(this),false));
            modelOptions(models,ModelCatalog.TRANSCRIPT_NAMES,ModelCatalog.TRANSCRIPT_IDS,"translate_transcribe_model","whisper-large-v3");
            Ui.space(models,8);
            modelOptions(models,ModelCatalog.CLEANUP_NAMES,ModelCatalog.CLEANUP_IDS,"translate_model","openai/gpt-oss-120b");
            Ui.space(models,8);
        }
        // Dictation
        LinearLayout dict=card("dictation","Dictation","English · Cloud");
        if(dict!=null){
            toggle(dict,"AI cleanup","Fixes punctuation; never changes your words.","ai_polish",false,null);separator(dict);
            settingRow(dict,"Cleanup style",Store.verbatim(this)?"Verbatim":"Basic","cleanup",v->openPanel("cleanup"));separator(dict);
            String size=Store.get(this,"bubble_size","small");if(!size.equals("medium")&&!size.equals("large"))size="small";
            settingRow(dict,"Bubble size",Character.toUpperCase(size.charAt(0))+size.substring(1),"size",v->openPanel("size"));separator(dict);
            toggle(dict,"Quick thoughts","Keep an idle edge dot and a Thoughts card. Turning off hides both; saved thoughts stay on this phone.","quick_thoughts",false,()->{deckReady=false;if(!Store.quickThoughts(this)){if(Notes.thoughtOpened(this))Notes.back();if(part==4)part=0;}render();});separator(dict);
            toggle(dict,"Haptic feedback","","haptic_feedback",true,null);separator(dict);
            toggle(dict,"Save dictations","Up to 100 on this phone.","save_history",true,null);separator(dict);
            recapToggle(dict);separator(dict);
            hint(dict,"Password and PIN dictations are never saved.");
            Ui.space(dict,8);dict.addView(Daybook.text(this,"Personal dictionary",14,Daybook.ink(this),false));Ui.space(dict,6);
            android.widget.LinearLayout chips=Ui.column(this);
            for(String[] entry:dictionaryWords()){LinearLayout chip=Ui.row(this);chip.setGravity(Gravity.CENTER_VERTICAL);chip.setPadding(Ui.dp(this,12),0,Ui.dp(this,4),0);chip.setBackground(Ui.shape(Daybook.soft(this),16,this));
                LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,entry[1],13,Daybook.ink(this),false));labels.addView(Daybook.text(this,entry[0]+" → "+entry[1],11,Daybook.muted(this),false));chip.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                TextView x=Daybook.text(this,"×",20,Daybook.muted(this),false);x.setGravity(Gravity.CENTER);x.setContentDescription("Remove "+entry[1]);x.setOnClickListener(v->{Daybook.feedback(v);java.util.List<String> keep=new java.util.ArrayList<>();for(String line:Store.get(this,"dictionary","").split("\\r?\\n"))if(!line.equals(entry[2]))keep.add(line);Store.prefs(this).edit().putString("dictionary",android.text.TextUtils.join("\n",keep)).apply();render();});chip.addView(x,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=Ui.dp(this,6);chips.addView(chip,cp);}
            dict.addView(chips);dict.addView(Daybook.button(this,"Add word",false,v->openPanel("dictionary")));Ui.space(dict,10);
        }
        // Backup (on hold)
        LinearLayout backup=card("backup","Backup","On hold");
        if(backup!=null){hint(backup,"Google Drive backup is paused. This build is signed with a new key, and Google sign-in is still registered to the old one, so Google cancels the connection. It returns once the new key is added in Google Cloud Console. Your dictations stay on this phone.");Ui.space(backup,10);}
        // Permissions (live)
        boolean notif=android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        boolean allSet=microphoneAllowed()&&FlowAccessibility.instance!=null&&notif&&BubbleHealth.ignoringBattery(this);
        LinearLayout perms=card("permissions","Permissions",allSet?"All set":"Needs setup");
        if(perms!=null){
            settingRow(perms,"Microphone",microphoneAllowed()?"Allowed":"Not allowed","microphone",v->manageMicrophone());separator(perms);
            settingRow(perms,"Floating microphone",FlowAccessibility.instance!=null?"Connected":floatingEnabled()?"Not connected":"Not enabled","floating",v->discloseAccessibility());separator(perms);
            settingRow(perms,"Battery",BubbleHealth.ignoringBattery(this)?"Unrestricted":"Optimized","battery",v->{if(BubbleHealth.ignoringBattery(this)){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException ignored){}}else BubbleHealth.requestBatteryExemption(this);});
            if(android.os.Build.VERSION.SDK_INT>=33){separator(perms);settingRow(perms,"Notifications",notif?"Allowed":"Not allowed","notifications",v->{if(!notif && !getPreferences(MODE_PRIVATE).getBoolean("notifications_requested",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);}else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});}
            separator(perms);settingRow(perms,"VonoTation keyboard",keyboardSelected()?"Selected":keyboardEnabled()?"Enabled":"Optional","keyboard",v->{if(!keyboardEnabled())startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));else ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();});
            hint(perms,"Status updates when you return. The bubble is always on.");Ui.space(perms,10);
        }
        Ui.space(body,16);
        Button update=Daybook.button(this,"Update",true,v->AppUpdate.start(this));
        update.setContentDescription("Update VonoTation");
        LinearLayout.LayoutParams up=new LinearLayout.LayoutParams(-1,Ui.dp(this,48));up.bottomMargin=Ui.dp(this,6);
        body.addView(update,up);
        TextView updateLine=Daybook.text(this,"",12,Daybook.muted(this),false);updateLine.setGravity(Gravity.CENTER);body.addView(updateLine);
        AppUpdate.bind(this,update,updateLine);
        Ui.space(body,8);
        settingsButton(body,"Clear history",v->new AlertDialog.Builder(this).setTitle("Clear saved history?").setMessage("Deletes all saved dictations and daily recaps from this phone.").setNegativeButton("Keep",null).setPositiveButton("Clear",(d,w)->{Store.clearHistory(this);render();}).show());
        String version="";try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException ignored){}
        TextView foot=Daybook.text(this,"VonoTation "+version,11,Daybook.muted(this),false);foot.setGravity(Gravity.CENTER);body.addView(foot);Ui.space(body,12);
    }
    private String modelSummary(){
        return modelName(ModelCatalog.TRANSCRIPT_NAMES,ModelCatalog.TRANSCRIPT_IDS,Store.get(this,"transcribe_model",CloudApi.DEFAULT_TRANSCRIBE));
    }
    private static String lastFour(String key){return key.length()<=4?key:key.substring(key.length()-4);}
    /** Asks the saved endpoint whether this key is accepted. Does not spend a transcription. */
    private void probeConnection(View button,TextView result){
        String key=Vault.get(this);
        if(key.isEmpty()){result.setTextColor(0xffb34c40);result.setText("Add an API key first.");return;}
        button.setEnabled(false);
        if(button instanceof Button)((Button)button).setText("Checking…");
        result.setTextColor(Daybook.muted(this));result.setText("Reaching the API…");
        String base=Store.get(this,"api_base",CloudApi.DEFAULT_BASE);
        new Thread(()->{
            int code=-1;String failure=null;
            try{code=CloudApi.checkKey(base,key);}catch(Exception error){failure=error.getMessage();}
            final int status=code;final String error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||!button.isAttachedToWindow())return;
                button.setEnabled(true);
                if(button instanceof Button)((Button)button).setText("Test connection");
                if(error!=null){result.setTextColor(0xffb34c40);result.setText("Couldn't reach the API. Check your internet and the endpoint.");return;}
                if(status>=200&&status<300){result.setTextColor(Daybook.accent(this));result.setText("Connected. This key works.");return;}
                if(status==401||status==403){result.setTextColor(0xffb34c40);result.setText("The key was rejected ("+status+"). Open API key and paste a new one.");return;}
                if(status==429){result.setTextColor(0xffb34c40);result.setText("The key reached the API, but the service is limiting requests right now.");return;}
                result.setTextColor(0xffb34c40);result.setText("The API replied "+status+". Check the endpoint.");
            });
        },"api-probe").start();
    }
    /** Card header reuses the settings row (title · status · chevron); returns the body only when open. */
    private LinearLayout card(String id,String title,String status){
        Ui.space(body,12);LinearLayout c=Daybook.card(this);boolean open=id.equals(openCard);
        settingRow(c,title,status,"card_"+id,v->{openCard=open?"":id;render();});
        c.getChildAt(c.getChildCount()-1).setContentDescription(title+", "+status+(open?", expanded":", collapsed"));
        body.addView(c);if(!open)return null;separator(c);return c;
    }
    private void settingsPanel() {
        if(panel.equals("size")){Ui.space(body,6);options(body,new String[]{"Small","Medium","Large"},null,new String[]{"small","medium","large"},"bubble_size","small",true);}
        else if(panel.equals("cleanup")){Ui.space(body,6);LinearLayout cleanup=Daybook.card(this);toggle(cleanup,"Verbatim mode","Insert exactly what was heard. Skips all cleanup.","verbatim",false,this::render);separator(cleanup);toggle(cleanup,"Basic cleanup","Removes um/uh; fixes spacing and capitals.","clean",true,null);separator(cleanup);toggle(cleanup,"Spoken punctuation","Say “comma”, “new line”.","spoken_punctuation",true,null);body.addView(cleanup);}
        else if(panel.equals("dictionary"))dictionaryPanel();else if(panel.equals("permissions"))permissionsPanel();else {panel="";settings();}
    }
    private void groupLabel(String value){Ui.space(body,17);TextView label=Daybook.text(this,value.toUpperCase(java.util.Locale.getDefault()),11,Daybook.accent(this),true);label.setLetterSpacing(.055f);body.addView(label);Ui.space(body,8);}
    private void separator(LinearLayout parent){View divider=new View(this);divider.setBackgroundColor(Daybook.line(this));parent.addView(divider,new LinearLayout.LayoutParams(-1,Math.max(1,Ui.dp(this,.5f))));}
    private void hint(LinearLayout parent,String text){Ui.space(parent,10);parent.addView(Daybook.text(this,text,11,Daybook.muted(this),false));}
    private void settingRow(LinearLayout parent,String title,String value,String key,View.OnClickListener click){LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,54));row.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,8));row.addView(Daybook.text(this,title,key.equals("advanced")?12:14,key.equals("advanced")?Daybook.muted(this):Daybook.ink(this),false),new LinearLayout.LayoutParams(0,-2,1));if(!value.isEmpty()){TextView shown=Daybook.text(this,value,12,Daybook.accent(this),false);shown.setMaxLines(1);shown.setEllipsize(android.text.TextUtils.TruncateAt.END);shown.setMaxWidth(Ui.dp(this,120));LinearLayout.LayoutParams vp=new LinearLayout.LayoutParams(-2,-2);vp.leftMargin=Ui.dp(this,9);row.addView(shown,vp);}TextView chevron=Daybook.text(this,"›",20,Daybook.accent(this),false);chevron.setPadding(Ui.dp(this,6),0,0,0);row.addView(chevron);row.setContentDescription(title);if(android.os.Build.VERSION.SDK_INT>=30&&!value.isEmpty())row.setStateDescription(value);row.setFocusable(true);row.setFocusableInTouchMode(false);row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);row.setOnClickListener(v->{Daybook.feedback(v);click.onClick(v);});settingRows.put(key,row);parent.addView(row);}
    private void toggle(LinearLayout parent,String title,String detail,String key,boolean fallback,Runnable after){LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,54));LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,title,14,Daybook.ink(this),false));if(!detail.isEmpty()){Ui.space(labels,4);labels.addView(Daybook.text(this,detail,11,Daybook.muted(this),false));}row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));Switch toggle=new Switch(this);toggle.setContentDescription(title);toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Daybook.surface(this)));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Daybook.action(this),Daybook.line(this)}));toggle.setChecked(Store.bool(this,key,fallback));toggle.setOnCheckedChangeListener((button,checked)->{Store.prefs(this).edit().putBoolean(key,checked).apply();if(!key.equals("haptic_feedback")||checked)Daybook.feedback(button);if(key.equals("haptic_feedback")&&checked)Haptics.fire(this,Haptics.POP,true);if(after!=null)after.run();});row.addView(toggle);row.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,8));row.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));parent.addView(row);}
    private void options(LinearLayout parent,String[] names,String[] descriptions,String[] values,String key,String fallback,boolean close){LinearLayout group=Daybook.card(this);String selected=Store.get(this,key,fallback);for(int i=0;i<values.length;i++){final String value=values[i];if(i>0)separator(group);LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,68));row.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,8));LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,names[i],14,Daybook.ink(this),false));if(descriptions!=null&&!descriptions[i].isEmpty()){Ui.space(labels,4);labels.addView(Daybook.text(this,descriptions[i],12,Daybook.muted(this),false));}row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));if(value.equals(selected))row.addView(Daybook.text(this,"✓",18,Daybook.accent(this),true));row.setContentDescription(names[i]);row.setSelected(value.equals(selected));row.setFocusable(true);row.setFocusableInTouchMode(false);row.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);row.setOnClickListener(v->{Daybook.feedback(v);Store.prefs(this).edit().putString(key,value).apply();if(close)backToSettings();else render();});group.addView(row);}parent.addView(group);}
    private void modelOptions(LinearLayout parent,String[] names,String[] ids,String key,String fallback){String selected=Store.get(this,key,fallback);boolean known=false;for(String id:ids)known|=id.equals(selected);if(!known){names=java.util.Arrays.copyOf(names,names.length+1);names[names.length-1]="Custom · "+selected;ids=java.util.Arrays.copyOf(ids,ids.length+1);ids[ids.length-1]=selected;}options(parent,names,null,ids,key,fallback,false);}
    private String modelName(String[] names,String[] ids,String selected){for(int i=0;i<ids.length;i++)if(ids[i].equals(selected))return names[i];return "Custom";}
    private java.util.List<String[]> dictionaryWords(){java.util.List<String[]> words=new java.util.ArrayList<>();for(String line:Store.get(this,"dictionary","").split("\\r?\\n")){int split=line.indexOf('=');if(split>0&&split<line.length()-1)words.add(new String[]{line.substring(0,split).trim(),line.substring(split+1).trim(),line});}return words;}
    private void rememberDictionaryDraft(){if(heardField!=null)heardDraft=heardField.getText().toString();if(preferredField!=null)preferredDraft=preferredField.getText().toString();}
    private void dictionaryPanel(){hint(body,"Keep the names and spellings you use often.");Ui.space(body,16);LinearLayout words=Daybook.card(this);java.util.List<String[]> entries=dictionaryWords();if(entries.isEmpty()){TextView none=Daybook.text(this,"No saved words yet",13,Daybook.muted(this),false);none.setPadding(0,Ui.dp(this,17),0,Ui.dp(this,17));words.addView(none);}for(String[] entry:entries){if(words.getChildCount()>0)separator(words);LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,62));LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,entry[1],14,Daybook.ink(this),false));Ui.space(labels,4);labels.addView(Daybook.text(this,entry[0]+" → "+entry[1],12,Daybook.muted(this),false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));TextView remove=Daybook.text(this,"×",24,Daybook.muted(this),false);remove.setGravity(Gravity.CENTER);remove.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,12));remove.setContentDescription("Remove "+entry[1]);remove.setOnClickListener(v->{Daybook.feedback(v);java.util.List<String> keep=new java.util.ArrayList<>();for(String line:Store.get(this,"dictionary","").split("\\r?\\n"))if(!line.equals(entry[2]))keep.add(line);Store.prefs(this).edit().putString("dictionary",android.text.TextUtils.join("\n",keep)).apply();render();});row.addView(remove,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,44)));words.addView(row);}body.addView(words);Ui.space(body,24);body.addView(Daybook.text(this,"Add a word",14,Daybook.ink(this),true));hint(body,"Heard spelling");heardField=field(heardDraft,"voice flow",false);heardField.setSingleLine(true);heardField.setContentDescription("Heard spelling");body.addView(heardField);hint(body,"Preferred spelling");preferredField=field(preferredDraft,"VonoTation",false);preferredField.setSingleLine(true);preferredField.setContentDescription("Preferred spelling");body.addView(preferredField);TextView error=Daybook.text(this,"",12,Daybook.coral(this),false);Ui.space(body,13);Button add=Daybook.button(this,"Add word",true,v->{String heard=heardField.getText().toString().trim(),preferred=preferredField.getText().toString().trim();if(heard.isEmpty()||preferred.isEmpty()){error.setText("Enter both spellings to add a word.");return;}if(heard.contains("=")||preferred.contains("=")||heard.contains("\n")||preferred.contains("\n")||heard.length()>80||preferred.length()>80){error.setText("Use one spelling per field, up to 80 characters, without =.");return;}for(String[] word:dictionaryWords())if(word[0].equalsIgnoreCase(heard)){error.setText("That heard spelling is already saved.");return;}String old=Store.get(this,"dictionary","");String updated=old.trim().isEmpty()?heard+"="+preferred:old+"\n"+heard+"="+preferred;if(updated.length()>100000){error.setText("Your dictionary is full. Remove a word first.");return;}Store.prefs(this).edit().putString("dictionary",updated).apply();heardField.setText("");preferredField.setText("");heardDraft="";preferredDraft="";render();Toast.makeText(this,"Word added",Toast.LENGTH_SHORT).show();});add.setContentDescription("Add word");body.addView(add,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));Ui.space(body,10);body.addView(error);}
    /** Daily recaps are an explained opt-in and stay off until the user confirms. */
    private void recapToggle(LinearLayout parent){
        LinearLayout row=Ui.row(this);row.setMinimumHeight(Ui.dp(this,54));LinearLayout labels=Ui.column(this);labels.addView(Daybook.text(this,"Daily recaps",14,Daybook.ink(this),false));Ui.space(labels,4);labels.addView(Daybook.text(this,"Off by default. Asks before sending anything.",11,Daybook.muted(this),false));row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Switch toggle=new Switch(this);toggle.setContentDescription("Daily recaps");toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(Daybook.surface(this)));toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Daybook.action(this),Daybook.line(this)}));toggle.setChecked(Store.bool(this,"daily_recaps_opt_in",false));
        toggle.setOnCheckedChangeListener((button,checked)->{
            if(!checked){Store.prefs(this).edit().putBoolean("daily_recaps_opt_in",false).apply();return;}
            if(Store.bool(this,"daily_recaps_opt_in",false))return;
            new AlertDialog.Builder(this).setTitle("Turn on daily recaps?").setMessage("VonoTation will send each day’s saved dictations (not password or PIN dictations) to your AI provider ("+Store.get(this,"api_base",CloudApi.DEFAULT_BASE)+") using your cleanup model, and show a 2–3 sentence recap under that day. Your provider may bill for these requests. You can turn this off at any time.").setNegativeButton("Not now",(d,w)->button.setChecked(false)).setOnCancelListener(d->button.setChecked(false)).setPositiveButton("Turn on",(d,w)->{Store.prefs(this).edit().putBoolean("daily_recaps_opt_in",true).apply();Daybook.feedback(button);}).show();
        });
        row.addView(toggle);row.setBackground(Daybook.ripple(this,android.graphics.Color.TRANSPARENT,8));row.setOnClickListener(v->toggle.setChecked(!toggle.isChecked()));parent.addView(row);
    }
    private Dictation retryDictation;
    /** Unsent cloud recordings and private dictations awaiting an explicit copy. */
    private void pendingBanners(){
        LinearLayout host=historyHost!=null?historyHost:body;
        // Cloud is the only engine: without a key the mic cannot work, so say so up front.
        if(Vault.get(this).isEmpty()){
            LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));card.setBackground(Daybook.shape(this,Daybook.soft(this),17));
            card.addView(Daybook.text(this,DictationProblem.API_KEY_REQUIRED,14,Daybook.ink(this),true));Ui.space(card,4);
            card.addView(Daybook.text(this,"The mic can’t record until you add your API key.",12,Daybook.muted(this),false));Ui.space(card,10);
            Button add=Daybook.button(this,"Add API key",true,v->{settingsVisible=true;panel="";origin="connection";render();apiDialog();});add.setContentDescription("Add API key");card.addView(add,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));host.addView(card);Ui.space(host,14);
        }
        // The bubble is always on; Android only runs it while VonoTation's Accessibility service is enabled.
        if(FlowAccessibility.instance==null){
            boolean was=BubbleHealth.wasSetUp(this),enabled=BubbleHealth.enabledInSettings(this);
            LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));card.setBackground(Daybook.shape(this,Daybook.soft(this),17));
            card.addView(Daybook.text(this,was?"VonoTation bubble is off":"Floating bubble needs permission",14,Daybook.ink(this),true));
            if(was){Ui.space(card,4);card.addView(Daybook.text(this,enabled?"It didn’t start after the phone restarted. Turn VonoTation off and on in Accessibility.":"Your phone turned it off, often after a restart. Turn VonoTation back on in Accessibility.",12,Daybook.muted(this),false));}
            Ui.space(card,10);
            Button allow=Daybook.button(this,was?"Turn back on":"Allow",true,v->restoreBubble());allow.setContentDescription(was?"Turn the VonoTation bubble back on":"Allow the floating bubble");card.addView(allow,new LinearLayout.LayoutParams(-1,Ui.dp(this,44)));host.addView(card);Ui.space(host,14);
        }else if(!BubbleHealth.ignoringBattery(this) && !Store.bool(this,"battery_prompt_dismissed",false)){
            LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));card.setBackground(Daybook.shape(this,Daybook.soft(this),17));
            card.addView(Daybook.text(this,"Keep the bubble running",14,Daybook.ink(this),true));Ui.space(card,4);
            card.addView(Daybook.text(this,"Battery optimization can put VonoTation to sleep and switch off the bubble, especially after a restart. VonoTation only uses the mic when you tap it.",12,Daybook.muted(this),false));Ui.space(card,10);
            LinearLayout row=Ui.row(this);
            Button later=Daybook.button(this,"Not now",false,v->{Store.prefs(this).edit().putBoolean("battery_prompt_dismissed",true).apply();render();});
            Button allowBattery=Daybook.button(this,"Allow",true,v->BubbleHealth.requestBatteryExemption(this));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(this,44),1);lp.rightMargin=Ui.dp(this,10);row.addView(later,lp);row.addView(allowBattery,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(row);host.addView(card);Ui.space(host,14);
        }
        if(PrivatePending.available()){
            LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));card.setBackground(Daybook.shape(this,Daybook.soft(this),17));
            card.addView(Daybook.text(this,"Private dictation not inserted",14,Daybook.ink(this),true));Ui.space(card,4);
            card.addView(Daybook.text(this,"It was not copied or saved. It is held in memory for "+Math.max(1,PrivatePending.remainingSeconds()/60+(PrivatePending.remainingSeconds()%60>0?1:0))+" more minute(s).",12,Daybook.muted(this),false));Ui.space(card,10);
            LinearLayout actions=Ui.row(this);Button copy=Daybook.button(this,"Copy privately",true,v->{boolean ok=PrivatePending.copy(this);Toast.makeText(this,ok?"Copied as sensitive clipboard text.":"It has expired.",Toast.LENGTH_SHORT).show();render();});Button discard=Daybook.button(this,"Discard",false,v->{PrivatePending.discard();render();});
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(this,44),1);cp.rightMargin=Ui.dp(this,8);actions.addView(copy,cp);actions.addView(discard,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(actions);host.addView(card);Ui.space(host,14);
        }
        java.io.File audio=RetryBuffer.latest(this);
        if(audio!=null || retryDictation!=null){
            LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,14),Ui.dp(this,12),Ui.dp(this,14),Ui.dp(this,12));card.setBackground(Daybook.shape(this,Daybook.soft(this),17));
            card.addView(Daybook.text(this,"Recording not transcribed",14,Daybook.ink(this),true));Ui.space(card,4);
            int count=RetryBuffer.list(this).size();
            TextView detail=Daybook.text(this,retryDictation!=null?"Retrying…":"Cloud transcription failed"+(audio==null?"":" at "+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(RetryBuffer.time(audio))))+". The audio is kept privately on this phone for 24 hours"+(count>1?" ("+count+" recordings)":"")+" and is not in History or backups.",12,Daybook.muted(this),false);card.addView(detail);Ui.space(card,10);
            LinearLayout actions=Ui.row(this);
            Button retry=Daybook.button(this,retryDictation!=null?"Cancel retry":"Retry",true,v->{if(retryDictation!=null){retryDictation.cancel();retryDictation=null;render();}else retryRecording(audio);});
            Button discard=Daybook.button(this,"Discard",false,v->{RetryBuffer.discard(audio);render();});discard.setEnabled(retryDictation==null);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,Ui.dp(this,44),1);cp.rightMargin=Ui.dp(this,8);actions.addView(retry,cp);actions.addView(discard,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));card.addView(actions);host.addView(card);Ui.space(host,14);
        }
    }
    private void retryRecording(java.io.File audio){
        if(audio==null || retryDictation!=null)return;
        final long kept=RetryBuffer.time(audio);
        final boolean retryThought=RetryBuffer.thought(audio);
        if(retryThought && !Store.quickThoughts(this))return;
        if(Dictation.isWorking() || VoiceTake.running() || Translate.going()){Toast.makeText(this,"Finish the current recording first.",Toast.LENGTH_SHORT).show();return;}
        retryDictation=new Dictation(this,new Dictation.Listener(){
            public void state(String status){}
            public void partial(String text){}
            public void level(float level){}
            public void result(String raw,String text,String warning){
                Dictation finished=retryDictation;retryDictation=null;if(finished!=null)finished.close();
                if(retryThought){
                    long id=Notes.keepThought(MainActivity.this,kept,raw,0);
                    if(id>0){RetryBuffer.discard(audio);Notes.formatThought(MainActivity.this,id);Toast.makeText(MainActivity.this,"Thought saved.",Toast.LENGTH_SHORT).show();}
                    else Toast.makeText(MainActivity.this,"Could not save. The recording is still kept for Retry.",Toast.LENGTH_LONG).show();
                    render();return;
                }
                synchronized(Store.class){Store.save(MainActivity.this,raw,text,0);}
                render();
                boolean saved=Store.bool(MainActivity.this,"save_history",true);
                new AlertDialog.Builder(MainActivity.this).setTitle("Retry transcribed").setMessage(text+(warning.isEmpty()?"":"\n\n"+warning)+(saved?"\n\nSaved to History.":"\n\nSave dictations is off, so this is not in History.")).setNegativeButton("Close",null).setPositiveButton("Copy",(d,w)->Ui.copy(MainActivity.this,text)).show();
            }
            public void error(String message){Dictation finished=retryDictation;retryDictation=null;if(finished!=null)finished.close();new AlertDialog.Builder(MainActivity.this).setTitle("Retry failed").setMessage(message).setPositiveButton("OK",null).show();render();}
        },false,retryThought);
        retryDictation.retry(audio);render();
    }
    private boolean microphoneAllowed(){return checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    private boolean keyboardEnabled(){
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        for(android.view.inputmethod.InputMethodInfo method:manager.getEnabledInputMethodList())
            if(method.getPackageName().equals(getPackageName()))return true;
        return false;
    }
    private boolean keyboardSelected(){
        String selected=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        android.content.ComponentName component=selected==null?null:android.content.ComponentName.unflattenFromString(selected);
        return component!=null&&component.getPackageName().equals(getPackageName());
    }
    private boolean floatingEnabled(){
        String enabled=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(enabled==null)return false;
        android.content.ComponentName own=new android.content.ComponentName(this,FlowAccessibility.class);
        for(String entry:enabled.split(":"))if(own.equals(android.content.ComponentName.unflattenFromString(entry)))return true;
        return false;
    }
    private String permissionSummary(){return microphoneAllowed()&&FlowAccessibility.instance!=null?"Ready":"Needs setup";}
    private void permissionsPanel(){
        hint(body,"Tap a permission to set it up or manage it. Status updates when you return.");
        groupLabel("Required for floating dictation");
        LinearLayout required=Daybook.card(this);
        settingRow(required,"Microphone",microphoneAllowed()?"Allowed":"Not allowed","microphone",v->manageMicrophone());
        separator(required);
        settingRow(required,"Floating microphone",FlowAccessibility.instance!=null?"Connected":floatingEnabled()?"Not connected":"Not enabled","floating",v->discloseAccessibility());
        separator(required);settingRow(required,"Battery",BubbleHealth.ignoringBattery(this)?"Unrestricted":"Optimized","battery",v->{if(BubbleHealth.ignoringBattery(this)){try{startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}catch(RuntimeException ignored){}}else BubbleHealth.requestBatteryExemption(this);});
        if(android.os.Build.VERSION.SDK_INT>=33){separator(required);boolean allowed=checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;settingRow(required,"Notifications",allowed?"Allowed":"Not allowed","notifications",v->{if(!allowed && !getPreferences(MODE_PRIVATE).getBoolean("notifications_requested",false)){getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_requested",true).apply();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},21);}else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});}
        body.addView(required);
        hint(body,"Microphone records only when you start dictation. Accessibility lets the floating mic insert words in the selected field.");
        groupLabel("Optional keyboard");
        LinearLayout keyboard=Daybook.card(this);
        settingRow(keyboard,"VonoTation keyboard",keyboardSelected()?"Selected":keyboardEnabled()?"Enabled":"Not enabled","keyboard",v->{
            if(!keyboardEnabled())startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            else ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
        });
        body.addView(keyboard);
        hint(body,"Keep your usual keyboard, or select VonoTation for its direct input connection. Hold the floating mic to toggle typing.");
    }
    private void manageMicrophone(){
        boolean asked=getPreferences(MODE_PRIVATE).getBoolean("microphone_requested",false);
        if(microphoneAllowed()||(asked&&!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))){
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));
        }else{
            getPreferences(MODE_PRIVATE).edit().putBoolean("microphone_requested",true).apply();
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},20);
        }
    }
    private void settingsButton(LinearLayout parent,String title,View.OnClickListener click){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Ui.dp(this,44));p.bottomMargin=Ui.dp(this,8);parent.addView(Daybook.button(this,title,false,click),p);}
    /** Enabled but not running: Android needs the switch toggled; otherwise explain and open Accessibility. */
    private void restoreBubble(){
        if(FlowAccessibility.instance!=null)return;
        if(BubbleHealth.enabledInSettings(this)){try{startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(RuntimeException ignored){}}
        else discloseAccessibility();
    }
    private void discloseAccessibility(){new AlertDialog.Builder(this).setTitle("Use the floating microphone").setMessage("VonoTation uses Accessibility to detect the focused editable field and show a floating microphone. When you start dictation, it reads that field’s text and cursor position and updates the field as you speak. Tap the floating mic to record immediately; tap again to finish and keep your words. Hold to toggle keyboard. Your last keyboard visibility choice is remembered across inputs. If the original field is unavailable, the transcript is copied to your clipboard and saved when History is enabled. It does not collect screen contents or upload field text.\n\nEnable VonoTation bubble in Android Accessibility settings. On a sideloaded app, Android may first ask you to allow restricted settings from VonoTation’s App info menu.").setNegativeButton("Cancel",null).setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();}
    private EditText field(String value,String hint,boolean secret){return Daybook.field(this,value,hint,secret);}
    private final class ModelPicker {
        final Spinner spinner; final EditText custom; final String[] ids;
        ModelPicker(LinearLayout parent,String title,String[] names,String[] ids,String selected) {
            this.ids=ids;
            parent.addView(Daybook.text(MainActivity.this,title,12,Daybook.muted(MainActivity.this),true));
            spinner=new Spinner(MainActivity.this); spinner.setContentDescription(title);
            String[] labels=java.util.Arrays.copyOf(names,names.length+1); labels[names.length]="Custom";
            spinner.setAdapter(new ArrayAdapter<String>(MainActivity.this,android.R.layout.simple_spinner_dropdown_item,labels));
            int position=ids.length; for(int i=0;i<ids.length;i++) if(ids[i].equals(selected)) position=i;
            spinner.setSelection(position); parent.addView(spinner,new LinearLayout.LayoutParams(-1,Ui.dp(MainActivity.this,48)));
            custom=field(position==ids.length?selected:"","Model ID",false); custom.setSingleLine(true); custom.setVisibility(position==ids.length?View.VISIBLE:View.GONE); parent.addView(custom);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){} public void onItemSelected(AdapterView<?> p,View v,int n,long id){custom.setVisibility(n==ids.length?View.VISIBLE:View.GONE);}});
        }
        String value(){int n=spinner.getSelectedItemPosition();return n<ids.length?ids[n]:custom.getText().toString().trim();}
    }
    private void apiDialog() { apiDialog(false); }
    private void apiDialog(boolean whisperV3) {
        final String savedKey=Vault.get(this);
        LinearLayout form=Ui.column(this); form.setPadding(Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,24),Ui.dp(this,16)); form.addView(Daybook.text(this,"API base URL",12,Daybook.muted(MainActivity.this),true)); EditText base=field(whisperV3?CloudApi.DEFAULT_BASE:Store.get(this,"api_base",CloudApi.DEFAULT_BASE),"HTTPS endpoint",false); base.setSingleLine(true); form.addView(base); Ui.space(form,12);
        LinearLayout keyHeader=Ui.row(this); keyHeader.setGravity(android.view.Gravity.CENTER_VERTICAL); keyHeader.addView(Daybook.text(this,"API key",12,Daybook.muted(MainActivity.this),true),new LinearLayout.LayoutParams(0,-2,1));
        TextView update=Daybook.text(this,"Update",13,Daybook.accent(this),true); update.setPadding(Ui.dp(this,8),Ui.dp(this,4),0,Ui.dp(this,4)); update.setContentDescription("Update API key"); keyHeader.addView(update); form.addView(keyHeader);
        EditText key=field("","API key",true); key.setSingleLine(true); form.addView(key);
        TextView keyState=Daybook.text(this,"",12,Daybook.accent(this),true); keyState.setPadding(0,Ui.dp(this,6),0,0); form.addView(keyState); Ui.space(form,12);
        // masked[0]: the field holds the preview of the saved key (built from its last 4 characters only).
        final boolean[] masked={false}; final String[] stored={savedKey};
        Runnable showSaved=()->{ masked[0]=true; key.setTransformationMethod(null); key.setText(KeyMask.preview(stored[0])); key.setContentDescription("Saved API key ending "+(stored[0].length()>8?stored[0].substring(stored[0].length()-4):"")); keyState.setText("✓ API key saved"); update.setVisibility(View.VISIBLE); };
        Runnable showEntry=()->{ masked[0]=false; key.setText(""); key.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance()); key.setHint(stored[0].isEmpty()?"API key":"Enter a new API key"); key.setContentDescription("API key"); keyState.setText(""); update.setVisibility(View.GONE); };
        if(stored[0].isEmpty()) showEntry.run(); else showSaved.run();
        key.setOnFocusChangeListener((v,focus)->{ if(focus && masked[0]) showEntry.run(); });
        key.setOnClickListener(v->{ if(masked[0]) showEntry.run(); });
        update.setOnClickListener(v->{ showEntry.run(); key.requestFocus(); });
        ModelPicker model=new ModelPicker(form,"Transcript",ModelCatalog.TRANSCRIPT_NAMES,ModelCatalog.TRANSCRIPT_IDS,whisperV3?CloudApi.DEFAULT_TRANSCRIBE:Store.get(this,"transcribe_model",CloudApi.DEFAULT_TRANSCRIBE)); Ui.space(form,12);
        ModelPicker ai=new ModelPicker(form,"Cleanup",ModelCatalog.CLEANUP_NAMES,ModelCatalog.CLEANUP_IDS,whisperV3?CloudApi.DEFAULT_POLISH:Store.get(this,"polish_model",CloudApi.DEFAULT_POLISH)); Ui.space(form,8); form.addView(Daybook.text(this,"On Groq, Instant and Versatile need enterprise access. OSS models are available alternatives.",12,Daybook.muted(MainActivity.this),false)); TextView error=Daybook.text(this,"",12,0xffb34c40,false); Ui.space(form,10); form.addView(error);
        ScrollView scroll=new ScrollView(this); scroll.addView(form);
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle(whisperV3?"Groq defaults":"API key & models").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Save",null);
        builder.setNeutralButton("Remove key",(d,w)->{synchronized(Store.class){Store.prefs(this).edit().remove("api_secret").remove("api_iv").putString("engine","cloud").putBoolean("ai_polish",false).apply();}render();});
        AlertDialog dialog=builder.create();
        dialog.setOnShowListener(d->{ if(stored[0].isEmpty()) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            error.setText("");
            final String url,typed=masked[0]?"":key.getText().toString().trim();
            try {
                url=CloudApi.validateBase(base.getText().toString());
                if(model.value().isEmpty() || ai.value().isEmpty()) throw new Exception("Enter both model names.");
                boolean keepKey=typed.isEmpty() && !stored[0].isEmpty();
                if(!keepKey){ String problem=KeyMask.formatProblem(typed); if(problem!=null) throw new Exception(problem); }
                if(keepKey && !url.equals(Store.get(this,"api_base",CloudApi.DEFAULT_BASE))) throw new Exception("Enter a key for the new endpoint before saving.");
                if(keepKey){ commitConnection(url,model.value(),ai.value(),null,whisperV3); render(); showSaved.run(); keyState.setText("✓ Saved"); return; }
            } catch(Exception ex){ error.setText(ex.getMessage()==null?"Could not save the connection.":ex.getMessage()); return; }
            // Immediate validation: ask the provider before saving. A rejected key is never stored.
            Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE); save.setEnabled(false); keyState.setTextColor(Daybook.muted(this)); keyState.setText("Checking key…");
            final String transcribeModel=model.value(),cleanupModel=ai.value();
            new Thread(()->{
                String rejected=null,note="";
                try { rejected=KeyMask.rejection(CloudApi.checkKey(url,typed)); }
                catch(Exception unreachable){ note=" (couldn’t reach the server to check it)"; android.util.Log.w("VonoTation","Key check unreachable: "+unreachable.getClass().getSimpleName()); }
                final String rejection=rejected,saveNote=note;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    save.setEnabled(true); keyState.setTextColor(Daybook.accent(this));
                    if(rejection!=null){ keyState.setTextColor(0xffb34c40); keyState.setText(rejection+(stored[0].isEmpty()?"":" Your previous key is still saved.")); key.requestFocus(); return; }
                    try { commitConnection(url,transcribeModel,cleanupModel,typed,whisperV3); }
                    catch(Exception ex){ keyState.setText(""); error.setText("Could not save the key."); return; }
                    stored[0]=typed; render(); showSaved.run(); keyState.setText("✓ API key saved"+saveNote);
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
                });
            },"key-check").start();
        });});
        dialog.show();
    }
    /** Endpoint, models and (optionally) a new key are committed as one preference snapshot. */
    private void commitConnection(String url,String transcribeModel,String cleanupModel,String newKey,boolean whisperV3) throws Exception {
        synchronized(Store.class){
            android.content.SharedPreferences.Editor connection=Store.prefs(this).edit().putString("api_base",url).putString("transcribe_model",transcribeModel).putString("polish_model",cleanupModel);
            if(newKey!=null && !newKey.isEmpty()) Vault.write(connection,Vault.prepare(newKey));
            if(whisperV3) connection.putString("engine","cloud");
            connection.apply();
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);render();}
}

/** Full-screen card in front. Phone fans the other three behind it. A wide screen keeps two: the front card and one peeking behind. */
final class CardDeck extends android.widget.FrameLayout {
    interface Move { void moved(int from,int to); }
    Move listener;
    int front;
    boolean two;
    private float downX,downY;
    private boolean dragging,caught;
    CardDeck(android.content.Context context){super(context);setClipChildren(false);}
    private int leftOf(int id){return (id+getChildCount()-1)%getChildCount();}
    private int rightOf(int id){return (id+1)%getChildCount();}
    private android.view.View card(int id){for(int i=0;i<getChildCount();i++)if(id==(Integer)getChildAt(i).getTag())return getChildAt(i);return null;}
    private void pose(android.view.View card,float tx,float ty,float rot,float scale,float alpha,boolean animate){
        card.setPivotX(card.getWidth()/2f);card.setPivotY(card.getHeight()*0.2f);
        if(animate)card.animate().translationX(tx).translationY(ty).rotation(rot).scaleX(scale).scaleY(scale).alpha(alpha).setDuration(340).setInterpolator(new android.view.animation.OvershootInterpolator(1.15f)).start();
        else{card.animate().cancel();card.setTranslationX(tx);card.setTranslationY(ty);card.setRotation(rot);card.setScaleX(scale);card.setScaleY(scale);card.setAlpha(alpha);}
    }
    private void rest(int id,float[] out){
        int delta=(id-front+getChildCount())%getChildCount();
        float lift=Ui.dp(getContext(),20),nudge=Ui.dp(getContext(),14);
        if(two){
            if(delta==0){out[0]=0;out[1]=0;out[2]=0;out[3]=1f;out[4]=1f;}
            else if(delta==1){out[0]=nudge*0.6f;out[1]=-lift*0.8f;out[2]=4f;out[3]=0.98f;out[4]=1f;}
            else{out[0]=0;out[1]=-lift;out[2]=0;out[3]=0.96f;out[4]=0f;}
            return;
        }
        if(delta==0){out[0]=0;out[1]=0;out[2]=0;out[3]=1f;out[4]=1f;}
        else if(delta==1){out[0]=nudge;out[1]=-lift;out[2]=6f;out[3]=0.98f;out[4]=1f;}
        else if(delta==getChildCount()-1){out[0]=-nudge;out[1]=-lift*1.7f;out[2]=-7f;out[3]=0.96f;out[4]=1f;}
        else{out[0]=0;out[1]=-lift*2.5f;out[2]=3f;out[3]=0.94f;out[4]=1f;}
    }
    void place(boolean animate){
        if(getWidth()==0)return;
        float[] at=new float[5];
        for(int i=0;i<getChildCount();i++){
            android.view.View card=getChildAt(i);
            int id=(Integer)card.getTag();
            rest(id,at);
            card.setElevation(Ui.dp(getContext(),id==front?18:at[4]==0?0:6));
            pose(card,at[0],at[1],at[2],at[3],at[4],animate);
        }
        android.view.View peek=card(rightOf(front));if(peek!=null)peek.bringToFront();
        android.view.View top=card(front);if(top!=null)top.bringToFront();
    }
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event){
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();dragging=false;caught=false;for(int i=0;i<getChildCount();i++)getChildAt(i).animate().cancel();return false;}
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE && !dragging){
            float dx=event.getX()-downX,dy=event.getY()-downY;
            if(Math.abs(dx)>Ui.dp(getContext(),18) && Math.abs(dx)>Math.abs(dy)){dragging=true;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        }
        return false;
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event){
        android.view.View top=card(front);if(top==null)return false;
        float span=Math.max(1,getWidth()*0.55f);
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_MOVE){
            float raw=event.getX()-downX;
            float dx=stick(raw,span);
            boolean past=Math.abs(raw)>span*0.42f;
            if(past!=caught){caught=past;Haptics.fire(getContext(),caught?Haptics.STICK:Haptics.RELEASE);}
            top.setTranslationX(dx);top.setRotation(dx/span*5f);
            boolean fromRight=raw<0;
            android.view.View side=card(fromRight?rightOf(front):leftOf(front));
            if(side!=null){
                float p=Math.min(1f,Math.abs(dx)/(span*0.7f));
                float[] at=new float[5];rest(fromRight?rightOf(front):leftOf(front),at);
                side.setTranslationX(at[0]*(1f-p));side.setTranslationY(at[1]*(1f-p));side.setRotation(at[2]*(1f-p));
                side.setScaleX(at[3]+(1f-at[3])*p);side.setScaleY(at[3]+(1f-at[3])*p);side.setAlpha(at[4]+(1f-at[4])*p);
            }
            return true;
        }
        if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP || event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL){
            float raw=event.getX()-downX;
            boolean commit=event.getActionMasked()==android.view.MotionEvent.ACTION_UP && Math.abs(raw)>span*0.42f;
            if(commit){int from=front;front=raw<0?rightOf(front):leftOf(front);place(true);if(listener!=null)listener.moved(from,front);}
            else{Haptics.fire(getContext(),Haptics.RELEASE);place(true);}
            dragging=false;caught=false;
            return true;
        }
        return dragging;
    }
    private float stick(float raw,float span){
        float sign=Math.signum(raw),mag=Math.abs(raw),notch=span*0.42f;
        float pulled=mag<notch?mag*0.52f:notch*0.52f+(mag-notch)*0.2f;
        return sign*pulled;
    }
}
