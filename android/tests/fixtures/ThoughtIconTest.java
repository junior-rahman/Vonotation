package app.voiceflow;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Native bitmap checks for the source-drawn thought mark; no microphone or overlay is opened. */
public final class ThoughtIconTest extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle report=new Bundle();AtomicReference<Throwable> failure=new AtomicReference<>();
        runOnMainSync(()->{try{verify();}catch(Throwable problem){failure.set(problem);}});
        report.putInt("executed_checks",checks);
        if(failure.get()!=null)report.putString("failure",android.util.Log.getStackTraceString(failure.get()));
        else report.putString("result","PASS: actual native thought glyph differs from dictation and clipboard; the 12dp idle mark retains transparent margins and readable ink; expanded control keeps its 48dp hit area. These are component render checks, not a physical vibration or browser focus test.");
        finish(failure.get()==null?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
    }
    private void verify(){
        Context context=getTargetContext();int[] note=icon(context,"thought",144),dictation=icon(context,"micKeyboardHidden",144),keyboard=icon(context,"micKeyboardVisible",144),clipboard=icon(context,"clipboard",144);
        require(!Arrays.equals(note,dictation)&&!Arrays.equals(note,keyboard),"Thought and dictation artwork must be visibly distinct");
        require(!Arrays.equals(note,clipboard),"Thought artwork must retain its own folded-page silhouette");
        require(!Arrays.equals(dictation,keyboard),"Established dictation keyboard states must remain distinct");
        int size=Ui.dp(context,12);int[] compact=icon(context,"thoughtDot",size);int ink=0,left=size,top=size,right=-1,bottom=-1;
        for(int y=0;y<size;y++)for(int x=0;x<size;x++)if(Color.alpha(compact[y*size+x])>32){ink++;left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
        require(ink>=Math.max(8,size*size/6),"The idle note mark must retain visible ink at its actual size");
        require(right-left+1>=size/2 && bottom-top+1>=size*2/3,"The small note silhouette must remain recognizable");
        require(Color.alpha(compact[0])==0 && Color.alpha(compact[size-1])==0,"Native artwork must preserve transparent corners");
        int target=Ui.dp(context,48),artwork=Ui.dp(context,30);FloatingIcon.Control control=new FloatingIcon.Control(context,"thought",Color.WHITE,artwork);
        control.measure(View.MeasureSpec.makeMeasureSpec(target,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(target,View.MeasureSpec.EXACTLY));control.layout(0,0,target,target);
        require(control.getWidth()==target&&control.getHeight()==target,"Thought control changed the 48dp hit target");
        require(control.getChildCount()==1&&control.getChildAt(0).getWidth()==artwork&&control.getChildAt(0).getHeight()==artwork,"Expanded artwork changed its existing size");
        require(android.widget.Button.class.getName().contentEquals(control.getAccessibilityClassName()),"Thought control must expose a button role");
    }
    private int[] icon(Context context,String kind,int size){
        FloatingIcon icon=new FloatingIcon(context,kind,Color.WHITE);icon.layout(0,0,size,size);Bitmap bitmap=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);icon.draw(new Canvas(bitmap));int[] pixels=new int[size*size];bitmap.getPixels(pixels,0,size,0,0,size,size);bitmap.recycle();return pixels;
    }
    private void require(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
