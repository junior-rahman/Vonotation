package app.voiceflow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * A private dictation that could not be inserted. Held only in memory for two minutes,
 * never written to History, drafts, backups or the clipboard unless the user explicitly
 * taps "Copy privately".
 */
final class PrivatePending {
    static final long KEEP_MS=2*60*1000L;
    private static String text;
    private static long expires;
    private static long generation;
    private static final android.os.Handler expiryHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private PrivatePending() {}
    static synchronized void offer(String value){
        text=value;expires=android.os.SystemClock.elapsedRealtime()+KEEP_MS;final long owner=++generation;
        expiryHandler.postDelayed(()->expire(owner),KEEP_MS);
    }
    private static synchronized void expire(long owner){if(owner!=generation)return;long left=expires-android.os.SystemClock.elapsedRealtime();if(left>0){expiryHandler.postDelayed(()->expire(owner),left);return;}text=null;generation++;}
    static synchronized boolean available(){if(text!=null && android.os.SystemClock.elapsedRealtime()>expires)text=null;return text!=null;}
    static synchronized long remainingSeconds(){return available()?Math.max(0,(expires-android.os.SystemClock.elapsedRealtime())/1000):0;}
    /** Explicit user action only. Marks the clip sensitive and forgets the pending value. */
    static synchronized boolean copy(Context context){
        if(!available())return false;
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip=ClipData.newPlainText("VonoTation private dictation",text);
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);
        try{clipboard.setPrimaryClip(clip);discard();return true;}catch(RuntimeException failed){return false;}
    }
    static synchronized void discard(){text=null;generation++;}
}
