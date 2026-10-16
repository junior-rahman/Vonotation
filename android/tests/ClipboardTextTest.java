package app.voiceflow;

public final class ClipboardTextTest {
    public static void main(String[] args){
        String clip="  hello\nworld\t ";check(ClipboardText.usable(clip));equal("hello world",ClipboardText.preview(clip));
        equal(clip,ClipboardText.prepend(clip,new String[]{"older",clip})[0]);
        check(!ClipboardText.usable(null));check(!ClipboardText.usable(""));check(!ClipboardText.usable(" \t\n\u00a0"));
        String[] items=new String[30];for(int i=0;i<items.length;i++)items[i]="item "+i;
        String[] bounded=ClipboardText.prepend("new",items);check(bounded.length==20);equal("new",bounded[0]);equal("item 18",bounded[19]);
        check(ClipboardText.prepend(null,new String[]{"same","same","","different"}).length==2);
        ClipboardText.Selection selected=new ClipboardText.Selection("before SELECT after",13,7);
        equal("before "+clip+" after",selected.take("before SELECT after",13,7,clip));check(selected.cursor(clip)==7+clip.length());
        check(selected.take("before SELECT after",13,7,clip)==null);
        ClipboardText.Selection cursor=new ClipboardText.Selection("AB",1,1);equal("A"+clip+"B",cursor.take("AB",1,1,clip));
        check(new ClipboardText.Selection("AB",-1,-1).take("AB",-1,-1,clip)==null);
        check(new ClipboardText.Selection("AB",1,1).take("AB",0,0,clip)==null);
        check(new ClipboardText.Selection("AB",1,1).take("AC",1,1,clip)==null);
        String emoji="😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀😀";
        String preview=ClipboardText.preview(emoji);check(preview.codePointCount(0,preview.length())==37);check(preview.endsWith("…"));
        StringBuilder longBuilder=new StringBuilder("  long copy\n");for(int i=0;i<10000;i++)longBuilder.append('x');longBuilder.append("\n  ");String longCopy=longBuilder.toString();
        check(ClipboardText.usable(longCopy));check(ClipboardText.storable(longCopy));check(ClipboardText.preview(longCopy).length()<100);
        String[] runtime=ClipboardText.withCurrent(longCopy,items);check(runtime.length==20);equal(longCopy,runtime[0]);equal("item 0",runtime[1]);
        String[] persisted=ClipboardText.prepend(longCopy,new String[]{"saved"});check(persisted.length==2);equal(longCopy,persisted[0]);equal("saved",persisted[1]);
        ClipboardText.Selection longSelection=new ClipboardText.Selection("start [replace] end",7,14);
        equal("start ["+longCopy+"] end",longSelection.take("start [replace] end",7,14,longCopy));check(longSelection.cursor(longCopy)==7+longCopy.length());
        check(ClipboardText.withCurrent("saved",new String[]{"saved","older"}).length==2);
        EditorInsertion.Snapshot snap=new EditorInsertion.Snapshot("A","B","C",1,2);
        check(ClipboardText.same(snap,new EditorInsertion.Snapshot("A","B","C",1,2)));
        check(!ClipboardText.same(snap,new EditorInsertion.Snapshot("A","B","C",2,1)));
        System.out.println("PASS: exact whitespace, bounded deduplicated history, single-use selection replacement, changed text/cursor rejection, Unicode preview and full long-copy retention eligibility and insertion");
    }
    private static void check(boolean good){if(!good)throw new AssertionError();}
    private static void equal(String expected,String actual){if(!expected.equals(actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
