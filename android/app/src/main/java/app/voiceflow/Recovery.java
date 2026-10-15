package app.voiceflow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

/** Preserve completed words when the original editor or recorder UI is no longer available. */
final class Recovery {
    static void keep(Context context,String raw,String text,long duration,boolean inserted){
        keep(context,raw,text,duration,inserted,false);
    }
    static void keep(Context context,String raw,String text,long duration,boolean inserted,boolean sensitive){
        if(text==null || text.trim().isEmpty())return;
        if(!sensitive)synchronized(Store.class){Store.save(context,raw,text,duration);if(!inserted)DraftState.set(text);}
        if(inserted)return;
        if(sensitive){
            // Never copy a private dictation automatically. Keep it in memory briefly and
            // let the user choose "Copy privately" in VonoTation.
            PrivatePending.offer(text);
            Toast.makeText(context,"Private dictation was not inserted or copied. Open VonoTation within 2 minutes to copy it privately.",Toast.LENGTH_LONG).show();
            return;
        }
        ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip=ClipData.newPlainText("VonoTation dictation",text);
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);clip.getDescription().setExtras(extras);
        // The system clip stays hidden from other apps, so the tray has to keep the words itself.
        ClipList.remember(context,text);
        boolean copied=false;try{clipboard.setPrimaryClip(clip);copied=true;}catch(RuntimeException ignored){}
        Toast.makeText(context,copied?"Dictation copied. Your words were kept.":"Your dictation was kept in VonoTation.",Toast.LENGTH_SHORT).show();
    }
}
