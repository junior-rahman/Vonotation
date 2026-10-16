package app.voiceflow;
public final class LanguagePolicyTest {
    static void check(boolean value){if(!value)throw new AssertionError("Language policy regression");}
    public static void main(String[] args){
        check(LanguagePolicy.needsRomanization("আমি ভালো আছি"));
        check(LanguagePolicy.needsRomanization("मैं ठीक हूँ"));
        check(LanguagePolicy.needsRomanization("म ठीक छु"));
        check(LanguagePolicy.needsRomanization("Tomorrow আমি Zara যাব 😀"));
        check(!LanguagePolicy.needsRomanization("ami bhalo achi 😀 123"));
        check(!LanguagePolicy.needsRomanization("Español café"));
        check(!LanguagePolicy.needsRomanization("১২৩ १२३"));
        check(LanguagePolicy.instructions(false,true,"").contains("Do not edit"));
        check(LanguagePolicy.instructions(false,true,"").contains("Never translate"));
        check(LanguagePolicy.instructions(true,true,"jewel=Juel").contains("already been applied"));
        check(LanguagePolicy.instructions(true,false,"").contains("Preserve profanity"));
        check(!LanguagePolicy.instructions(true,false,"").contains("Banglish"));
        check(!LanguagePolicy.wrongScript("auto","مرحبا كيف حالك اليوم"));
        check(!LanguagePolicy.wrongScript("auto","اردو ایک زبان ہے"));
        check(LanguagePolicy.wrongScript("bn","مرحبا كيف حالك اليوم"));
        System.out.println("PASS: 15 language-policy checks.");
    }
}
