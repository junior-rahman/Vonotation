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
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

/** The existing coral control, reduced to a dot while optional thought capture is idle. */
final class QuickThoughtOverlay {
    private final Context context;
    private final WindowManager manager;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh;
    private final Runnable capture;
    private final Runnable openThoughts;
    private final java.util.function.BooleanSupplier mayCapture;
    private FrameLayout host;
    private WindowManager.LayoutParams layout;
    private boolean collapsed;
    private boolean interacting;
    private Runnable pendingHold;
    private long lastInteraction;
    QuickThoughtOverlay(Context context,WindowManager manager,Runnable refresh,java.util.function.BooleanSupplier mayCapture,Runnable capture,Runnable openThoughts){this.context=context;this.manager=manager;this.refresh=refresh;this.mayCapture=mayCapture;this.capture=capture;this.openThoughts=openThoughts;}
    void show(){
        if(host!=null && interacting)return;
        boolean compact=QuickThoughtPolicy.collapsed(SystemClock.elapsedRealtime(),lastInteraction,Dictation.isWorking());
        if(host!=null && collapsed==compact){position();armIdle(compact);return;}
        hide();collapsed=compact;
        int size=Ui.dp(context,48);
        host=new FrameLayout(context);host.setClipChildren(false);host.setContentDescription("Quick thoughts: tap to record, hold to open thoughts, drag to move");host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);host.setClickable(true);
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
        host.setOnClickListener(v->open());
        host.setHapticFeedbackEnabled(Store.bool(context,"haptic_feedback",true));
        host.setOnLongClickListener(v->{
            if(!mayCapture.getAsBoolean())return false;
            lastInteraction=SystemClock.elapsedRealtime();hide();openThoughts.run();return true;
        });
        host.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View view,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(view,info);info.addAction(new AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK,"Open Quick thoughts"));}
        });
        host.setTooltipText("Tap to record · Hold to open Quick thoughts · Drag to move");
        attachDrag();
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
        // A tap always records in place; opening the app is reserved for an explicit hold.
        capture.run();
    }
    /** Recording and processing are active time; the idle minute starts after completion. */
    void captureFinished(){lastInteraction=SystemClock.elapsedRealtime();}
    private void position(){
        if(layout==null || interacting)return;
        DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);
        int savedX=Store.prefs(context).getInt("rail_anchor_x",metrics.widthPixels-layout.width/2-Ui.dp(context,16));
        int savedY=Store.prefs(context).getInt("rail_anchor_y",Math.round(metrics.heightPixels*.52f));
        // Both idle and active controls persist the microphone's center, without edge snapping.
        layout.x=savedX-layout.width/2;layout.y=savedY-layout.height/2;clamp(metrics);
        if(host!=null && host.isAttachedToWindow())try{manager.updateViewLayout(host,layout);}catch(RuntimeException ignored){}
    }
    private void clamp(DisplayMetrics metrics){
        int left=0,right=metrics.widthPixels,top=Ui.dp(context,32),bottom=metrics.heightPixels-Ui.dp(context,48);
        if(android.os.Build.VERSION.SDK_INT>=30)try{
            android.graphics.Insets safe=manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());
            left=safe.left;right-=safe.right;top=Math.max(top,safe.top);bottom=Math.min(bottom,metrics.heightPixels-safe.bottom-Ui.dp(context,12));
        }catch(RuntimeException unavailable){}
        layout.x=Math.max(left,Math.min(Math.max(left,right-layout.width),layout.x));
        layout.y=Math.max(top,Math.min(Math.max(top,bottom-layout.height),layout.y));
    }
    private void attachDrag(){
        host.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY;int startX,startY;boolean down,moved,held,cancelled;
            final int slop=ViewConfiguration.get(context).getScaledTouchSlop();
            final Runnable hold=()->{if(down && !moved && !cancelled && host!=null && host.isAttachedToWindow()){held=true;host.performLongClick();}};
            public boolean onTouch(View view,MotionEvent event){
                if(layout==null || host!=view)return true;
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN){
                    downX=event.getRawX();downY=event.getRawY();startX=layout.x;startY=layout.y;down=true;moved=false;held=false;cancelled=false;interacting=true;view.setPressed(true);
                    handler.removeCallbacks(refresh);pendingHold=hold;handler.postDelayed(hold,ViewConfiguration.getLongPressTimeout());return true;
                }
                if(!down)return true;
                if(action==MotionEvent.ACTION_POINTER_DOWN || action==MotionEvent.ACTION_POINTER_UP){cancelled=true;handler.removeCallbacks(hold);view.setPressed(false);return true;}
                if(action==MotionEvent.ACTION_MOVE){
                    if(held || cancelled)return true;
                    float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                    if(!moved && dx*dx+dy*dy>slop*slop){moved=true;handler.removeCallbacks(hold);view.setPressed(false);}
                    if(moved){DisplayMetrics metrics=new DisplayMetrics();manager.getDefaultDisplay().getRealMetrics(metrics);layout.x=startX+Math.round(dx);layout.y=startY+Math.round(dy);clamp(metrics);try{manager.updateViewLayout(host,layout);}catch(RuntimeException ignored){}}
                    return true;
                }
                if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
                    down=false;interacting=false;handler.removeCallbacks(hold);pendingHold=null;view.setPressed(false);
                    if(action==MotionEvent.ACTION_UP && !cancelled && moved){lastInteraction=SystemClock.elapsedRealtime();Store.prefs(context).edit().putInt("rail_anchor_x",layout.x+layout.width/2).putInt("rail_anchor_y",layout.y+layout.height/2).apply();}
                    else if(action==MotionEvent.ACTION_UP && !cancelled && !held){view.performClick();return true;}
                    handler.post(refresh);return true;
                }
                return true;
            }
        });
    }
    void hide(){handler.removeCallbacks(refresh);if(pendingHold!=null)handler.removeCallbacks(pendingHold);pendingHold=null;interacting=false;if(host!=null)try{manager.removeView(host);}catch(RuntimeException ignored){}host=null;layout=null;}
}
