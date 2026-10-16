package app.voiceflow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/** Copies Android makes readable, retained privately on this phone for 30 days. */
final class ClipList {
    private static final String KEY="clips";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static boolean watching;
    private static int generation;
    static final int UNAVAILABLE=0,AVAILABLE=1,EMPTY=2,SENSITIVE=3,NON_TEXT=4;
    private static int status=UNAVAILABLE;
    private static String latest,error="";
    /** Existing floating controls and the foreground Daybook have independent observers. */
    static Runnable onChanged;
    private static final ArrayList<Runnable> OBSERVERS=new ArrayList<>();
    static synchronized int generation(){return generation;}
    static int status(){return status;}
    static String latest(){return status==AVAILABLE?latest:null;}
    static String error(){return error;}
    private ClipList() {}

    static void watch(Context context) {
        Context app=context.getApplicationContext();archive(app);ClipboardExpiryJob.schedule(app);
        if(watching)return;
        ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null)return;watching=true;
        // Event-driven only: never borrow focus or launch an activity for a background copy.
        clipboard.addPrimaryClipChangedListener(()->capture(app));
    }
    static void observe(Runnable observer){synchronized(OBSERVERS){if(!OBSERVERS.contains(observer))OBSERVERS.add(observer);}}
    static void forget(Runnable observer){synchronized(OBSERVERS){OBSERVERS.remove(observer);}}
    /** Explicit normal dictation recovery is eligible; private recovery never calls this. */
    static void remember(Context context,String text) {
        if(!ClipboardText.storable(text))return;
        try{ClipboardArchive saved=archive(context);if(saved==null)return;saved.remember(text,System.currentTimeMillis());changed(context);}
        catch(RuntimeException unavailable){failed();}
    }
    /** A bounded tray view, not a retention limit. Full copies remain available in the Clipboard card. */
    static String[] recent(Context context) {
        try{
            ClipboardArchive saved=archive(context);if(saved==null)return new String[0];long now=System.currentTimeMillis();ArrayList<String> out=new ArrayList<>();
            for(ClipboardArchive.Item item:saved.page(0,0,ClipboardText.MAX_ITEMS,now)){String text=saved.text(item.id,now);if(ClipboardText.usable(text)&&!out.contains(text))out.add(text);}
            return out.toArray(new String[0]);
        }catch(RuntimeException unavailable){failed();return new String[0];}
    }
    static List<ClipboardArchive.Item> page(Context context,long beforeTime,long beforeId,int count){
        try{ClipboardArchive saved=archive(context);return saved==null?java.util.Collections.emptyList():saved.page(beforeTime,beforeId,count,System.currentTimeMillis());}
        catch(RuntimeException unavailable){failed();return java.util.Collections.emptyList();}
    }
    static String text(Context context,long id){try{ClipboardArchive saved=archive(context);return saved==null?null:saved.text(id,System.currentTimeMillis());}catch(RuntimeException unavailable){failed();return null;}}
    static List<ClipboardArchive.Day> days(Context context){try{ClipboardArchive saved=archive(context);return saved==null?java.util.Collections.emptyList():saved.days(java.util.TimeZone.getDefault(),System.currentTimeMillis());}catch(RuntimeException unavailable){failed();return java.util.Collections.emptyList();}}
    static List<ClipboardArchive.Item> dayPage(Context context,ClipboardArchive.Day day,long beforeTime,long beforeId,int count){try{ClipboardArchive saved=archive(context);return saved==null?java.util.Collections.emptyList():saved.dayPage(day,java.util.TimeZone.getDefault(),beforeTime,beforeId,count,System.currentTimeMillis());}catch(RuntimeException unavailable){failed();return java.util.Collections.emptyList();}}
    static boolean remove(Context context,long id){try{ClipboardArchive saved=archive(context);if(saved!=null&&saved.remove(id)){changed(context);return true;}}catch(RuntimeException unavailable){failed();}return false;}
    static long nextExpiry(Context context){try{ClipboardArchive saved=archive(context);return saved==null?0:saved.nextExpiry();}catch(RuntimeException unavailable){failed();return 0;}}
    static long nextVisibleExpiry(Context context){try{ClipboardArchive saved=archive(context);return saved==null?0:saved.nextVisibleExpiry(System.currentTimeMillis());}catch(RuntimeException unavailable){failed();return 0;}}
    static void expire(Context context){try{ClipboardArchive saved=archive(context);if(saved!=null&&saved.prune(System.currentTimeMillis())>0)changed(context);}catch(RuntimeException unavailable){failed();}}
    /** Reads actual text only, never a URI, Intent, or unrelated selected input text. */
    static void capture(Context context) {
        boolean privateContext=FlowKeyboard.clipboardSensitiveContext()||FlowAccessibility.clipboardSensitiveContext();
        int next=UNAVAILABLE;String value=null;ClipData data=null;
        try{
            ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){
                data=clipboard.getPrimaryClip();
                if(data!=null){
                    if(sensitive(data))next=SENSITIVE;
                    else if(data.getItemCount()==0)next=EMPTY;
                    else{CharSequence text=data.getItemAt(0).getText();if(text==null)next=NON_TEXT;else if(ClipboardText.usable(text.toString())){value=text.toString();next=AVAILABLE;}else next=EMPTY;}
                }
            }
        }catch(RuntimeException ignored){}
        if(privateContext){
            try{ClipboardArchive saved=archive(context);if(saved!=null){if(value!=null)saved.exclude(event(data,value));else if(next==UNAVAILABLE)saved.exclude(null);}}catch(RuntimeException unavailable){failed();}
            status=SENSITIVE;latest=null;notifyChanged();return;
        }
        status=next;latest=value;
        if(next==AVAILABLE){
            try{
                ClipboardArchive saved=archive(context);if(saved!=null){
                    long timestamp=data.getDescription()==null?0:data.getDescription().getTimestamp();
                    String label=data.getDescription()==null?"":String.valueOf(data.getDescription().getLabel());
                    String event=event(data,value);
                    if(saved.excluded(event)){status=SENSITIVE;latest=null;notifyChanged();return;}
                    if(saved.capture(value,timestamp,event,"VonoTation dictation".equals(label),System.currentTimeMillis())){changed(context);return;}
                }
            }catch(RuntimeException unavailable){failed();return;}
        }
        notifyChanged();
    }
    private static String event(ClipData data,String value){long timestamp=data==null||data.getDescription()==null?0:data.getDescription().getTimestamp();return timestamp+":"+ClipboardArchive.fingerprint(value);}
    static boolean sensitive(ClipData data) {
        if(data==null||data.getDescription()==null)return false;
        PersistableBundle extras=data.getDescription().getExtras();
        return extras!=null && extras.getBoolean("android.content.extra.IS_SENSITIVE");
    }
    private static synchronized ClipboardArchive archive(Context context){
        try{
            ClipboardArchive saved=ClipboardArchive.get(context);JSONArray old;
            try{old=new JSONArray(Store.get(context,KEY,"[]"));}catch(Exception invalid){old=new JSONArray();}
            saved.migrateLegacy(old,System.currentTimeMillis());
            // Database commit precedes clearing the old snapshot; a crash cannot lose legacy copies.
            if(Store.prefs(context).contains(KEY))Store.prefs(context).edit().remove(KEY).commit();
            return saved;
        }catch(RuntimeException unavailable){failed();return null;}
    }
    private static void changed(Context context){synchronized(ClipList.class){generation++;}error="";ClipboardExpiryJob.schedule(context);notifyChanged();}
    private static void failed(){if(!error.isEmpty())return;error="Clipboard could not be saved on this phone. Free some storage and try again.";notifyChanged();}
    private static void notifyChanged(){
        Runnable run=onChanged;if(run!=null)MAIN.post(run);
        synchronized(OBSERVERS){for(Runnable observer:OBSERVERS)MAIN.post(observer);}
    }
}
