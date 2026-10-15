package app.voiceflow;
import com.ibm.icu.text.Transliterator;
import java.util.regex.*;
/** Mirrors RomanizationReference with desktop ICU4J (Android uses android.icu, same ICU rules) to sanity-check the guard. */
public final class IcuBanglishCheck {
    static String ref(String source){
        Transliterator indic=Transliterator.getInstance("Bengali-Latin; Devanagari-Latin"),ascii=Transliterator.getInstance("Latin-ASCII");
        Matcher m=Pattern.compile("[\\p{L}\\p{M}]+").matcher(source);StringBuffer out=new StringBuffer();
        while(m.find()){String w=m.group();if(LanguagePolicy.needsRomanization(w))w=ascii.transliterate(indic.transliterate(w).replace('ṁ','n').replace('ṃ','n'));m.appendReplacement(out,Matcher.quoteReplacement(w));}
        m.appendTail(out);return out.toString();
    }
    public static void main(String[] a){
        String[][] cases={
            {"আমি ভালো আছি","ami bhalo achi","1"},{"আমি ভালো আছি","ami valo achi","1"},{"আমি ভালো আছি","umi bhilu achi","0"},
            {"তুমি কেমন আছো","tumi kemon acho","1"},{"তুমি কেমন আছো","tami kemon acho","0"},
            {"আমি আজ বাড়ি যাব","ami aj bari jabo","1"},{"আমি আজ বাড়ি যাব","ami aj buri jabo","0"},
            {"আমি কাজ করছি","ami kaj korchi","1"},{"ধন্যবাদ","dhonnobad","1"},
            {"मैं ठीक हूँ","main theek hoon","1"},{"म ठीक छु","ma thik chhu","1"},{"म ठीक छु","mi thik chhu","0"},
        };
        int ok=0,n=0;
        for(String[] c:cases){n++;String r=ref(c[0]);boolean got=TranscriptGuard.accepts(c[0],r,c[1]);boolean want=c[2].equals("1");System.out.println((got==want?"ok   ":"DIFF ")+c[0]+" | ICU ref: "+r+" | output: "+c[1]+" | expected "+(want?"accept":"reject")+", got "+(got?"accept":"reject"));if(got==want)ok++;}
        System.out.println(ok+"/"+n+" ICU4J-reference Banglish/Hinglish checks matched expectation (informational).");
    }
}
