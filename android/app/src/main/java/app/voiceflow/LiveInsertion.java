package app.voiceflow;

/** A dictation owns only its insertion range; every update verifies the last text and cursor. */
final class LiveInsertion {
    private final String before,after;
    private String expected;
    private int expectedStart,expectedEnd;
    LiveInsertion(String original,int start,int end) {
        if(original==null||start<0||end<0||start>original.length()||end>original.length())throw new IllegalArgumentException("A current selection is required");
        before=original.substring(0,Math.min(start,end)); after=original.substring(Math.max(start,end));
        expected=original; expectedStart=start; expectedEnd=end;
    }
    String render(String words) { return before+TextInsertion.addition(before,words,after)+after; }
    int cursor(String words) { return before.length()+TextInsertion.addition(before,words,after).length(); }
    boolean matches(String text,int start,int end) { return expected.equals(text) && start>=0 && end>=0 && expectedStart==start && expectedEnd==end; }
    void accept(String words) { expected=render(words); expectedStart=expectedEnd=cursor(words); }
}
