package app.voiceflow;

public final class CoreTest {
    static int count;
    private static void equal(String expected,String actual) {
        count++;
        if(!expected.equals(actual)) throw new AssertionError("Expected ["+expected+"] but got ["+actual+"]");
    }
    public static void main(String[] args) {
        equal("Hello, Juel",Cleaner.clean("um hello comma jewel",true,"jewel=Juel"));
        equal("hello um",Cleaner.clean("  hello um  ",false,""));
        equal("Tomorrow\nBring tea",Cleaner.clean("tomorrow new line Bring tea",true,""));
        equal("Human summary umbrella",Cleaner.clean("human summary umbrella",true,""));
        equal("Meet Zara tomorrow",Cleaner.clean("meet zara tomorrow",true,"zara=Zara"));
        equal("আমি ভালো আছি",Cleaner.clean("আমি ভালো আছি",true,""));
        equal("$5",Cleaner.clean("five",false,"five=$5"));
        equal("A+B",Cleaner.clean("a+b",false,"a+b=A+B"));
        equal("Juel and jewels",Cleaner.clean("jewel and jewels",false,"jewel=Juel"));
        equal(" next ",TextInsertion.addition("hello","next","word"));
        equal("next",TextInsertion.addition("hello ","next","!"));
        equal("আমি",TextInsertion.addition("","আমি",""));
        equal(" ভালো ",TextInsertion.addition("আমি","ভালো","আছি"));
        equal("",TextInsertion.addition("hello","","there"));
        equal(" Next. ",TextInsertion.addition("Before.","Next.","After"));
        equal("hello. ",TextInsertion.addition("","hello.","Next"));
        equal(" Next ",TextInsertion.addition("Before!","Next","After"));
        equal(", ",TextInsertion.addition("hello",",","world"));
        equal(".",TextInsertion.addition("hello",".",""));
        equal("hello",TextInsertion.addition("(","hello",")"));
        equal("hello",TextInsertion.addition("\"","hello","\""));
        equal("hello",TextInsertion.addition("“","hello","”"));
        equal(" Next ",TextInsertion.addition("(Before)","Next","After"));
        equal(" Next ",TextInsertion.addition("“Before”","Next","After"));
        equal(" Next ",TextInsertion.addition("\"Before\"","Next","After"));
        equal("hello. ",TextInsertion.addition(" ","hello.","Next"));
        equal("hello.",TextInsertion.addition(" ","hello."," Next"));
        equal(" এরপর ",TextInsertion.addition("আগে।","এরপর","শেষ"));
        equal("bn",Cleaner.languageCode("bn-BD"));
        if(Cleaner.words(" ")!=0 || Cleaner.words("two words")!=2) throw new AssertionError("Word count failed");
        System.out.println("Passed "+count+" core cases plus word-count checks.");
    }
}
