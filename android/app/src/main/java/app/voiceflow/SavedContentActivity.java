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
        LinearLayout root=Ui.column(this);root.setBackgroundColor(Daybook.paper(this));root.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,12));
        root.setOnApplyWindowInsetsListener((v,insets)->{if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());v.setPadding(Ui.dp(this,20)+bars.left,Ui.dp(this,12)+bars.top,Ui.dp(this,20)+bars.right,Ui.dp(this,12)+bars.bottom);}return insets;});
        root.addView(Daybook.button(this,"Back",false,v->finish()));root.addView(Daybook.text(this,deleted?"Recently deleted":"Search saved text",24,Daybook.ink(this),true));
        query=Daybook.field(this,state==null?"":state.getString("query",""),deleted?"Filter deleted items":"Search dictations, thoughts and clipboard",false);query.setSingleLine(true);root.addView(query);
        if(deleted)root.addView(Daybook.text(this,"Undo is available for 24 hours. Clipboard expiry dates do not change.",12,Daybook.muted(this),false));
        ScrollView scroll=new ScrollView(this);results=Ui.column(this);scroll.addView(results);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
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
                for(Hit hit:hits){String label=hit.title.trim();if(label.isEmpty())label="Untitled";int end=label.offsetByCodePoints(0,Math.min(100,label.codePointCount(0,label.length())));label=label.substring(0,end);Button row=Daybook.button(this,hit.kind+" · "+label,false,v->{if(deleted){boolean ok=DeletionUndo.restore(this,hit.kind,hit.id);Toast.makeText(this,ok?"Restored.":"Could not restore; it may have expired.",Toast.LENGTH_SHORT).show();load();}else open(hit);});row.setContentDescription((deleted?"Undo delete: ":"Open ")+hit.kind+" "+label);results.addView(row,new LinearLayout.LayoutParams(-1,-2));}
            });
        });
    }
    private String currentText(Hit hit){
        if(hit.kind.equals("clipboard"))return ClipList.text(this,hit.id);
        try{JSONArray entries=new JSONArray(Store.get(this,hit.kind.equals("dictation")?"history":"notes","[]"));for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);if(item.optLong("id")==hit.id)return item.optString("text").isEmpty()?item.optString("raw"):item.optString("text");}}catch(Exception ignored){}return null;
    }
    private void open(Hit hit){String text=currentText(hit);if(text==null){Toast.makeText(this,"This item was deleted or expired.",Toast.LENGTH_SHORT).show();load();return;}
        opened=new AlertDialog.Builder(this).setTitle(hit.kind).setMessage(text).setNegativeButton("Close",null).setPositiveButton("Copy",(d,w)->{String current=currentText(hit);if(current!=null)Ui.copy(this,current);else Toast.makeText(this,"This item expired or was deleted.",Toast.LENGTH_SHORT).show();}).show();
    }
}
