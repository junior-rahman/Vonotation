package app.voiceflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Native vector adaptation of the supplied Voitation logo and control sheet, v1. */
final class VoitationGlyph extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color; private String stage="Ready"; private float level;private boolean thought;
    VoitationGlyph(Context c){super(c);color=Daybook.brand(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    void setColor(int value){color=value;invalidate();}
    void setThought(boolean value){thought=value;invalidate();}
    void setActive(boolean value){stage=value?"Listening":"Ready";invalidate();}
    void setLevel(float value){level=Math.max(0,Math.min(1,value));invalidate();}
    void setStage(String value){stage=value;invalidate();}
    @Override protected void onDraw(Canvas canvas){float size=Math.min(getWidth(),getHeight());canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/48,size/48);draw(canvas,paint,color,isEnabled()?stage:"Disabled",level,thought);canvas.restore();}
    /** Approved option 01, Thought Loop: open Q, rounded tail and detached upper-right stroke. */
    static void thoughtLoop(Canvas c,Paint p){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(6.5f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        c.drawArc(9,9,37,37,9,279,false,p);c.drawLine(28,31,37,39,p);c.drawLine(35,12,38,16,p);
    }
    static void wave(Canvas c,Paint p,boolean keyboard){
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setStrokeCap(Paint.Cap.ROUND);
        for(int i=0;i<7;i++){float depth=3-Math.abs(3-i);float top=10+depth*4,bottom=22+depth*5;if(keyboard){top*=.8f;bottom*=.8f;}c.drawLine(8+i*5.33f,top,8+i*5.33f,bottom,p);}
        if(keyboard)c.drawLine(10,40,38,40,p);
    }
    static void draw(Canvas c,Paint p,int color,String stage,float level){
        draw(c,p,color,stage,level,false);
    }
    static void draw(Canvas c,Paint p,int color,String stage,float level,boolean thought){
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        String s=stage==null?"ready":stage.toLowerCase(java.util.Locale.ROOT);
        if(s.contains("disabled"))p.setAlpha(100);
        if(s.contains("upload")){c.drawLine(24,30,24,10,p);c.drawLine(16,18,24,10,p);c.drawLine(32,18,24,10,p);Path path=new Path();path.moveTo(11,31);path.lineTo(11,38);path.lineTo(37,38);path.lineTo(37,31);c.drawPath(path,p);}
        else if(s.contains("transcrib")||s.contains("polish")||s.contains("english letters")||s.contains("converting")){c.drawLine(10,18,29,18,p);c.drawLine(10,26,33,26,p);c.drawLine(10,34,26,34,p);if(s.contains("transcrib"))c.drawArc(6,6,42,42,-80,100,false,p);else{Path sparkle=new Path();sparkle.moveTo(37,4);sparkle.quadTo(38,10,43,11);sparkle.quadTo(38,12,37,18);sparkle.quadTo(36,12,31,11);sparkle.quadTo(36,10,37,4);c.drawPath(sparkle,p);}}
        else if(s.contains("saving")||s.contains("finish")){p.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)c.drawCircle(13+11*i,24,3,p);}
        else if(s.contains("done")||s.contains("saved")||s.contains("inserted")||s.contains("copied")){c.drawLine(11,25,20,34,p);c.drawLine(20,34,38,14,p);}
        else if(s.contains("retry")||s.contains("error")){c.drawArc(10,10,38,38,35,290,false,p);c.drawLine(36,9,36,19,p);c.drawLine(27,19,36,19,p);}
        else if(s.contains("blocked")){c.drawRoundRect(18,8,30,30,6,6,p);c.drawArc(12,15,36,36,0,180,false,p);c.drawLine(24,36,24,43,p);c.drawLine(17,43,31,43,p);c.drawLine(8,8,40,40,p);}
        else if(s.contains("listen")||s.contains("recording")){if(thought)thoughtLoop(c,p);else for(int i=0;i<7;i++){float h=(8+(3-Math.abs(i-3))*6)*(0.7f+level*.3f);c.drawLine(8+i*5.33f,24-h/2,8+i*5.33f,24+h/2,p);}p.setStyle(Paint.Style.FILL);c.drawCircle(42,5,2.5f,p);}
        else if(s.contains("cancel")){c.drawCircle(24,24,16,p);c.drawLine(17,17,31,31,p);c.drawLine(31,17,17,31,p);}
        else if(s.contains("working")||s.contains("inserting")){p.setStyle(Paint.Style.FILL);for(int i=0;i<3;i++)c.drawCircle(13+11*i,24,3,p);}
        else{if(thought)thoughtLoop(c,p);else wave(c,p,false);if(s.contains("start")){p.setStrokeWidth(2);c.drawArc(3,3,45,45,-80,105,false,p);}}
    }
}
