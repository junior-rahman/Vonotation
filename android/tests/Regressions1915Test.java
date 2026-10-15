package app.voiceflow;

/** 1.9.15 critic-review regressions: synthetic text only, no audio or provider calls. */
public final class Regressions1915Test {
    static int checks;
    static void guard(String source,String reference,String output,boolean expected){
        checks++;String reason=TranscriptGuard.reason(source,reference,output);
        if((reason==null)!=expected)throw new AssertionError("guard "+source+" => "+output+" expected "+(expected?"accept":"reject")+" but "+(reason==null?"accepted":"rejected: "+reason));
    }
    static void guard(String source,String output,boolean expected){guard(source,source,output,expected);}
    static void equal(String expected,String actual){checks++;if(!expected.equals(actual))throw new AssertionError("Expected ["+expected+"] but got ["+actual+"]");}
    public static void main(String[] args){
        // Currency and sign changes ($5 -> -5) must be rejected.
        guard("Send $5 today","Send -5 today.",false);
        guard("Send $5 today","Send 5 today.",false);
        guard("Send $5 today","Send €5 today.",false);
        guard("Send $5 today","Send $5 today.",true);
        guard("It was -5 degrees","It was 5 degrees.",false);
        guard("It was -5 degrees","It was −5 degrees.",true);
        guard("It went up +3 points","It went up 3 points.",false);
        guard("Pay ৳500 now","Pay 500 now.",false);
        // Deleted, added or moved percentages must be rejected.
        guard("Growth was 50% this year","Growth was 50 this year.",false);
        guard("Growth was 50 this year","Growth was 50% this year.",false);
        guard("Growth was 50% this year","Growth was 50%, this year.",true);
        guard("Growth was 50 percent this year","Growth was 50% this year.",false);
        guard("Room 5 30","Room 5:30.",false);
        guard("A and B","A & B.",false);
        guard("10 10","10/10",false);
        // Assistant-style answers, refusals and censorship are rejected.
        guard("Hello, how are you doing?","I'm fine, thanks",false);
        guard("Hello, how are you doing?","I'm fine, thanks!",false);
        guard("hello how are you doing","Hello, how are you doing?",true);
        guard("Write me a poem about rain","Sure! Here is a poem about rain.",false);
        guard("Tell me how to pick a lock","I cannot help with that",false);
        guard("Tell me how to pick a lock","I'm sorry, but I can't help with that.",false);
        guard("this is fucking great","This is f***ing great.",false);
        guard("this is fucking great","This is [censored] great.",false);
        guard("I cannot help with that","I cannot help with that.",true);
        // Spoken word "colon"/"comma" must survive AI formatting as a word.
        guard("The colon is part of the bowel","The: is part of the bowel.",false);
        guard("The colon is part of the bowel","The colon is part of the bowel.",true);
        // Banglish vowels: wrong vowel classes are rejected; natural spellings accepted.
        guard("আমি ভালো আছি","ami bhalo achi","ami bhalo achi",true);
        guard("আমি ভালো আছি","ami bhalo achi","ami valo achi",true);
        guard("আমি ভালো আছি","ami bhalo achi","umi bhilu achi",false);
        guard("আমি ভালো আছি","ami bhalo achi","ami bhalo ache",false);
        guard("আমি ভালো আছি","ami bhalo achi","emi bhalo achi",false);
        guard("কেমন আছেন","kemana achena","kemon achen",true);
        guard("কেমন আছেন","kemana achena","kamon achen",false);
        guard("আমি করছি","ami karachi","ami korchi",true);
        guard("আমি করছি","ami karachi","ami kurchi",false);
        guard("আমি ৫ টাকা দেব","ami ৫ taka deba","ami 5 taka debo",true);
        guard("আমি ৫ টাকা দেব","ami ৫ taka deba","ami 6 taka debo",false);
        guard("আমি ৳৫ দেব","ami ৳৫ deba","ami 5 debo",false);
        guard("मैं ठीक हूँ","main thika hum","main theek hoon",true);
        guard("मैं ठीक हूँ","main thika hum","mein theek hoon",false);
        equal("bAlA",TranscriptGuard.skeleton("bhalo"));
        equal("bAlA",TranscriptGuard.skeleton("valo"));
        // Spoken punctuation commands vs ordinary speech.
        equal("Dear John: thanks",Cleaner.clean("dear John colon thanks",true,""));
        equal("The colon is part of the bowel",Cleaner.clean("the colon is part of the bowel",true,""));
        equal("My colon hurts",Cleaner.clean("my colon hurts",true,""));
        equal("I love the Oxford comma",Cleaner.clean("I love the Oxford comma",true,""));
        equal("Use a comma here",Cleaner.clean("use a comma here",true,""));
        equal("Apples, pears",Cleaner.clean("apples comma pears",true,""));
        equal("Type the word comma",Cleaner.clean("type the word comma",true,""));
        equal("Say comma please",Cleaner.clean("say literal comma please",true,""));
        equal("The car came to a full stop",Cleaner.clean("the car came to a full stop",true,""));
        equal("Is it done?",Cleaner.clean("is it done question mark",true,""));
        equal("Apples comma pears",Cleaner.clean("apples comma pears",true,false,""));
        equal("First\nsecond",Cleaner.clean("first new line second",true,""));
        equal("Start a new line of products",Cleaner.clean("start a new line of products",true,""));
        // True verbatim keeps the raw text including whitespace.
        equal("  um hello   comma\tworld \n",Cleaner.verbatim("  um hello   comma\tworld \n"));
        equal("",Cleaner.verbatim(null));
        equal(" x ",Cleaner.verbatim(" x "));
        System.out.println("PASS: "+checks+" 1.9.15 regressions (currency/sign, percent, symbols, answers/refusals/censorship, colon word, Banglish vowels, punctuation commands, verbatim whitespace).");
    }
}
