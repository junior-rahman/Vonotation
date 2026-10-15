package app.voiceflow;

public final class QuickThoughtPolicyTest {
    static int checks;
    static void check(boolean valid,String message){checks++;if(!valid)throw new AssertionError(message);}
    public static void main(String[] args){
        check(QuickThoughtPolicy.visible(true,true,false,false,false,false,false),"enabled no-input capture");
        check(!QuickThoughtPolicy.visible(false,true,false,false,false,false,false),"off hides dot");
        check(!QuickThoughtPolicy.visible(true,true,true,false,false,false,false),"lock hides dot");
        check(!QuickThoughtPolicy.visible(true,false,false,false,false,false,false),"screen off hides dot");
        check(!QuickThoughtPolicy.visible(true,true,false,false,false,true,false),"input retains dictation");
        check(!QuickThoughtPolicy.visible(true,true,false,false,true,false,false),"capture suppresses idle dot");
        check(!QuickThoughtPolicy.visible(true,true,false,false,false,false,true),"other recording suppresses dot");
        check(QuickThoughtPolicy.collapsed(60001,1,false),"collapse after one minute");
        check(!QuickThoughtPolicy.collapsed(60000,1,false),"keep expanded before minute");
        check(!QuickThoughtPolicy.collapsed(70000,1,true),"never collapse while working");
        check("Hello, how are you?".equals(QuickThoughtPolicy.formatted("Hello how are you","Hello, how are you?")),"punctuation accepted");
        check("Hello how are you".equals(QuickThoughtPolicy.formatted("Hello how are you","I am fine thanks")),"answer rejected");
        check("Call Juel at 5".equals(QuickThoughtPolicy.formatted("Call Juel at 5","Call Juel at 6")),"changed date rejected");
        check("আমার কথা".equals(QuickThoughtPolicy.formatted("আমার কথা","My words")),"translation rejected");
        check("fuck this".equals(QuickThoughtPolicy.formatted("fuck this","I cannot help with that")),"refusal rejected");
        check("fallback".equals(QuickThoughtPolicy.title(" ","fallback")),"empty title fallback");
        check("Buy milk tomorrow.".equals(QuickThoughtPolicy.title("Buy milk tomorrow. Then call Juel","fallback")),"extract first sentence");
        check("one two three four five six seven eight…".equals(QuickThoughtPolicy.title("one two three four five six seven eight nine ten","fallback")),"title word limit");
        check(QuickThoughtPolicy.canApply("source",1,true,true,"source",1),"current response applies");
        check(!QuickThoughtPolicy.canApply("source",1,false,false,"",-1),"deleted thought stays deleted");
        check(!QuickThoughtPolicy.canApply("source",1,true,true,"source",2),"edit supersedes old formatting");
        check(!QuickThoughtPolicy.canApply("source",1,true,true,"restored",1),"different restored source rejects old formatting");
        check(!QuickThoughtPolicy.canApply("source",1,true,false,"source",1),"never write to normal note");
        String emoji="😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
        String title=QuickThoughtPolicy.title(emoji,"fallback");check(!Character.isHighSurrogate(title.charAt(title.length()-2)),"title never splits emoji");
        System.out.println("PASS: "+checks+" thought capture rules.");
    }
}
