package app.voiceflow;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Searches complete saved text on a worker, including text beyond clipboard previews/chunks. */
public final class SavedContentActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private AlertDialog opened;
    private final Runnable expiry=()->{if(opened!=null)opened.dismiss();load();};
    private EditText query;
    private LinearLayout results;
    private boolean deleted;
    private volatile int generation;
    private final Runnable search=()->load();
    static final class Hit {final String kind,title,text;final long id;Hit(String k,long i,String t,String x){kind=k;id=i;title=t;text=x;}}
    @Override public void onCreate(Bundle state){super.onCreate(state);if(AppProduct.isNotes()){finish();return;}
        deleted=getIntent().getBooleanExtra("deleted",false);
        LinearLayout shell=Ui.column(this);shell.setBackground(new Daybook.Backdrop(this));
        getWindow().setStatusBarColor(Daybook.paper(this));getWindow().setNavigationBarColor(Daybook.paper(this));
        boolean light=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)!=android.content.res.Configuration.UI_MODE_NIGHT_YES;
        getWindow().getDecorView().setSystemUiVisibility(light?android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0);
        shell.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}return insets;});
        FrameLayout frame=new FrameLayout(this);shell.addView(frame,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout root=Ui.column(this);root.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,12));
        frame.addView(root,new FrameLayout.LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels,Ui.dp(this,560)),-1,android.view.Gravity.CENTER_HORIZONTAL));
        LinearLayout toolbar=Ui.row(this);toolbar.addView(Daybook.iconButton(this,"back","Back",v->finish()),new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,48)));
        TextView heading=Daybook.text(this,deleted?"Recently deleted":"Search",24,Daybook.ink(this),true);toolbar.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(toolbar);Ui.space(root,12);
        root.addView(Daybook.text(this,deleted?"Restore something you removed":"Find your saved words",16,Daybook.ink(this),true));Ui.space(root,5);
        root.addView(Daybook.text(this,deleted?"Undo is available for 24 hours. Clipboard expiry dates stay the same.":"Dictations, thoughts and clipboard — together.",13,Daybook.muted(this),false));Ui.space(root,18);
        LinearLayout searchRow=Ui.row(this);searchRow.setBackground(Daybook.fieldSurface(this));
        Daybook.Symbol searchIcon=new Daybook.Symbol(this,"search");searchRow.addView(searchIcon,new LinearLayout.LayoutParams(Ui.dp(this,42),Ui.dp(this,48)));
        query=Daybook.field(this,state==null?"":state.getString("query",""),deleted?"Filter deleted items":"Search saved text",false);query.setSingleLine(true);query.setBackgroundColor(android.graphics.Color.TRANSPARENT);query.setPadding(0,Ui.dp(this,11),0,Ui.dp(this,11));searchRow.addView(query,new LinearLayout.LayoutParams(0,-2,1));
        android.view.View clear=Daybook.iconButton(this,"close","Clear search",v->query.setText(""));searchRow.addView(clear,new LinearLayout.LayoutParams(Ui.dp(this,44),Ui.dp(this,48)));root.addView(searchRow);Ui.space(root,18);
        ScrollView scroll=new ScrollView(this);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);results=Ui.column(this);results.setPadding(0,0,0,Ui.dp(this,12));scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(shell);root.setFocusableInTouchMode(true);root.requestFocus();
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){generation++;main.removeCallbacks(search);main.postDelayed(search,250);}public void afterTextChanged(Editable e){}});
        load();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putString("query",query.getText().toString());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){generation++;main.removeCallbacksAndMessages(null);worker.shutdownNow();super.onDestroy();}
    private static boolean matches(String text,String term){return text.toLowerCase(Locale.ROOT).contains(term);}
    private void load(){final int owner=++generation;String term=query.getText().toString().trim().toLowerCase(Locale.ROOT);results.removeAllViews();results.addView(Daybook.text(this,"Searching…",13,Daybook.muted(this),false));
        worker.execute(()->{List<Hit> hits=new ArrayList<>();String failure=null;long expires=0;
            try{
                if(deleted){JSONArray undo=DeletionUndo.list(this);for(int i=undo.length()-1;i>=0;i--){JSONObject row=undo.getJSONObject(i),entry=row.getJSONObject("entry");String text=entry.optString("title")+"\n"+entry.optString("text")+"\n"+entry.optString("raw");if(matches(text,term))hits.add(new Hit(row.optString("kind"),row.optLong("id"),entry.optString("title",entry.optString("text")),text));}
                    for(ClipboardArchive.Item item:ClipboardArchive.get(this).deleted(System.currentTimeMillis()))if(matches(item.preview,term))hits.add(new Hit("clipboard",item.id,item.preview,""));
                }else{
                    for(String key:new String[]{"history","notes"}){JSONArray entries=new JSONArray(Store.get(this,key,"[]"));for(int i=0;i<entries.length()&&hits.size()<100&&owner==generation;i++){JSONObject entry=entries.getJSONObject(i);if(key.equals("notes")&&!entry.optBoolean("thought"))continue;String body=entry.optString("text"),raw=entry.optString("raw"),title=entry.optString("title",body);if(matches(title+"\n"+body+"\n"+raw,term))hits.add(new Hit(key.equals("history")?"dictation":"thought",entry.optLong("id"),title,body.isEmpty()?raw:body));}}
                    long before=0,id=0,now=System.currentTimeMillis();ClipboardArchive archive=ClipboardArchive.get(this);
                    while(hits.size()<100&&owner==generation&&!Thread.currentThread().isInterrupted()){
                        List<ClipboardArchive.Item> page=archive.page(before,id,50,now);if(page.isEmpty())break;
                        for(ClipboardArchive.Item item:page){String text=archive.text(item.id,System.currentTimeMillis());if(text!=null&&matches(text,term))hits.add(new Hit("clipboard",item.id,item.preview,text));if(hits.size()>=100)break;}
                        ClipboardArchive.Item last=page.get(page.size()-1);before=last.copiedAt;id=last.id;
                    }
                }
                expires=ClipboardArchive.get(this).nextVisibleExpiry(System.currentTimeMillis());
            }catch(Exception unavailable){failure="Search could not read saved data. Your items were not changed.";}
            final String error=failure;final long nextExpiry=expires;main.post(()->{if(owner!=generation||isDestroyed())return;results.removeAllViews();main.removeCallbacks(expiry);if(nextExpiry>0)main.postDelayed(expiry,Math.max(1,nextExpiry-System.currentTimeMillis()));if(error!=null){results.addView(Daybook.text(this,error,13,Daybook.coral(this),false));return;}
                results.addView(Daybook.text(this,hits.isEmpty()?"No matching items.":hits.size()>=100?"First 100 matches. Refine your search for more.":hits.size()+" matching items",12,Daybook.muted(this),false));
                if(hits.isEmpty()){Ui.space(results,24);LinearLayout empty=Daybook.card(this);empty.setPadding(Ui.dp(this,20),Ui.dp(this,24),Ui.dp(this,20),Ui.dp(this,24));empty.addView(Daybook.text(this,deleted?"Nothing to restore":"No saved text found",18,Daybook.ink(this),true));Ui.space(empty,8);empty.addView(Daybook.text(this,term.isEmpty()?(deleted?"Deleted items appear here for 24 hours.":"Your saved words will appear here."):"Try a different word or clear your search.",14,Daybook.muted(this),false));results.addView(empty);}
                for(Hit hit:hits)addHit(hit);

            });
        });
    }
    private String kindLabel(String kind){return kind.equals("dictation")?"Dictation":kind.equals("thought")?"Quick thought":"Clipboard";}
    private void addHit(Hit hit){
        Ui.space(results,12);LinearLayout card=Daybook.card(this);card.setPadding(Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16),Ui.dp(this,16));
        LinearLayout top=Ui.row(this);FloatingIcon icon=new FloatingIcon(this,hit.kind.equals("thought")?"thought":hit.kind.equals("clipboard")?"clipboard":"micKeyboardHidden",Daybook.accent(this));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24));ip.rightMargin=Ui.dp(this,8);top.addView(icon,ip);top.addView(Daybook.text(this,kindLabel(hit.kind),12,Daybook.accent(this),true),new LinearLayout.LayoutParams(0,-2,1));card.addView(top);Ui.space(card,10);
        String label=hit.title.trim();if(label.isEmpty())label=hit.text.trim();if(label.isEmpty())label="Untitled";
        TextView preview=Daybook.text(this,label,16,Daybook.ink(this),false);preview.setMaxLines(3);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(preview);
        if(deleted){Ui.space(card,12);Button restore=Daybook.button(this,"Restore",false,v->{boolean ok=DeletionUndo.restore(this,hit.kind,hit.id);Toast.makeText(this,ok?"Restored.":"Could not restore; it may have expired.",Toast.LENGTH_SHORT).show();load();});restore.setContentDescription("Restore "+kindLabel(hit.kind)+": "+label);card.addView(restore,new LinearLayout.LayoutParams(-1,-2));}
        else{card.setBackground(Daybook.ripple(this,Daybook.surface(this),20));card.setFocusable(true);card.setContentDescription("Open "+kindLabel(hit.kind)+": "+label);card.setOnClickListener(v->{Daybook.feedback(v);open(hit);});}
        results.addView(card,new LinearLayout.LayoutParams(-1,-2));
    }
    private String currentText(Hit hit){
        if(hit.kind.equals("clipboard"))return ClipList.text(this,hit.id);
        try{JSONArray entries=new JSONArray(Store.get(this,hit.kind.equals("dictation")?"history":"notes","[]"));for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(item.optLong("id")==hit.id)return item.optString("text").isEmpty()?item.optString("raw"):item.optString("text");}}catch(Exception ignored){}return null;
    }
    private void open(Hit hit){String text=currentText(hit);if(text==null){Toast.makeText(this,"This item was deleted or expired.",Toast.LENGTH_SHORT).show();load();return;}
        opened=new AlertDialog.Builder(this).setTitle(kindLabel(hit.kind)).setMessage(text).setNegativeButton("Close",null).setPositiveButton("Copy",(d,w)->{String current=currentText(hit);if(current!=null)Ui.copy(this,current);else Toast.makeText(this,"This item expired or was deleted.",Toast.LENGTH_SHORT).show();}).show();
    }
}
