package app.voiceflow;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Expanded fan. Hold 300 ms: Cancel stays on the button. Delete, Clipboard and Keyboard
 * fan out on a 76 dp arc, edge-aware, 8 dp inside the screen, kept off the caret line.
 * Delete is nearest the thumb and never closes the fan. Clipboard opens a compact history.
 * A single text pill beside Clipboard pastes the latest readable plain-text copy.
 */
final class ArcFan {
    interface Actions { default void beginDelete(){} default void endDelete(){} default boolean deletePending(){return false;} boolean deleteWord(); void openClipboard(); void prepareClipboardPaste(); void cancelClipboardPaste(); void pasteClipboard(String value); void openKeyboard(); void closed(); }
    static final float R_DP=76,MARGIN_DP=8,TILE_DP=48;
    static final int DWELL_MS=450,REDUCED_FADE_MS=150;
    // [Delete, Clipboard, Keyboard]. Checked so 48 dp tiles stay at least 8 dp apart.
    static final int[][] BASE={{265,205,160},{280,340,395},{155,90,40}};
    static final int RIGHT=0,LEFT=1,BOTTOM=2;

    private final Context ctx;private final WindowManager wm;private final Actions actions;private final Handler h=new Handler(Looper.getMainLooper());
    private final float density,R,M,T;
    private FrameLayout root;private final View[] tiles=new View[4];private final TextView[] labels=new TextView[4];private Ring ring;
    private TextView clipPill;private RectF clipPillBounds;private String clipboardValue,pressedClipboardValue;
    float cx,cy;int edge;int[] angles;int rotation;
    private int hover=-1;private boolean touchable,closing,wordDone,deleting,deleteCanceled;private long lastAct;
    private boolean recovered;
    private final Runnable dwell=new Runnable(){public void run(){if(hover==1&&root!=null){wordDone=true;if(!actions.deleteWord()){endDelete();return;}if(!actions.deletePending())Haptics.fire(ctx,Haptics.POP);if(ring!=null)ring.set(0);mark();h.postDelayed(this,REPEAT_MS);}}};

    ArcFan(Context ctx,WindowManager wm,Actions actions){this.ctx=ctx;this.wm=wm;this.actions=actions;density=ctx.getResources().getDisplayMetrics().density;R=R_DP*density;M=MARGIN_DP*density;T=TILE_DP*density;}
    boolean isOpen(){return root!=null&&!closing;}
    void clipboardPreview(String value){
        clipboardValue=ClipboardText.usable(value)?value:null;
        if(clipPill!=null&&!clipPill.isPressed()){clipPill.setText(clipboardValue==null?"":ClipboardText.preview(clipboardValue));clipPill.setContentDescription((recovered?"Open copied dictation: ":"Paste latest copy: ")+clipPill.getText());clipPill.setVisibility(clipboardValue!=null&&clipPillBounds!=null?View.VISIBLE:View.GONE);}
    }
    boolean isRecovery(){return recovered;}
    void openRecovered(float x,float y,String text){
        recovered=true;clipboardPreview(text);open(x,y,null,0);
        if(root==null)return;
        tiles[1].setVisibility(View.GONE);tiles[3].setVisibility(View.GONE);ring.setVisibility(View.GONE);
        labels[1].setVisibility(View.GONE);labels[3].setVisibility(View.GONE);
        tiles[2].setContentDescription("Open copied dictation");labels[2].setText("Copied");makeTouchable();
        h.postDelayed(this::close,12000);
    }
    Rect clipboardBounds(){
        if(!isOpen())return null;float[] p=pos(cx,cy,angles[1],R);
        return new Rect(Math.round(p[0]-T/2),Math.round(p[1]-T/2),Math.round(p[0]+T/2),Math.round(p[1]+T/2));
    }
    /** Stay beside Clipboard, within usable display bounds, and away from the other controls/caret. */
    private RectF choosePill(float W,float H,RectF caret){
        float[] p=pos(cx,cy,angles[1],R);int preferred=p[0]>W/2?-1:1;
        for(int width:new int[]{136,116,96})for(int side:new int[]{preferred,-preferred})for(int shift:new int[]{0,-12,-24,-36,-48,12,24}){
            float w=width*density,x=side<0?p[0]-T/2-8*density-w:p[0]+T/2+8*density;
            RectF pill=new RectF(x,p[1]-T/2+shift*density,x+w,p[1]+T/2+shift*density);
            if(pill.left<M||pill.top<M||pill.right>W-M||pill.bottom>H-M)continue;
            if(caret!=null&&RectF.intersects(pill,caret))continue;
            boolean collision=false;
            for(int i=0;i<4;i++){float[] q=i==0?new float[]{cx,cy}:pos(cx,cy,angles[i-1],R);RectF tile=new RectF(q[0]-T/2-6*density,q[1]-T/2-6*density,q[0]+T/2+6*density,q[1]+T/2+6*density);if(RectF.intersects(pill,tile)){collision=true;break;}}
            if(!collision)return pill;
        }
        return null;
    }

