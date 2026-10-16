package app.voiceflow;

/** Owns only the dictated range; the editor can keep all other text and formatting. */
final class EditorInsertion {
    static final int CONTEXT=512;
    static final long ACK_MS=2000;
    interface Clock {long now();}
    interface Port {
        default boolean current(){return true;}
        Snapshot read();
        boolean replace(int start,int end,int previousLength,String text);
    }
    static final class Snapshot {
        final String before,selected,after;
        final int start,end;
        Snapshot(String before,String selected,String after,int start,int end) {
            this.before=before;this.selected=selected;this.after=after;this.start=start;this.end=end;
        }
    }
    private final Snapshot original;
    private final Clock clock;
    private String addition="";
    private boolean written,invalid;
    private Snapshot pendingSource;
    private String pendingAddition;
    private long sentAt;
    EditorInsertion(Snapshot original){this(original,()->System.nanoTime()/1_000_000L);}
    EditorInsertion(Snapshot original,Clock clock){this.original=original;this.clock=clock;}
    boolean matches(Snapshot current) {
        if(invalid)return false;
        if(pendingSource!=null){
            if(!matches(current,pendingAddition,true))return false;
            addition=pendingAddition;written=true;pendingSource=null;pendingAddition=null;
        }
        return matches(current,addition,written);
    }
    /** An unchanged pre-dispatch snapshot is latency, not permission to resend. */
    boolean compatible(Snapshot current){
        if(matches(current))return true;
        return !invalid&&pendingSource!=null&&clock.now()-sentAt<ACK_MS&&(same(pendingSource,current)||awaitingCaret(current));
    }
    private boolean awaitingCaret(Snapshot current){return current!=null&&current.selected.isEmpty()&&current.after.isEmpty()&&current.start==current.end&&original.start>=0&&Math.min(original.start,original.end)==original.before.length()
        &&(current.before+current.selected+current.after).equals(original.before+pendingAddition+original.after);}
    private boolean matches(Snapshot current,String value,boolean applied) {
        if(current==null) return false;
        String before=applied?original.before+value:original.before;
        String selected=applied?"":original.selected;
        int start=applied && original.start>=0?Math.min(original.start,original.end)+value.length():original.start;
        int end=applied?start:original.end;
        return (current.before.equals(before)||(applied&&current.before.equals(tail(before)))) && current.selected.equals(selected) && current.after.equals(original.after)
            && (start<0 || current.start<0 || (start==current.start && end==current.end));
    }
    boolean write(Port port,String words) {
        if(port==null||!port.current()||invalid)return false;
        Snapshot current=port.read();
        if(!compatible(current)){if(current!=null)invalid=true;return false;}
        if(pendingSource!=null)return true;
        String next=TextInsertion.addition(original.before,words,original.after);
        if(written && next.equals(addition)) return true;
        int start=original.start<0?-1:Math.min(original.start,original.end);
        int end=written?(start<0?-1:start+addition.length()):(original.end<0?-1:Math.max(original.start,original.end));
        // Record ownership before dispatch: a remote exception can arrive after the write.
        pendingSource=current;pendingAddition=next;sentAt=clock.now();
        try{if(!port.replace(start,end,written?addition.length():0,next)){invalid=true;return false;}}
        catch(RuntimeException uncertain){return true;}
        return true;
    }
    private static boolean same(Snapshot a,Snapshot b){return a!=null&&b!=null&&a.start==b.start&&a.end==b.end&&a.before.equals(b.before)&&a.selected.equals(b.selected)&&a.after.equals(b.after);}
    private static String tail(String value){return value.substring(Math.max(0,value.length()-CONTEXT));}
    /** Keep the existing selection, delete our previous words, or select a range. */
    static int replacementMode(Snapshot current,int start,int end,int previousLength){
        if(previousLength>0 && current.selected.isEmpty() && (end<0 || current.start<0 || current.start==end))return 1;
        if(start<0 || end<0 || current.start<0 || current.end<0)return 0;
        return Math.min(current.start,current.end)==start && Math.max(current.start,current.end)==end?0:2;
    }
}
