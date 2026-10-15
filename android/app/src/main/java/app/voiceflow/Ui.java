package app.voiceflow;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(236,238,236), INK = Color.rgb(37,40,43), MUTED = Color.rgb(102,107,112), ACCENT = Color.rgb(169,178,183), LINE = Color.rgb(204,208,209), WHITE = Color.WHITE;
    static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color, float radius, Context c) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d; }
    static android.graphics.drawable.Drawable glass(Context c,float radius,int tone){return Glass.surface(c,radius,tone);}
    static LinearLayout column(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout row(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static TextView text(Context c, String value, float size, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); t.setLineSpacing(dp(c,3),1); return t;
    }
    static void space(LinearLayout l, int height) { View v = new View(l.getContext()); l.addView(v, new LinearLayout.LayoutParams(1,dp(l.getContext(),height))); }
    static Button button(Context c, String label, boolean primary, View.OnClickListener onClick) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(primary ? WHITE : INK); b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x30ffffff:0x1825282b),glass(c,20,primary?Glass.ACTIVE:Glass.LIGHT),shape(WHITE,20,c)));b.setElevation(dp(c,1));
        b.setMinHeight(dp(c,52)); b.setMinimumHeight(dp(c,52)); b.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,10)); b.setOnClickListener(onClick); return b;
    }
    static LinearLayout card(Context c) {
        LinearLayout l = column(c); l.setPadding(dp(c,20),dp(c,20),dp(c,20),dp(c,20)); l.setBackground(glass(c,24,Glass.LIGHT));l.setElevation(dp(c,2)); return l;
    }
    static void addCard(LinearLayout parent, View card) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(parent.getContext(),16); parent.addView(card,p);
    }
    static void copy(Context c, String text) { ClipList.remember(c,text); ((android.content.ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("VonoTation dictation",text)); android.widget.Toast.makeText(c,"Copied. Paste it wherever you write.",android.widget.Toast.LENGTH_SHORT).show(); }
    static String languageName(Context c) {
        return "English";
    }
    static final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean active;
        private float level;
        private int color = ACCENT;
        Wave(Context c) { super(c); setContentDescription("Voice waveform"); }
        void setActive(boolean active) { this.active=active; invalidate(); }
        void setLevel(float level) { this.level=Math.min(1,Math.max(0,level)); invalidate(); }
        void setColor(int color) { this.color=color; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); paint.setColor(color); int bars=29; float gap=getWidth()/(float)bars, w=Math.max(3,gap*.34f);
            double time = System.currentTimeMillis()/170.0;
            for (int i=0;i<bars;i++) {
                float envelope = (float)Math.sin(Math.PI*(i+1)/(bars+1));
                float variation = active ? (float)(.35+.65*Math.abs(Math.sin(time+i*.8))) : (float)(.25+.65*Math.abs(Math.sin(i*1.7)));
                float height = dp(getContext(),5)+(getHeight()*.78f)*envelope*variation*(active ? .3f+.7f*level : .7f);
                float x=gap*i+(gap-w)/2; canvas.drawRoundRect(x,(getHeight()-height)/2,x+w,(getHeight()+height)/2,w/2,w/2,paint);
            }
            if (active && isAttachedToWindow()) postInvalidateDelayed(50);
        }
    }
}