    static float[] pos(float cx,float cy,float a,float r){double t=Math.toRadians(a);return new float[]{(float)(cx+r*Math.cos(t)),(float)(cy-r*Math.sin(t))};}
    static int edgeOf(float W,float H,float cx,float cy,float kbH,float density){if(cy>H-kbH-130*density)return BOTTOM;return cx>W/2?RIGHT:LEFT;}
    /** Base angles by edge, then rotate ±5° until the tiles fit and miss the caret. */
    static int[] choose(float W,float H,float cx,float cy,int edge,RectF caret,float R,float T,float M){
        int[] base=BASE[edge];
        for(int k=0;k<=18;k++)for(int s:new int[]{1,-1}){int d=k*5*s;int[] as={base[0]+d,base[1]+d,base[2]+d};if(ok(W,H,cx,cy,as,caret,R,T,M))return new int[]{as[0],as[1],as[2],d};if(k==0)break;}
        return new int[]{base[0],base[1],base[2],0};
    }
    static boolean ok(float W,float H,float cx,float cy,int[] as,RectF caret,float R,float T,float M){
        float[][] P=new float[4][];P[0]=new float[]{cx,cy};
        for(int i=0;i<3;i++)P[i+1]=pos(cx,cy,as[i],R);
        for(int i=0;i<4;i++)for(int j=i+1;j<4;j++)if(Math.max(Math.abs(P[i][0]-P[j][0]),Math.abs(P[i][1]-P[j][1]))-T<M)return false;
        for(int i=1;i<4;i++){float l=P[i][0]-T/2,t=P[i][1]-T/2,r=l+T,b=t+T;if(l<M||t<M||r>W-M||b>H-M)return false;if(caret!=null&&RectF.intersects(new RectF(l-M,t-M,r+M,b+M),caret))return false;}
        return true;
    }

