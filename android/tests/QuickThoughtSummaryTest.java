package app.voiceflow;

public final class QuickThoughtSummaryTest {
    static int checks;
    static void check(boolean valid,String reason){checks++;if(!valid)throw new AssertionError(reason);}
    static void equal(String expected,String actual){check(expected.equals(actual),"Expected ["+expected+"] got ["+actual+"]");}
    static String quote(String value){return QuickThoughtOrganizationTest.quote(value);}
    static String json(String title,String body,String summary){return "{\"title\":"+quote(title)+",\"body\":"+quote(body)+",\"summary\":"+quote(summary)+"}";}
    static void unsafe(String raw,String summary){check(!QuickThoughtPolicy.summaryAccepted(raw,summary),"Unsafe summary accepted: "+summary);}
    public static void main(String[] args){
        String raw="Maybe call Juel tomorrow. Buy 5 apples. Meeting at 5:30 costs $5.50.";
        check(QuickThoughtPolicy.summaryAccepted(raw,raw),"short notes retain every complete sentence");
        check(QuickThoughtPolicy.summaryAccepted(raw,raw.toUpperCase()),"case and sentence punctuation may change");
        unsafe(raw,"Call Juel tomorrow.");unsafe(raw,"Meeting at 5:30 costs $5.50. Maybe call Juel tomorrow.");
        unsafe(raw,"Buy 3 apples.");unsafe(raw,"Meeting at 6:30 costs $5.50.");unsafe(raw,"Meeting at 5:30 costs 5.50.");
        unsafe(raw,"Maybe call Juel.");unsafe(raw,"Buy 5 apples. Buy 5 apples.");unsafe(raw,"Approved.");
        unsafe("Do not call Juel tomorrow. Buy milk.","Call Juel tomorrow.");
        unsafe("I do not want a reminder to buy milk.","Do not buy milk.");
        unsafe("Buy 5 apples and sell 3 oranges.","Buy 3 oranges.");
        unsafe("I haven't decided whether to buy a car.","Buy a car.");
        unsafe("Maybe call Juel then buy milk","Maybe call Juel");
        unsafe("Is my visa approved? Check tomorrow.","Is my visa approved.");
        check(QuickThoughtPolicy.summaryAccepted("Is my visa approved? Check tomorrow.","Is my visa approved? Check tomorrow."),"whole question remains a question");
        unsafe("Is my visa approved？ Check tomorrow.","Is my visa approved.");
        check(QuickThoughtPolicy.summaryAccepted("Is my visa approved？ Check tomorrow.","Is my visa approved? Check tomorrow."),"Unicode question marker may normalize without becoming an assertion");
        unsafe("هل تمت الموافقة؟ تحقق غدًا.","هل تمت الموافقة.");
        String signs="Pay −5 dollars. Keep $5.50 and 5%. Temperature is - 5 degrees.";
        check(QuickThoughtPolicy.summaryAccepted(signs,signs.replace('−','-')),"equivalent Unicode minus retained");
        unsafe(signs,"Pay 5 dollars.");unsafe(signs,"Temperature is 5 degrees.");unsafe(signs,"Keep $5.50 and 5.");
        check(QuickThoughtPolicy.summaryAccepted("আগামীকাল দুধ কিনব। তারপর জুয়েলকে ফোন করব।","আগামীকাল দুধ কিনব। তারপর জুয়েলকে ফোন করব।"),"Bengali complete sentence excerpt");
        unsafe("আগামীকাল দুধ কিনব। তারপর জুয়েলকে ফোন করব।","Buy milk tomorrow.");
        check(QuickThoughtPolicy.summaryAccepted("Buy milk 😀. Call Juel.","Buy milk 😀. Call Juel."),"spoken/source emoji preserved");
        unsafe("Buy milk 😀. Call Juel.","Buy milk.");unsafe("Buy milk. Call Juel.","Buy milk 🤡.");
        String abbreviations="Dr. J. Smith paid $5.50 at 5:30. Then call Juel.";
        check(QuickThoughtPolicy.summaryAccepted(abbreviations,"Dr J Smith paid $5.50 at 5:30. Then call Juel."),"abbreviation, initial, decimal and time stay in one source sentence");
        unsafe(abbreviations,"Smith paid $5.50 at 5:30.");
        unsafe("He said \"Do not call. Buy milk. \" Then left.","Buy milk.");
        unsafe("He said 'Do not call. Buy milk. ' Then left.","Buy milk.");
        unsafe("If approved (maybe. Not sure) buy milk.","Not sure buy milk.");
        unsafe("Maybe... Buy milk tomorrow.","Buy milk tomorrow.");
        unsafe("Do not\ncall Juel tomorrow.","Call Juel tomorrow.");
        unsafe("Do not call Juel; buy milk tomorrow.","Buy milk tomorrow.");
        equal("Buy milk. Call Juel. Walk home.",QuickThoughtPolicy.summary("Buy milk. Call Juel. Walk home."));
        String unpunctuated="I was thinking about maybe repainting the kitchen then buying blue paint";
        equal(unpunctuated,QuickThoughtPolicy.summary(unpunctuated));
        StringBuilder longSentence=new StringBuilder();for(int i=0;i<300;i++)longSentence.append("word ");longSentence.append("end.");
        equal(longSentence+" Later.",QuickThoughtPolicy.summary(longSentence+" Later."));
        QuickThoughtPolicy.Organized result=QuickThoughtPolicy.organizeReply(raw,json("Maybe call Juel tomorrow",raw,raw));
        check(result.accepted,"body/title accepted separately from summary");equal(raw,result.body);equal(raw,result.summary);
        result=QuickThoughtPolicy.organizeReply(raw,json("Maybe call Juel tomorrow",raw,"Call Juel tomorrow."));
        check(result.accepted,"unsafe summary does not discard faithful full body");equal(raw,result.body);equal(QuickThoughtPolicy.summary(raw),result.summary);
        result=QuickThoughtPolicy.organized(raw,"Maybe call Juel tomorrow",raw);equal(QuickThoughtPolicy.summary(raw),result.summary);
        for(String invalid:new String[]{
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\"}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"summary\":null}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"summary\":3}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"summary\":\"Buy milk\",\"summary\":\"Buy milk\"}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"summary\":\"Buy milk\",\"reminder\":true}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"title\":\"Buy milk\"}",
            "{\"title\":\"Buy milk\",\"body\":\"Buy milk\",\"summary\":\"Buy milk\",}",
            json("Buy milk","Buy milk","Buy milk")+" trailing"
        }){result=QuickThoughtPolicy.organizeReply("Buy milk",invalid);check(!result.accepted,"strict three-field schema rejects malformed response");equal("Buy milk",result.body);equal("Buy milk",result.summary);}
        result=QuickThoughtPolicy.organizeReply("Buy milk","{\"summary\":\"Buy milk\",\"body\":\"Buy milk\",\"title\":\"Buy milk\"}");check(result.accepted,"three fields accepted in any order");
        System.out.println("PASS: "+checks+" complete-sentence summary, uncertainty, Unicode, source-order and strict schema checks.");
    }
}
