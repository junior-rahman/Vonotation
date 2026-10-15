package app.voiceflow;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks source words, numbers and meaning-bearing symbols, never a blacklist of
 * things the user is allowed to say. Formatting may only change case, ordinary
 * punctuation, spacing and paragraph breaks; anything else falls back to the
 * original transcript.
 */
final class TranscriptGuard {
    private static final Pattern WORD=Pattern.compile("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*");
    /** A number with any directly attached sign, currency and percent/permille marks. */
    private static final Pattern NUMBER=Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])([+\\-−–]?)([\\p{Sc}]?)([+\\-−–]?)(\\p{N}+(?:[.,:]\\p{N}+)*)(\\s?[%‰]?)");
    /** Symbols whose addition, removal or reordering changes meaning. */
    private static final String MEANINGFUL="%‰#@&+=<>*/\\^~|°±×÷";
    static List<String> words(String text) {
        List<String> words=new ArrayList<>();Matcher matcher=WORD.matcher(text);
        while(matcher.find())words.add(matcher.group());return words;
    }
    static boolean accepts(String source,String reference,String output) {
        return reason(source,reference,output)==null;
    }
    /** Null when accepted; otherwise a short internal reason used by tests and diagnostics. */
    static String reason(String source,String reference,String output) {
        if(source==null || reference==null || output==null)return "missing text";
        List<String> before=words(source),expected=words(reference),after=words(output);
        if(before.isEmpty())return "empty source";
        if(before.size()!=after.size() || expected.size()!=before.size())return "word count changed";
        boolean romanized=!source.equals(reference);
        for(int i=0;i<before.size();i++) {
            if(LanguagePolicy.needsRomanization(before.get(i)) && romanized) {
                if(LanguagePolicy.needsRomanization(after.get(i)))return "not romanized";
                if(!samePronunciation(expected.get(i),after.get(i)))return "pronunciation changed";
            } else if(!fold(before.get(i)).equals(fold(after.get(i))))return "word changed";
        }
        if(!numbers(romanized?reference:source).equals(numbers(output)))return "number, sign or currency changed";
        if(!symbols(romanized?reference:source).equals(symbols(output)))return "symbol changed";
        return null;
    }
    static List<String> numbers(String text) {
        List<String> found=new ArrayList<>();Matcher matcher=NUMBER.matcher(Normalizer.normalize(text,Normalizer.Form.NFKC));
        while(matcher.find()) {
            String sign=(matcher.group(1)+matcher.group(3)).replace('−','-').replace('–','-');
            found.add(sign+"|"+matcher.group(2)+"|"+digits(matcher.group(4))+"|"+matcher.group(5).trim());
        }
        return found;
    }
    static String symbols(String text) {
        StringBuilder out=new StringBuilder();String normal=Normalizer.normalize(text,Normalizer.Form.NFKC);
        for(int i=0;i<normal.length();) {
            int cp=normal.codePointAt(i);i+=Character.charCount(cp);
            if(cp=='−')continue; // A minus sign is checked with its number; hyphens and dashes are punctuation.
            if(MEANINGFUL.indexOf(cp)>=0 || Character.getType(cp)==Character.CURRENCY_SYMBOL || Character.getType(cp)==Character.MATH_SYMBOL)out.appendCodePoint(cp);
        }
        return out.toString();
    }
    private static String fold(String text) {return digits(Normalizer.normalize(text,Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replace('’','\''));}
    /** Native-script digits (৫, ५) compare equal to their ASCII value; the number itself must not change. */
    static String digits(String text) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<text.length();){int cp=text.codePointAt(i);i+=Character.charCount(cp);int d=Character.getType(cp)==Character.DECIMAL_DIGIT_NUMBER?Character.digit(cp,10):-1;if(d>=0)out.append((char)('0'+d));else out.appendCodePoint(cp);}
        return out.toString();
    }

    /**
     * Compares an AI romanization with the on-device pronunciation reference.
     * Consonants must match (allowing common aspiration and v/bh spellings) and every
     * vowel must stay in the same vowel class. Only a non-initial inherent "a/o" vowel
     * and a final nasal may be dropped. This is conservative and approximate; it is
     * not a semantic proof.
     */
    static boolean samePronunciation(String reference,String result) {
        if(!result.matches("[A-Za-z]+(?:['’][A-Za-z]+)*"))return false;
        String a=skeleton(reference),b=skeleton(result);
        if(a.isEmpty() || b.isEmpty())return fold(reference).equals(fold(result));
        return match(a,0,b,0);
    }
    /** Lowercase consonants; uppercase vowel classes A (a/o), E, I and U. */
    static String skeleton(String text) {
        String s=Normalizer.normalize(fold(text),Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("['’]","");
        s=s.replace("ee","i").replace("oo","u").replace("chh","c").replace("ch","c").replace("sh","s").replace("ph","f").replace("v","bh");
        s=s.replaceAll("([bcdgjkpt])h","$1");
        // Word-initial য is pronounced j (ICU writes y: যাব -> yaba, natural jabo).
        s=s.replaceAll("^y","j");
        // A consonant + y conjunct (ya-phala) is spoken as a doubled consonant: ধন্যবাদ -> dhonnobad.
        s=s.replaceAll("([bcdfgjklmnprstvz])y","$1");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);char mapped;
            switch(c){case 'a':case 'o':mapped='A';break;case 'e':mapped='E';break;case 'i':mapped='I';break;case 'u':mapped='U';break;default:mapped=c;}
            if(out.length()>0 && out.charAt(out.length()-1)==mapped)continue;
            out.append(mapped);
        }
        return out.toString();
    }
    private static boolean nasal(char c){return c=='n' || c=='m';}
    private static boolean match(String r,int i,String o,int j) {
        if(i==r.length() && j==o.length())return true;
        if(i<r.length() && j<o.length()) {
            char x=r.charAt(i),y=o.charAt(j);
            boolean finalNasals=nasal(x) && nasal(y) && i==r.length()-1 && j==o.length()-1;
            if((x==y || finalNasals) && match(r,i+1,o,j+1))return true;
        }
        // An inherent vowel is often unwritten in natural Banglish/Hinglish (korchi, kemon).
        if(i>0 && i<r.length() && r.charAt(i)=='A' && match(r,i+1,o,j))return true;
        // A final nasal mark (anusvara/chandrabindu) may be dropped or added.
        if(i==r.length()-1 && nasal(r.charAt(i)) && match(r,i+1,o,j))return true;
        if(j==o.length()-1 && nasal(o.charAt(j)) && i==r.length())return true;
        return false;
    }
    private TranscriptGuard() {}
}
