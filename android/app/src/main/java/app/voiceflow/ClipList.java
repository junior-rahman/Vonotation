package app.voiceflow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import org.json.JSONArray;

/** Bounded on-device text history, excluded from Drive backup. Android does not expose other keyboards' history. */
final class ClipList {
    private static final String KEY="clips";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static boolean watching;
    private static int generation;
    static final int UNAVAILABLE=0,AVAILABLE=1,EMPTY=2,SENSITIVE=3,NON_TEXT=4;
    private static int status=UNAVAILABLE;
    private static String latest;
    /** Fired on the main thread after history or latest-read status changes. */
    static Runnable onChanged;
    static int generation(){return generation;}
    static int status(){return status;}
    static String latest(){return status==AVAILABLE?latest:null;}
    private ClipList() {}

    static void watch(Context context) {
        if(watching)return;
        Context app=context.getApplicationContext();
        ClipboardManager clipboard=(ClipboardManager)app.getSystemService(Context.CLIPBOARD_SERVICE);
        if(clipboard==null)return;
        watching=true;
        // Event-driven only. Never borrow focus or poll in response to a background copy.
        clipboard.addPrimaryClipChangedListener(()->capture(app));
    }
    static void remember(Context context,String text) {
        if(!ClipboardText.storable(text))return;
        synchronized(ClipList.class){
            JSONArray next=new JSONArray();for(String item:ClipboardText.prepend(text,recent(context)))next.put(item);
            Store.prefs(context).edit().putString(KEY,next.toString()).apply();generation++;
        }
        notifyChanged();
    }
    static String[] recent(Context context) {
        JSONArray old=read(context);String[] items=new String[old.length()];
        for(int i=0;i<old.length();i++)items[i]=old.optString(i,"");
        return ClipboardText.prepend(null,items);
    }
    /** Reads only actual text, never a URI/Intent coercion or the user's unrelated selected field text. */
    static void capture(Context context) {
        int next=UNAVAILABLE;String value=null;
        try{
            ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null){
                ClipData data=clipboard.getPrimaryClip();
                if(data!=null){
                    if(sensitive(data))next=SENSITIVE;
                    else if(data.getItemCount()==0)next=EMPTY;
                    else{
                        CharSequence text=data.getItemAt(0).getText();
                        if(text==null)next=NON_TEXT;
                        else if(ClipboardText.usable(text.toString())){value=text.toString();next=AVAILABLE;}
                        else next=EMPTY;
                    }
                }
                // A null result can mean Android denied access. Do not show stale text as the latest copy.
            }
        }catch(RuntimeException ignored){}
        status=next;latest=value;
        if(next==AVAILABLE&&ClipboardText.storable(value))remember(context,value);else notifyChanged();
    }
    private static void notifyChanged(){Runnable run=onChanged;if(run!=null)MAIN.post(run);}
    private static boolean sensitive(ClipData data) {
        if(data.getDescription()==null)return false;
        PersistableBundle extras=data.getDescription().getExtras();
        return extras!=null && extras.getBoolean("android.content.extra.IS_SENSITIVE");
    }
    private static JSONArray read(Context context) {
        try{return new JSONArray(Store.get(context,KEY,"[]"));}catch(Exception e){return new JSONArray();}
    }
}
