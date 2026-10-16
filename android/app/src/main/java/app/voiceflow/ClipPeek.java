package app.voiceflow;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;

/** Focused helper used only by an explicit Clipboard action; every launch has a short-lived request identity. */
public final class ClipPeek extends Activity {
    static final String REQUEST="app.voiceflow.CLIPBOARD_REQUEST";
    static Runnable after;
    private static int nextRequest,pendingRequest,activeRequest;
    private static java.lang.ref.WeakReference<ClipPeek> visible=new java.lang.ref.WeakReference<>(null);
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private int request;
    private boolean read;

    static int prepare(Runnable callback){
        nextRequest=nextRequest==Integer.MAX_VALUE?1:nextRequest+1;
        pendingRequest=nextRequest;activeRequest=0;after=callback;return pendingRequest;
    }
    static boolean pending(int value){return value>0&&pendingRequest==value;}
    static boolean active(int value){return value>0&&activeRequest==value;}
    static void cancel(int value){if(value>0&&pendingRequest==value){pendingRequest=0;activeRequest=0;after=null;}ClipPeek activity=visible.get();if(activity!=null&&activity.request==value)activity.finish();}

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        visible=new java.lang.ref.WeakReference<>(this);
        accept(getIntent());
    }
    @Override protected void onNewIntent(android.content.Intent intent){super.onNewIntent(intent);setIntent(intent);accept(intent);}
    private void accept(android.content.Intent intent){
        handler.removeCallbacksAndMessages(null);read=false;
        request=intent.getIntExtra(REQUEST,0);
        if(!pending(request)){finish();return;}
        activeRequest=request;
        overridePendingTransition(0,0);
        View view=new View(this);
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        setContentView(view);
        getWindow().setLayout(1,1);
        getWindow().setGravity(Gravity.START|Gravity.TOP);
        final int owner=request;
        handler.postDelayed(()->{if(request!=owner)return;Runnable callback=pending(owner)?after:null;cancel(owner);finish();if(callback!=null)callback.run();},1500);
    }
    @Override public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if(has){if(active(request))pull();else finish();}
    }
    private void pull() {
        if(read||!pending(request))return;
        read=true;
        try{ClipList.capture(this);}finally{
            Runnable next=after;cancel(request);
            finish();overridePendingTransition(0,0);
            if(next!=null)next.run();
        }
    }
    @Override protected void onDestroy(){
        handler.removeCallbacksAndMessages(null);Runnable callback=pending(request)?after:null;cancel(request);if(visible.get()==this)visible.clear();super.onDestroy();
        if(callback!=null)callback.run();
    }
}
