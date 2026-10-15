package app.voiceflow;

import android.os.Build;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A local pronunciation reference for checking AI output, never a chat response. */
final class RomanizationReference {
    static String of(String source) throws IOException {
        if(!LanguagePolicy.needsRomanization(source))return source;
        if(Build.VERSION.SDK_INT<29)throw new IOException("Checked English-letter conversion needs Android 10 or later.");
        return convert(source);
    }
    @android.annotation.TargetApi(29)
    private static String convert(String source) {
        android.icu.text.Transliterator indic=android.icu.text.Transliterator.getInstance("Bengali-Latin; Devanagari-Latin");
        android.icu.text.Transliterator ascii=android.icu.text.Transliterator.getInstance("Latin-ASCII");
        Matcher matcher=Pattern.compile("[\\p{L}\\p{M}]+").matcher(source);StringBuffer out=new StringBuffer();
        while(matcher.find()) {
            String word=matcher.group();
            if(LanguagePolicy.needsRomanization(word))word=ascii.transliterate(indic.transliterate(word).replace('ṁ','n').replace('ṃ','n'));
            matcher.appendReplacement(out,Matcher.quoteReplacement(word));
        }
        matcher.appendTail(out);return out.toString();
    }
    private RomanizationReference() {}
}