    void open(float centerX,float centerY,Rect caretRect,int keyboardHeight){
        if(root!=null)return;
        DisplayMetrics dm=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);float W=dm.widthPixels,H=dm.heightPixels;
        cx=centerX;cy=centerY;edge=edgeOf(W,H,cx,cy,keyboardHeight,density);
        RectF caret=caretRect==null?null:new RectF(caretRect);
        int[] c=choose(W,H-keyboardHeight,cx,cy,edge,caret,R,T,M);angles=new int[]{c[0],c[1],c[2]};rotation=c[3];
        root=new FrameLayout(ctx);root.setClipChildren(false);
        String[] kinds={"cancel","delete","clipboard","keyboard"},names={"Cancel","Delete word","Clipboard","Open keyboard"},short_={"Cancel","Delete","Clipboard","Keyboard"};
        ring=new Ring(ctx);root.addView(ring,new FrameLayout.LayoutParams(Math.round(60*density),Math.round(60*density)));
        for(int i=0;i<4;i++){
            FloatingIcon.Control tile=new FloatingIcon.Control(ctx,kinds[i],i==0?Color.WHITE:Daybook.actionInk(ctx),Math.round(T));
            android.graphics.drawable.GradientDrawable g=i==0?FlowAccessibility.coralFace(ctx,12*density,0xFF8F3424,0xFF7F2D1F):FlowAccessibility.solidFace(ctx,12*density);if(i==0)g.setStroke(Math.max(1,Math.round(density)),0x40FFFFFF);
            tile.setBackground(g);tile.setElevation(4*density);if(android.os.Build.VERSION.SDK_INT>=28){tile.setOutlineAmbientShadowColor(0xFF000000);tile.setOutlineSpotShadowColor(0xFF000000);}
            tile.setId(View.generateViewId());tile.setContentDescription(names[i]);tile.setFocusable(false);tile.setFocusableInTouchMode(false);tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            final int idx=i;tile.setOnClickListener(v->act(idx));
            tile.setOnTouchListener((v,e)->{if(!touchable)return false;int m=e.getActionMasked();
                if(m==MotionEvent.ACTION_DOWN){v.setPressed(true);if(idx==1){beginDelete();deleteCanceled=false;repeats=0;h.removeCallbacks(repeat);h.postDelayed(repeat,DWELL_MS);}return true;}
                boolean inside=e.getX()>=-8*density&&e.getY()>=-8*density&&e.getX()<=v.getWidth()+8*density&&e.getY()<=v.getHeight()+8*density;
                if(m==MotionEvent.ACTION_MOVE && idx==1 && !inside){deleteCanceled=true;h.removeCallbacks(repeat);endDelete();v.setPressed(false);return true;}
                if(m==MotionEvent.ACTION_UP){v.setPressed(false);if(idx==1){h.removeCallbacks(repeat);if(repeats==0&&inside&&!deleteCanceled)act(1);endDelete();}else if(inside)act(idx);return true;}
                if(m==MotionEvent.ACTION_CANCEL){v.setPressed(false);if(idx==1){h.removeCallbacks(repeat);endDelete();}return true;}return true;});
            tile.setOnFocusChangeListener((v,f)->labels[idx].setVisibility(f?View.VISIBLE:View.INVISIBLE));
            root.addView(tile,new FrameLayout.LayoutParams(Math.round(T),Math.round(T)));tiles[i]=tile;
            TextView label=Ui.text(ctx,short_[i],11,Daybook.ink(ctx),true);label.setBackground(Ui.shape(Daybook.surface(ctx),8,ctx));label.setPadding(Math.round(6*density),Math.round(2*density),Math.round(6*density),Math.round(2*density));label.setVisibility(View.INVISIBLE);label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(label,new FrameLayout.LayoutParams(-2,-2));labels[i]=label;
        }
        tiles[1].setAccessibilityTraversalAfter(tiles[0].getId());
        tiles[2].setAccessibilityTraversalAfter(tiles[1].getId());
        tiles[3].setAccessibilityTraversalAfter(tiles[2].getId());
        float pad=T/2+20*density,l=cx-pad,t=cy-pad,r=cx+pad,b=cy+pad;
        int dir=(int)Math.signum(angles[2]-angles[0]);if(dir==0)dir=1;
        for(int k=0;k<3;k++){float start=angles[k]-dir*55;
            for(int j=0;j<=12;j++){float a=start+(angles[k]-start)*j/12f;float[] p=pos(cx,cy,a,R*1.09f);l=Math.min(l,p[0]-pad);t=Math.min(t,p[1]-pad);r=Math.max(r,p[0]+pad);b=Math.max(b,p[1]+pad);}}
        clipPillBounds=choosePill(W,H-keyboardHeight,caret);
        if(clipPillBounds!=null){l=Math.min(l,clipPillBounds.left);t=Math.min(t,clipPillBounds.top);r=Math.max(r,clipPillBounds.right);b=Math.max(b,clipPillBounds.bottom);}
        clipPill=Ui.text(ctx,"",12,Daybook.actionInk(ctx),false);clipPill.setSingleLine(true);clipPill.setEllipsize(android.text.TextUtils.TruncateAt.END);clipPill.setGravity(Gravity.CENTER_VERTICAL);
        clipPill.setPadding(Math.round(12*density),0,Math.round(12*density),0);clipPill.setBackground(FlowAccessibility.solidFace(ctx,12*density));clipPill.setElevation(4*density);clipPill.setFocusable(false);
        clipPill.setOnClickListener(v->{if(!touchable)return;String exact=pressedClipboardValue!=null?pressedClipboardValue:clipboardValue;if(exact==null)return;Haptics.fire(ctx,Haptics.RELEASE);actions.pasteClipboard(exact);mark();});
        clipPill.setOnTouchListener((v,event)->{
            if(!touchable)return false;int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){pressedClipboardValue=clipboardValue;actions.prepareClipboardPaste();v.setPressed(true);return true;}
            boolean inside=event.getX()>=0&&event.getY()>=0&&event.getX()<=v.getWidth()&&event.getY()<=v.getHeight();
            if(action==MotionEvent.ACTION_MOVE){if(!inside)v.setPressed(false);return true;}
            if(action==MotionEvent.ACTION_UP){boolean click=v.isPressed()&&inside;v.setPressed(false);if(click)v.performClick();else actions.cancelClipboardPaste();pressedClipboardValue=null;clipboardPreview(clipboardValue);return true;}
            if(action==MotionEvent.ACTION_CANCEL){v.setPressed(false);actions.cancelClipboardPaste();pressedClipboardValue=null;clipboardPreview(clipboardValue);return true;}return true;
        });
        root.addView(clipPill,new FrameLayout.LayoutParams(clipPillBounds==null?0:Math.round(clipPillBounds.width()),Math.round(T)));clipboardPreview(clipboardValue);
        winX=Math.round(l);winY=Math.round(t);
        if(clipPillBounds!=null){clipPill.setX(clipPillBounds.left-winX);clipPill.setY(clipPillBounds.top-winY);}
        root.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE&&touchable){outsideAt=SystemClock.uptimeMillis();h.removeCallbacks(outsideCheck);h.postDelayed(outsideCheck,OUTSIDE_GRACE_MS);}return false;});
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(Math.round(r-l),Math.round(b-t),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.START;lp.x=winX;lp.y=winY;
        if(android.os.Build.VERSION.SDK_INT>=28)lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        try{wm.addView(root,lp);}catch(RuntimeException e){root=null;return;}
        placeSubs(false);
        if(!ValueAnimator.areAnimatorsEnabled())fade(true,null);
        else{animate(true,0);animate(true,1);animate(true,2);}
        mark();
    }
    private void mark(){lastAct=SystemClock.uptimeMillis();}
    private float offX,offY;
    private int winX,winY;
    static final int OUTSIDE_GRACE_MS=350;private long outsideAt,fieldTapAt;
    private final Runnable outsideCheck=new Runnable(){public void run(){if(fieldTapAt>=outsideAt-50)return;close();}};
    void fieldTapped(){fieldTapAt=SystemClock.uptimeMillis();}
    void outsideAction(){if(touchable)close();}
    private void offsets(){offX=winX;offY=winY;}
    private void setAt(int i,float u,boolean fade){
        View w=tiles[i];int aEnd=angles[i-1];int dir=(int)Math.signum(angles[2]-angles[0]);if(dir==0)dir=1;float start=aEnd-dir*55;
        float a,r,alpha,scale;
        if(fade){a=aEnd;r=R;alpha=u;scale=1;}
        else{float sp=(float)(1-Math.exp(-7*u)*Math.cos(9*u));float ea=(float)(1-Math.pow(1-Math.min(1,u),3));a=start+(aEnd-start)*ea;r=R*Math.max(0,sp);alpha=Math.min(1,u*4);scale=.55f+.45f*Math.min(1,u*1.6f);}
        float[] p=pos(cx,cy,a,r);w.setX(p[0]-offX-T/2);w.setY(p[1]-offY-T/2);w.setAlpha(alpha);if(i!=hover){w.setScaleX(scale);w.setScaleY(scale);}
        if(i==1){ring.setX(p[0]-offX-ring.getLayoutParams().width/2f);ring.setY(p[1]-offY-ring.getLayoutParams().height/2f);}
        if(i==2&&clipPill!=null)clipPill.setAlpha(alpha);
        placeLabel(i,p[0],p[1],aEnd);
    }
    private void placeLabel(int i,float x,float y,float a){TextView l=labels[i];l.measure(0,0);float[] q=pos(x,y,a,T/2+12*density);l.setX(q[0]-offX-l.getMeasuredWidth()/2f);l.setY(q[1]-offY-l.getMeasuredHeight()/2f);}
    private void placeSubs(boolean fade){offsets();View c=tiles[0];c.setX(cx-offX-T/2);c.setY(cy-offY-T/2);float la=edge==LEFT?0:edge==RIGHT?180:90;placeLabel(0,cx,cy,la);for(int i=1;i<=3;i++)setAt(i,prog[i-1],fade);}
    private final float[] prog={0,0,0};
    private void animate(boolean in,int which){
        ValueAnimator va=ValueAnimator.ofFloat(prog[which],in?1:0);va.setDuration(in?520:220);va.setStartDelay(in?which*60L:(2-which)*50L);
        va.addUpdateListener(an->{if(root==null)return;prog[which]=(float)an.getAnimatedValue();placeSubs(false);});
        if(!in&&which==0)va.addListener(new android.animation.AnimatorListenerAdapter(){@Override public void onAnimationEnd(android.animation.Animator a){remove();}});
        va.start();
    }
    private void fade(boolean in,Runnable done){
        final long t0=SystemClock.uptimeMillis();
        Runnable step=new Runnable(){public void run(){if(root==null)return;float u=Math.min(1,(SystemClock.uptimeMillis()-t0)/(float)REDUCED_FADE_MS);float v=in?u:1-u;for(int i=0;i<3;i++)prog[i]=v;placeSubs(true);tiles[0].setAlpha(in?1:v);if(u<1)h.postDelayed(this,16);else if(done!=null)done.run();}};
        h.post(step);
    }
    private int hit(float x,float y){
        float slack=4*density;
        for(int i=3;i>=0;i--){float tx,ty;if(i==0){tx=cx;ty=cy;}else{float[] p=pos(cx,cy,angles[i-1],R);tx=p[0];ty=p[1];}
            if(Math.abs(x-tx)<=T/2+slack&&Math.abs(y-ty)<=T/2+slack)return i;}
        return -1;
    }
    void track(float rawX,float rawY){
        if(!isOpen())return;int now=hit(rawX,rawY);if(now==hover)return;
        if(hover==1)endDelete();setHover(now);mark();
        h.removeCallbacks(dwell);if(ring!=null)ring.set(0);
        if(now==1){beginDelete();wordDone=false;if(ring!=null)ring.run(DWELL_MS);h.postDelayed(dwell,DWELL_MS);}
    }
    private void setHover(int now){
        if(hover>=0){tiles[hover].animate().scaleX(1).scaleY(1).setDuration(90).start();labels[hover].setVisibility(View.INVISIBLE);}
        hover=now;
        if(now>=0){tiles[now].animate().scaleX(1.12f).scaleY(1.12f).setDuration(90).start();labels[now].setVisibility(View.VISIBLE);if(now>0)Haptics.fire(ctx,Haptics.RELEASE);}
    }
    void release(float rawX,float rawY,boolean moved){
        if(!isOpen())return;h.removeCallbacks(dwell);if(ring!=null)ring.set(0);
        if(!moved){endDelete();setHover(-1);makeTouchable();mark();return;}
        int target=hit(rawX,rawY);setHover(-1);
        if(target==1){if(!wordDone)act(1);endDelete();makeTouchable();return;}
        endDelete();
        if(target>0)act(target);else if(target==0)close();else makeTouchable();
    }
    void cancelDeleteGesture(){h.removeCallbacks(dwell);h.removeCallbacks(repeat);endDelete();wordDone=true;deleteCanceled=true;if(ring!=null)ring.set(0);setHover(-1);makeTouchable();}
    private void makeTouchable(){if(root==null||touchable)return;touchable=true;try{WindowManager.LayoutParams lp=(WindowManager.LayoutParams)root.getLayoutParams();lp.flags&=~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;wm.updateViewLayout(root,lp);}catch(RuntimeException ignored){}}
    static final int REPEAT_MS=300;private int repeats;
    private final Runnable repeat=new Runnable(){public void run(){if(root==null||closing||deleteCanceled)return;repeats++;if(!act(1)){endDelete();return;}h.postDelayed(this,REPEAT_MS);}};
    private void beginDelete(){if(!deleting){actions.beginDelete();deleting=true;}}
    private void endDelete(){if(deleting){actions.endDelete();deleting=false;}}
    private boolean act(int i){
        if(i==1){boolean single=!deleting;if(single)beginDelete();boolean removed=actions.deleteWord();boolean pending=actions.deletePending();if(single)endDelete();if(!removed)return false;if(!pending)Haptics.fire(ctx,Haptics.RELEASE);mark();}
        else if(i==2){Haptics.fire(ctx,Haptics.RELEASE);actions.openClipboard();mark();}
        else if(i==3){Haptics.fire(ctx,Haptics.RELEASE);remove();h.post(actions::openKeyboard);}
        else close();
        return true;
    }
    void close(){
        if(root==null||closing)return;closing=true;endDelete();h.removeCallbacks(dwell);h.removeCallbacks(repeat);
        if(!ValueAnimator.areAnimatorsEnabled())fade(false,this::remove);
        else{animate(false,2);animate(false,1);animate(false,0);}
    }
    void dismissNow(){remove();}
    private void remove(){endDelete();actions.cancelClipboardPaste();h.removeCallbacksAndMessages(null);if(root!=null){try{wm.removeView(root);}catch(RuntimeException ignored){}root=null;clipPill=null;clipPillBounds=null;pressedClipboardValue=null;actions.closed();}}

    final class Ring extends View {
        float p;long t0;int dur;final Paint pt=new Paint(Paint.ANTI_ALIAS_FLAG);
        Ring(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        void set(float v){p=v;dur=0;invalidate();}
        void run(int ms){t0=SystemClock.uptimeMillis();dur=ms;p=0;invalidate();}
        @Override protected void onDraw(Canvas c){
            if(dur>0){p=Math.min(1,(SystemClock.uptimeMillis()-t0)/(float)dur);if(p<1)postInvalidateOnAnimation();}
            if(p<=0)return;float s=getWidth()/60f,in=1.5f*s;RectF r=new RectF(in,in,getWidth()-in,getHeight()-in);
            pt.setStyle(Paint.Style.STROKE);pt.setStrokeWidth(3*s);pt.setColor(0xFFD8C8B6);c.drawRoundRect(r,16*s,16*s,pt);
            pt.setColor(Daybook.action(getContext()));pt.setStrokeCap(Paint.Cap.ROUND);android.graphics.Path path=new android.graphics.Path();path.addRoundRect(r,16*s,16*s,android.graphics.Path.Direction.CW);
            android.graphics.PathMeasure m=new android.graphics.PathMeasure(path,false);android.graphics.Path seg=new android.graphics.Path();m.getSegment(0,m.getLength()*p,seg,true);c.drawPath(seg,pt);
        }
    }
}
