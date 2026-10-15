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
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native Copper + Sand Daybook tokens; floating glass controls keep their own theme. */
final class Daybook {
    private static boolean dark(Context c) { return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private static int color(Context c, int light, int night) { return dark(c) ? night : light; }
    static int paper(Context c) { return color(c,0xfff1e9de,0xff211e1b); }
    static int surface(Context c) { return color(c,0xfffffaf3,0xff302923); }
    static int ink(Context c) { return color(c,0xff342820,0xfff6ede3); }
    static int muted(Context c) { return color(c,0xff726255,0xffc6b3a3); }
    static int accent(Context c) { return color(c,0xff8f5736,0xffe4b18a); }
    static int soft(Context c) { return color(c,0xffe9d8c5,0xff463628); }
    static int line(Context c) { return color(c,0xffd8c8b6,0xff544538); }
    static int coral(Context c) { return color(c,0xffb3422f,0xffffab97); }
    static int action(Context c) { return color(c,0xffbc4c36,0xffe78870); }
    static int actionInk(Context c) { return color(c,0xfffffaf5,0xff22150f); }
    static TextView text(Context c,String value,float size,int color,boolean bold) {
        TextView text=Ui.text(c,value,size,color,bold); text.setLineSpacing(Ui.dp(c,2),1); return text;
    }
    static GradientDrawable shape(Context c,int color,float radius) {
        GradientDrawable shape=Ui.shape(color,radius,c);
        if(Color.alpha(color)!=0) shape.setStroke(Ui.dp(c,0.5f),color(c,0x70ffffff,0x80625040));
        return shape;
    }
    static Drawable ripple(Context c,int color,float radius) {
        return new RippleDrawable(ColorStateList.valueOf(color(c,0x198f5736,0x26e4b18a)),shape(c,color,radius),Ui.shape(Color.WHITE,radius,c));
    }
    static LinearLayout card(Context c) {
        LinearLayout card=Ui.column(c);card.setPadding(Ui.dp(c,13),0,Ui.dp(c,13),0);card.setBackground(shape(c,surface(c),17));return card;
    }
    static Button button(Context c,String label,boolean primary,View.OnClickListener click) {
        Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(primary?actionInk(c):accent(c));b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setBackground(ripple(c,primary?action(c):surface(c),12));b.setMinimumHeight(Ui.dp(c,44));b.setMinHeight(Ui.dp(c,44));b.setPadding(Ui.dp(c,12),Ui.dp(c,8),Ui.dp(c,12),Ui.dp(c,8));b.setFocusableInTouchMode(false);b.setOnClickListener(v->{feedback(v);click.onClick(v);});return b;
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
        GradientDrawable shape=shape(c,surface(c),12);shape.setStroke(Ui.dp(c,1),line(c));field.setBackground(shape);field.setMinHeight(Ui.dp(c,46));
        field.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);return field;
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
            Bitmap tile=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(tile);Paint ink=new Paint();ink.setColor(color(c,0x05785127,0x05ead1ad)); // 1.9.15: quieter linen texture
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
        Mark(Context c) { super(c);paint.setColor(coral(c));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
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
            canvas.drawLine(3,6,9,6,paint);canvas.drawLine(15,6,21,6,paint);canvas.drawCircle(12,6,3,paint);
            canvas.drawLine(3,18,5,18,paint);canvas.drawLine(11,18,21,18,paint);canvas.drawCircle(8,18,3,paint);canvas.restore();
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
