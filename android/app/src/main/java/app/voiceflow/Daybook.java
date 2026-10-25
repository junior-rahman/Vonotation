package app.voiceflow;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared native roles: Sand & Cocoa for Voitation, original Daybook for Vocading. */
final class Daybook {
    private static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private static int color(Context c, int light, int night) { return dark(c) ? night : light; }
    private static int palette(Context c,int light,int night,int legacyLight,int legacyNight) { return color(c,AppProduct.isNotes()?legacyLight:light,AppProduct.isNotes()?legacyNight:night); }
    static int paper(Context c) { return palette(c,0xffeae3d9,0xff211b17,0xfff3f1ea,0xff121c19); }
    static int surface(Context c) { return palette(c,0xfffff9ef,0xff30261f,0xfffffcf6,0xff1b2923); }
    static int ink(Context c) { return palette(c,0xff372d25,0xfffbf3e8,0xff25352f,0xffeef3e9); }
    static int muted(Context c) { return palette(c,0xff6b5b4a,0xffd1bea7,0xff596960,0xffb2c2b5); }
    static int accent(Context c) { return palette(c,0xff6b4f3c,0xffe5c8a1,0xff3f6b5f,0xffaccebb); }
    static int soft(Context c) { return palette(c,0xffe9ded0,0xff45372b,0xffe3e9df,0xff2a3d32); }
    static int line(Context c) { return palette(c,0xff9a8468,0xffad9170,0xffcbd5ca,0xff3f5748); }
    static int controlOutline(Context c) { return palette(c,0xff9a8468,0xffad9170,0xff7b8e80,0xff6e8c77); }
    static int action(Context c) { return palette(c,0xff6b4f3c,0xffe5c8a1,0xff28594e,0xffaccebb); }
    static int actionInk(Context c) { return palette(c,0xffffffff,0xff211b17,0xffffffff,0xff15271e); }
    static int brand(Context c) { return palette(c,0xff6b4f3c,0xffe5c8a1,0xffa46144,0xffd9ab8d); }
    static int coral(Context c) { return color(c,0xffae403d,0xffffb4aa); }
    static int cardSurface(Context c,int depth) {
        if(AppProduct.isNotes() || depth<=0)return surface(c);
        if(depth==1)return color(c,0xffe5d4bb,0xff4a392c);
        if(depth==2)return color(c,0xffc7b291,0xff634d39);
        return color(c,0xffa58e6c,0xff81654d);
    }
    static int mix(int first,int second,float fraction) {
        return Color.rgb(Math.round(Color.red(first)*(1-fraction)+Color.red(second)*fraction),Math.round(Color.green(first)*(1-fraction)+Color.green(second)*fraction),Math.round(Color.blue(first)*(1-fraction)+Color.blue(second)*fraction));
    }
    static int opacity(int value,int alpha) { return (value & 0x00ffffff) | (alpha<<24); }
    static TextView text(Context c,String value,float size,int color,boolean bold) {
        TextView text=Ui.text(c,value,size,color,bold); text.setLineSpacing(Ui.dp(c,2),1); return text;
    }
    static GradientDrawable shape(Context c,int color,float radius) {
        GradientDrawable shape=Ui.shape(color,radius,c);
        // Retain the existing edge opacity; opaque edges also change Android's elevation shadow.
        if(Color.alpha(color)!=0) shape.setStroke(Ui.dp(c,0.5f),opacity(line(c),dark(c)?0x80:0x70));
        return shape;
    }
    static Drawable ripple(Context c,int color,float radius) {
        return new RippleDrawable(ColorStateList.valueOf(opacity(accent(c),dark(c)?0x26:0x19)),shape(c,color,radius),Ui.shape(Color.WHITE,radius,c));
    }
    static LinearLayout card(Context c) {
        int inset=AppProduct.isNotes()?13:16;
        LinearLayout card=Ui.column(c);card.setPadding(Ui.dp(c,inset),0,Ui.dp(c,inset),0);card.setBackground(shape(c,surface(c),AppProduct.isNotes()?17:20));return card;
    }
    static Button button(Context c,String label,boolean primary,View.OnClickListener click) {
        Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?actionInk(c):accent(c));b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setBackground(ripple(c,primary?action(c):surface(c),12));b.setMinimumHeight(Ui.dp(c,44));b.setMinHeight(Ui.dp(c,44));b.setPadding(Ui.dp(c,12),Ui.dp(c,8),Ui.dp(c,12),Ui.dp(c,8));b.setFocusableInTouchMode(false);b.setOnClickListener(v->{feedback(v);click.onClick(v);});return b;
    }
    /** Quiet, accessible actions for the Voitation toolbar. */
    static View iconButton(Context c,String symbol,String label,View.OnClickListener click) {
        Symbol view=new Symbol(c,symbol);view.setContentDescription(label);view.setTooltipText(label);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setBackground(ripple(c,Color.TRANSPARENT,14));view.setFocusable(true);
        view.setOnClickListener(v->{feedback(v);click.onClick(v);});return view;
    }
    static final class Symbol extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final String symbol;
        Symbol(Context c,String symbol){super(c);this.symbol=symbol;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
        @Override protected void onDraw(Canvas canvas){
            float size=Ui.dp(getContext(),22);canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);
            paint.setColor(accent(getContext()));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.7f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            if(symbol.equals("back")){canvas.drawLine(15,5,8,12,paint);canvas.drawLine(8,12,15,19,paint);}
            else if(symbol.equals("close")){canvas.drawLine(7,7,17,17,paint);canvas.drawLine(17,7,7,17,paint);}
            else if(symbol.equals("restore")){canvas.drawArc(5,5,21,21,-145,290,false,paint);canvas.drawLine(5,4,5,10,paint);canvas.drawLine(5,10,11,10,paint);canvas.drawLine(13,9,13,13,paint);canvas.drawLine(13,13,16,15,paint);}
            else{canvas.drawCircle(10.5f,10.5f,6.5f,paint);canvas.drawLine(15.5f,15.5f,20,20,paint);}
            canvas.restore();
        }
    }
    /** One round action, meant to float over a scrolling card. */
    static void pill(Button b,boolean primary) {
        Context c=b.getContext();
        b.setBackground(ripple(c,primary?action(c):surface(c),26));
        b.setMinimumWidth(Ui.dp(c,156));b.setMinimumHeight(Ui.dp(c,52));b.setMinHeight(Ui.dp(c,52));
        b.setPadding(Ui.dp(c,28),0,Ui.dp(c,28),0);b.setElevation(Ui.dp(c,8));
    }
    static EditText field(Context c,String value,String hint,boolean secret) {
        EditText field=new EditText(c);field.setText(value);field.setHint(hint);field.setTextSize(16);field.setTextColor(ink(c));field.setHintTextColor(muted(c));field.setPadding(Ui.dp(c,12),Ui.dp(c,11),Ui.dp(c,12),Ui.dp(c,11));
        field.setBackground(fieldSurface(c));field.setMinHeight(Ui.dp(c,46));
        field.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return field;
    }
    static Drawable fieldSurface(Context c) {
        GradientDrawable focused=shape(c,surface(c),12);focused.setStroke(Ui.dp(c,1),accent(c));
        GradientDrawable resting=shape(c,surface(c),12);resting.setStroke(Ui.dp(c,1),controlOutline(c));
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{android.R.attr.state_focused},focused);states.addState(new int[]{},resting);return states;
    }
    static void feedback(View view) { boolean enabled=Store.bool(view.getContext(),"haptic_feedback",true);view.setHapticFeedbackEnabled(enabled);if(enabled){view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);Haptics.fire(view.getContext(),Haptics.RELEASE);} }
    static void motion(View view) {
        view.animate().cancel();view.setAlpha(1);view.setTranslationY(0);
        if(!ValueAnimator.areAnimatorsEnabled())return;
        view.setAlpha(.8f);view.setTranslationY(Ui.dp(view.getContext(),4));view.animate().alpha(1).translationY(0).setDuration(160).start();
    }
    /** Tiny repeated tile and shader are built once; draw() only paints existing state. */
    static final class Backdrop extends Drawable {
        private final Paint base=new Paint(),fiber=new Paint();
        Backdrop(Context c) {
            base.setColor(paper(c));int w=Math.max(4,Ui.dp(c,4)),h=Math.max(3,Ui.dp(c,3));
            Bitmap tile=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(tile);Paint ink=new Paint();ink.setColor(opacity(accent(c),0x03)); // Subtle linen, below 1.2% opacity.
            canvas.drawRect(0,Math.max(0,h-Ui.dp(c,1)),w,h,ink);canvas.drawRect(Math.max(0,w-Ui.dp(c,1)),0,w,h,ink);
            fiber.setShader(new BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT));
        }
        @Override public void draw(Canvas canvas) { canvas.drawRect(getBounds(),base);canvas.drawRect(getBounds(),fiber); }
        @Override public void setAlpha(int alpha) { base.setAlpha(alpha);fiber.setAlpha(alpha);invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { base.setColorFilter(filter);fiber.setColorFilter(filter);invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }
    static final class Mark extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Mark(Context c) { super(c);paint.setColor(brand(c));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            if(!AppProduct.isNotes()){float unit=Math.min(getWidth(),getHeight())/48f;canvas.save();canvas.translate((getWidth()-48*unit)/2,(getHeight()-48*unit)/2);canvas.scale(unit,unit);VoitationGlyph.wave(canvas,paint,false);canvas.restore();return;}
            float unit=Math.min(getWidth()/30f,getHeight()/31f);canvas.save();canvas.translate((getWidth()-30*unit)/2,(getHeight()-31*unit)/2);canvas.scale(unit,unit);canvas.rotate(13,15,15);
            canvas.drawRoundRect(2,10,6,21,2,2,paint);canvas.drawRoundRect(9,4,13,27,2,2,paint);canvas.drawRoundRect(16,6,20,24,2,2,paint);canvas.drawRoundRect(23,11,27,19,2,2,paint);
            canvas.rotate(-45,19,28);canvas.drawRoundRect(15,26,24,30,2,2,paint);canvas.restore();
        }
    }
    static final class SettingsIcon extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        SettingsIcon(Context c) { super(c);paint.setColor(accent(c));paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas canvas) {
            float size=Ui.dp(getContext(),19),left=(getWidth()-size)/2,top=(getHeight()-size)/2,unit=size/24;
            canvas.save();canvas.translate(left,top);canvas.scale(unit,unit);paint.setStrokeWidth(1.8f);
            canvas.drawLine(3,5,7,5,paint);canvas.drawLine(13,5,21,5,paint);canvas.drawCircle(10,5,2.5f,paint);
            canvas.drawLine(3,12,12,12,paint);canvas.drawLine(18,12,21,12,paint);canvas.drawCircle(15,12,2.5f,paint);
            canvas.drawLine(3,19,5,19,paint);canvas.drawLine(11,19,21,19,paint);canvas.drawCircle(8,19,2.5f,paint);canvas.restore();
        }
    }
    static final class Rail extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final int dotColor,lineColor,paper;private final float density;
        Rail(Context c,boolean first) { super(c);density=c.getResources().getDisplayMetrics().density;dotColor=first?action(c):accent(c);lineColor=line(c);paper=paper(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float x=getWidth()/2f,y=18*density;paint.setColor(lineColor);paint.setStrokeWidth(Math.max(1,density));canvas.drawLine(x,0,x,getHeight(),paint);paint.setColor(paper);canvas.drawCircle(x,y,6.5f*density,paint);paint.setColor(dotColor);canvas.drawCircle(x,y,3.5f*density,paint);
        }
    }
    private Daybook() { }
}
