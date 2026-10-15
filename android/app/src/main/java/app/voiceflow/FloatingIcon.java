package app.voiceflow;

import android.content.Context;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** Small, resolution-independent icons for the floating dictation controls. */
final class FloatingIcon extends View {
    private final String kind;
    private final int color;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    FloatingIcon(Context context,String kind,int color){super(context);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas canvas){
        float size=Math.min(getWidth(),getHeight());canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/48f,size/48f);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.5f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        if(kind.equals("micKeyboardVisible") || kind.equals("micKeyboardHidden")){
            // Round5: one thin-bar waveform V; with the keyboard up the bars settle onto a key-row baseline (no badge).
            drawWaveV(canvas,kind.equals("micKeyboardVisible"));
        }else if(kind.equals("delete")){
            paint.setStrokeWidth(2f);android.graphics.Path p=new android.graphics.Path();p.moveTo(18,15);p.lineTo(35.5f,15);p.quadTo(38,15,38,17.5f);p.lineTo(38,30.5f);p.quadTo(38,33,35.5f,33);p.lineTo(18,33);p.lineTo(10,24);p.close();canvas.drawPath(p,paint);
            canvas.drawLine(23,20,31,28,paint);canvas.drawLine(31,20,23,28,paint);
        }else if(kind.equals("clipboard")){
            paint.setStrokeWidth(2f);canvas.drawRoundRect(16,14,34,36,3,3,paint);
            android.graphics.Path clip=new android.graphics.Path();clip.moveTo(20,14);clip.lineTo(20,12.5f);clip.quadTo(20,10,22.5f,10);clip.lineTo(27.5f,10);clip.quadTo(30,10,30,12.5f);clip.lineTo(30,14);canvas.drawPath(clip,paint);
            canvas.drawLine(21,22,29,22,paint);canvas.drawLine(21,27,27,27,paint);
        }else if(kind.equals("keyboard")){
            paint.setStrokeWidth(2f);canvas.drawRoundRect(10,15,38,33,3,3,paint);paint.setStyle(Paint.Style.FILL);
            for(float x:new float[]{14,19.5f,25,30.5f})canvas.drawRoundRect(x,19,x+2.6f,21.6f,.8f,.8f,paint);canvas.drawRoundRect(18,27,30,29.4f,1.2f,1.2f,paint);
        }else if(kind.equals("finish")){
            canvas.drawLine(12,25,21,34,paint);canvas.drawLine(21,34,37,15,paint);
        }else if(kind.equals("cancel")){
            paint.setStrokeWidth(2f);canvas.drawLine(16,16,32,32,paint);canvas.drawLine(32,16,16,32,paint);
        }else if(kind.equals("working")){
            paint.setStyle(Paint.Style.FILL);for(int x=12;x<=36;x+=12)canvas.drawCircle(x,24,2.5f,paint);
        }
        canvas.restore();
    }
    static final float[][] BARS={{12,22},{10,25},{15,28},{20,38},{16,30},{13,26},{10,21}};
    private void drawWaveV(Canvas canvas,boolean keyboard){
        paint.setStyle(Paint.Style.FILL);
        for(int i=0;i<BARS.length;i++){float x=10+i*4.267f,top=BARS[i][0],bottom=BARS[i][1];
            if(keyboard){top=10+(top-10)*22f/28f;bottom=10+(bottom-10)*22f/28f;}
            canvas.drawRoundRect(x,top,x+2.4f,bottom,1.2f,1.2f,paint);}
        if(keyboard)canvas.drawRoundRect(10,36,38,38,1,1,paint);
        paint.setStyle(Paint.Style.STROKE);
    }
    private void drawFlowMark(Canvas canvas){
        canvas.save();canvas.translate(1,3);canvas.scale(1.15f,1.15f);canvas.rotate(13,15,15);paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(2,10,6,21,2,2,paint);canvas.drawRoundRect(9,4,13,27,2,2,paint);canvas.drawRoundRect(16,6,20,24,2,2,paint);canvas.drawRoundRect(23,11,27,19,2,2,paint);
        canvas.rotate(-45,19,28);canvas.drawRoundRect(15,26,24,30,2,2,paint);canvas.restore();
    }
    /** Animate only the artwork, never the window, touch target, or saved anchor. */
    static final class Control extends FrameLayout {
        private final FloatingIcon icon;
        Control(Context context,String kind,int color,int iconSize){
            super(context);icon=new FloatingIcon(context,kind,color);addView(icon,new FrameLayout.LayoutParams(iconSize,iconSize,Gravity.CENTER));setFocusableInTouchMode(false);
        }
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        @Override public void setPressed(boolean pressed){
            super.setPressed(pressed);if(icon==null)return;icon.animate().cancel();
            if(!ValueAnimator.areAnimatorsEnabled()){resetArtwork();return;}
            float scale=pressed && isEnabled()?.91f:1f;
            icon.animate().scaleX(scale).scaleY(scale).alpha(1).setDuration(pressed?90:150).start();
        }
        void reveal(){
            resetArtwork();if(!ValueAnimator.areAnimatorsEnabled())return;
            icon.setScaleX(.88f);icon.setScaleY(.88f);icon.setAlpha(.65f);
            icon.animate().scaleX(1).scaleY(1).alpha(1).setDuration(160).start();
        }
        private void resetArtwork(){icon.animate().cancel();icon.setScaleX(1);icon.setScaleY(1);icon.setAlpha(1);icon.setTranslationX(0);icon.setTranslationY(0);}
        @Override protected void onDetachedFromWindow(){resetArtwork();super.onDetachedFromWindow();}
    }
}
