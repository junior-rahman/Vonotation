package app.voiceflow;

/** Owns only the dictated range; the editor can keep all other text and formatting. */
final class EditorInsertion {
    static final int CONTEXT=512;
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
    private String addition="";
    private boolean written;
    EditorInsertion(Snapshot original){this.original=original;}
    boolean matches(Snapshot current) {
        if(current==null) return false;
        String before=written?tail(original.before+addition):original.before;
        String selected=written?"":original.selected;
        int start=written && original.start>=0?Math.min(original.start,original.end)+addition.length():original.start;
        int end=written?start:original.end;
        return current.before.equals(before) && current.selected.equals(selected) && current.after.equals(original.after)
            && (start<0 || current.start<0 || (start==current.start && end==current.end));
    }
    boolean write(Port port,String words) {
        if(!matches(port.read())) return false;
        String next=TextInsertion.addition(original.before,words,original.after);
        if(written && next.equals(addition)) return true;
        int start=original.start<0?-1:Math.min(original.start,original.end);
        int end=written?(start<0?-1:start+addition.length()):(original.end<0?-1:Math.max(original.start,original.end));
        if(!port.replace(start,end,written?addition.length():0,next)) return false;
        addition=next;written=true;
        return true;
    }
    private static String tail(String value){return value.substring(Math.max(0,value.length()-CONTEXT));}
    /** Keep the existing selection, delete our previous words, or select a range. */
    static int replacementMode(Snapshot current,int start,int end,int previousLength){
        if(previousLength>0 && current.selected.isEmpty() && (end<0 || current.start<0 || current.start==end))return 1;
        if(start<0 || end<0 || current.start<0 || current.end<0)return 0;
        return Math.min(current.start,current.end)==start && Math.max(current.start,current.end)==end?0:2;
    }
}
