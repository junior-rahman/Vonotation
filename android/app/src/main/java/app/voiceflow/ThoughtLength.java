package app.voiceflow;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/** Two normal phone-width lines, independent of folding, screen density and display font scaling. */
final class ThoughtLength {
    static final int MIN_LINES=2;
    static final int WIDTH=320;
    static final float TEXT_SIZE=17f;
    static boolean minimumMet(String raw){
        try{return lineCount(raw)>=MIN_LINES;}
        catch(RuntimeException unavailable){return true;} // A measurement failure must never discard audio.
    }
    static int lineCount(String raw){
        String text=ThoughtLengthText.normalize(raw);if(text.isEmpty())return 0;
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));paint.setTextSize(TEXT_SIZE);
        return StaticLayout.Builder.obtain(text,0,text.length(),paint,WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build().getLineCount();
    }
    private ThoughtLength(){}
}
