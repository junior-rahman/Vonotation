package app.voiceflow;

/** A paste dispatch owns one backend until text and caret are acknowledged; polling never resends it. */
final class ClipboardPaste {
    static final int FAILED=-1,PENDING=0,APPLIED=1;
    interface Clock {long now();}
    private final EditorInsertion.Port port;
    private final EditorInsertion.Snapshot source,expected;
    private final Clock clock;
    private final String exact;
    private boolean dispatched,failed;
    private long sentAt;
    ClipboardPaste(EditorInsertion.Port port,EditorInsertion.Snapshot source,String exact){this(port,source,exact,()->System.nanoTime()/1_000_000L);}
    ClipboardPaste(EditorInsertion.Port port,EditorInsertion.Snapshot source,String exact,Clock clock){
        this.port=port;this.source=source;this.exact=exact;this.clock=clock;
        int cursor=source.start<0?-1:Math.min(source.start,source.end)+exact.length();
        String before=source.before+exact;
        expected=new EditorInsertion.Snapshot(before,"",source.after,cursor,cursor);
    }
    boolean dispatch(){
        if(dispatched)return !failed;
        if(port==null||!port.current()||!ClipboardText.usable(exact)||!ClipboardText.same(source,port.read())){failed=true;return false;}
        dispatched=true;sentAt=clock.now();
        try{if(!port.replace(source.start<0?-1:Math.min(source.start,source.end),source.end<0?-1:Math.max(source.start,source.end),0,exact)){failed=true;return false;}}
        catch(RuntimeException uncertain){/* Observe, never resend after a possible remote mutation. */}
        return true;
    }
    int check(){
        if(failed||!dispatched)return FAILED;
        try{
            if(!port.current()){failed=true;return FAILED;}
            EditorInsertion.Snapshot now=port.read();
            if(acknowledged(now))return APPLIED;
            boolean sourceStill=ClipboardText.same(source,now);
            boolean caretPending=now!=null&&now.selected.isEmpty()&&now.after.isEmpty()&&now.start==now.end&&source.start>=0&&Math.min(source.start,source.end)==source.before.length()
                &&(now.before+now.selected+now.after).equals(source.before+exact+source.after);
            if((now!=null&&!sourceStill&&!caretPending)||clock.now()-sentAt>=EditorInsertion.ACK_MS){failed=true;return FAILED;}
        }catch(RuntimeException unavailable){if(clock.now()-sentAt>=EditorInsertion.ACK_MS){failed=true;return FAILED;}}
        return PENDING;
    }
    private boolean acknowledged(EditorInsertion.Snapshot now){return now!=null&&(now.before.equals(expected.before)||now.before.equals(expected.before.substring(Math.max(0,expected.before.length()-EditorInsertion.CONTEXT))))&&now.selected.isEmpty()&&now.after.equals(expected.after)&&(expected.start<0||now.start<0||(now.start==expected.start&&now.end==expected.end));}
}
