package app.voiceflow;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** The existing coral control, reduced to an edge dot while optional thought capture is idle. */
final class QuickThoughtOverlay {
    private final Context context;
    private final WindowManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh;
    private final Runnable capture;
    private final java.util.function.BooleanSupplier mayCapture;
    private FrameLayout host;
    private WindowManager.LayoutParams layout;
    private boolean collapsed;
    private long lastInteraction;
    QuickThoughtOverlay(Context context,WindowManager manager,Runnable refresh,java.util.function.BooleanSupplier mayCapture,Runnable capture){this.context=context;this.manager=manager;this.refresh=refresh;this.mayCapture=mayCapture;this.capture=capture;}
    void show(){
        boolean compact=QuickThoughtPolicy.collapsed(SystemClock.elapsedRealtime(),lastInteraction,Dictation.isWorking());
        if(host!=null && collapsed==compact){position();armIdle(compact);return;}
        hide();collapsed=compact;
        int size=Ui.dp(context,48);
        host=new FrameLayout(context);host.setClipChildren(false);host.setContentDescription(compact?"Quick thoughts: tap to record":"Record a quick thought");host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);host.setClickable(true);
        View face;
        if(compact){
            face=new View(context);face.setBackground(FlowAccessibility.solidFace(context,Ui.dp(context,6)));face.setAlpha(.35f);
            host.addView(face,new FrameLayout.LayoutParams(Ui.dp(context,12),Ui.dp(context,12),Gravity.CENTER));
        }else{
            FloatingIcon.Control mic=new FloatingIcon.Control(context,"micKeyboardHidden",Color.WHITE,Ui.dp(context,30));mic.setBackground(FlowAccessibility.solidFace(context,Ui.dp(context,12)));mic.setAlpha(.65f);face=mic;
            host.addView(face,new FrameLayout.LayoutParams(size,size,Gravity.CENTER));
        }
        // One semantic click target: a tiny visual dot still has a 48dp hit area.
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.setOnClickListener(v->open());attachDrag();
        layout=new WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.LEFT;layout.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        position();
        try{manager.addView(host,layout);}catch(RuntimeException denied){host=null;layout=null;}
        armIdle(compact);
    }
    private void armIdle(boolean compact){handler.removeCallbacks(refresh);if(!compact)handler.postDelayed(refresh,Math.max(1,QuickThoughtPolicy.IDLE_MS-(SystemClock.elapsedRealtime()-lastInteraction)));}
    private void open(){
        if(!mayCapture.getAsBoolean()){refresh.run();return;}
        if(!Store.quickThoughts(context) || Dictation.isWorking() || VoiceTake.running() || VoiceTake.updateBusy() || Translate.going() || LineWire.running())return;
        lastInteraction=SystemClock.elapsedRealtime();Haptics.fire(context,Haptics.PRESS);hide();
        // The existing bound dictation service owns capture. The dot never opens an activity.
        capture.run();
    }
    /** Recording and processing are active time; the idle minute starts after completion. */
    void captureFinished(){lastInteraction=SystemClock.elapsedRealtime();}
    private void position(){
        if(layout==null)return;
        DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);
        int size=Ui.dp(context,48),edge=Ui.dp(context,4),saved=Store.prefs(context).getInt("rail_anchor_x",metrics.widthPixels-size/2-Ui.dp(context,12));
        // Keep the idle control on the same side as the ordinary dictation control.
        layout.x=saved<metrics.widthPixels/2?edge:Math.max(edge,metrics.widthPixels-size-edge);
        layout.y=Math.max(Ui.dp(context,40),Math.min(metrics.heightPixels-size-Ui.dp(context,48),Store.prefs(context).getInt("rail_anchor_y",Math.round(metrics.heightPixels*.52f))-size/2));
        if(host!=null && host.isAttachedToWindow())try{manager.updateViewLayout(host,layout);}catch(RuntimeException ignored){}
    }
    private void attachDrag(){
        host.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int startY;boolean moved;
            public boolean onTouch(View view,MotionEvent event){
                if(layout==null)return false;
                if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getRawX();downY=event.getRawY();startY=layout.y;moved=false;return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getRawX()-downX,dy=event.getRawY()-downY;if(!moved && Math.abs(dx)+Math.abs(dy)>Ui.dp(context,12))moved=true;if(moved){DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);layout.x=event.getRawX()<metrics.widthPixels/2?Ui.dp(context,4):metrics.widthPixels-layout.width-Ui.dp(context,4);layout.y=Math.max(Ui.dp(context,40),Math.min(metrics.heightPixels-layout.height-Ui.dp(context,48),startY+(int)dy));try{manager.updateViewLayout(host,layout);}catch(RuntimeException ignored){}}return true;}
                if(event.getActionMasked()==MotionEvent.ACTION_UP){if(moved){lastInteraction=SystemClock.elapsedRealtime();Store.prefs(context).edit().putInt("rail_anchor_x",layout.x+layout.width/2).putInt("rail_anchor_y",layout.y+layout.height/2).apply();handler.post(refresh);}else view.performClick();return true;}
                return event.getActionMasked()==MotionEvent.ACTION_CANCEL;
            }
        });
    }
    void hide(){handler.removeCallbacks(refresh);if(host!=null)try{manager.removeView(host);}catch(RuntimeException ignored){}host=null;layout=null;}
}
