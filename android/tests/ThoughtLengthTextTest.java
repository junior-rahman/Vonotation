package app.voiceflow;

public final class ThoughtLengthTextTest {
    static int checks;
    static void equal(String expected,String actual){checks++;if(!expected.equals(actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
    public static void main(String[] args){
        equal("",ThoughtLengthText.normalize(null));equal("",ThoughtLengthText.normalize(""));
        equal("",ThoughtLengthText.normalize(" \n\r\t\u00a0\u2003\u2028\u2029 "));
        equal("Hello there",ThoughtLengthText.normalize(" \tHello\r\n\nthere\t "));
        equal("Hello there",ThoughtLengthText.normalize("Hello\u00a0\u2003there"));
        equal("Hello",ThoughtLengthText.normalize("\u200b\u200bHello\u2060\u200e"));
        equal("👨‍👩‍👧‍👦",ThoughtLengthText.normalize("👨‍👩‍👧‍👦"));
        equal("ক্‌ষ",ThoughtLengthText.normalize("ক্‌ষ"));
        equal("Hi there",ThoughtLengthText.normalize("Hi\n\n\nthere"));
        String original="  আগামীকাল\nদুধ কিনব। 😀 Maybe not −5 dollars.  ";
        equal("আগামীকাল দুধ কিনব। 😀 Maybe not −5 dollars.",ThoughtLengthText.normalize(original));
        equal("  আগামীকাল\nদুধ কিনব। 😀 Maybe not −5 dollars.  ",original);
        equal("No don't change $5.50 or 5%",ThoughtLengthText.normalize("No don't change $5.50 or 5%"));
        equal("word word",ThoughtLengthText.normalize("word\u0000\u0007\nword"));
        System.out.println("PASS: "+checks+" whitespace/format padding, Unicode, empty text and original transcript preservation checks.");
    }
}
