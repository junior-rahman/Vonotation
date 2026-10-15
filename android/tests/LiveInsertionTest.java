package app.voiceflow;

public final class LiveInsertionTest {
    static void check(boolean okay) { if(!okay) throw new AssertionError(); }
    static void equal(String want,String got) { if(!want.equals(got)) throw new AssertionError(want+" != "+got); }
    public static void main(String[] args) {
        LiveInsertion insert=new LiveInsertion("Before after",7,7);
        check(insert.matches("Before after",7,7));
        equal("Before Hello after",insert.render("Hello"));
        insert.accept("Hello");
        check(insert.matches("Before Hello after",13,13));
        equal("Before Hello, world after",insert.render("Hello, world"));
        check(!insert.matches("Before my edit after",13,13));
        check(!insert.matches("Before Hello after",0,0));
        LiveInsertion selected=new LiveInsertion("Keep old ending",5,8);
        equal("Keep new ending",selected.render("new")); selected.accept("new");
        equal("Keep longer words ending",selected.render("longer words"));
        LiveInsertion bengali=new LiveInsertion("আমি আছি",4,4);
        equal("আমি ভালো আছি",bengali.render("ভালো"));
        System.out.println("PASS: live revisions, selected-text replacement, Bengali spacing, and manual-edit/cursor guards");
    }
}
