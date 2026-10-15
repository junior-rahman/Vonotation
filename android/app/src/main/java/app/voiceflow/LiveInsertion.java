package app.voiceflow;

/** A dictation owns only its insertion range; every update verifies the last text and cursor. */
final class LiveInsertion {
    private final String before,after;
    private String expected;
    private int expectedStart,expectedEnd;
    LiveInsertion(String original,int start,int end) {
        start=start<0?original.length():Math.min(start,original.length()); end=end<0?start:Math.min(end,original.length());
        before=original.substring(0,Math.min(start,end)); after=original.substring(Math.max(start,end));
        expected=original; expectedStart=start; expectedEnd=end;
    }
    String render(String words) { return before+TextInsertion.addition(before,words,after)+after; }
    int cursor(String words) { return before.length()+TextInsertion.addition(before,words,after).length(); }
    boolean matches(String text,int start,int end) { return expected.equals(text) && (start<0 || end<0 || (expectedStart==start && expectedEnd==end)); }
    void accept(String words) { expected=render(words); expectedStart=expectedEnd=cursor(words); }
}
