package app.voiceflow;

/** Full-field edits are allowed only after the host actually confirms its current selection. */
final class NodeRewrite {
    interface Port {
        boolean current();
        EditorInsertion.Snapshot read();
        boolean select(int start,int end);
        boolean text(String value);
    }
    static boolean preflight(Port port,EditorInsertion.Snapshot source,boolean needsCaret){
        if(port==null||!full(source)||!port.current()||!same(source,port.read()))return false;
        // Advertised actions are not evidence: custom editors can reject SET_SELECTION.
        boolean selected=port.select(source.start,source.end);
        return (selected||!needsCaret)&&port.current()&&same(source,port.read());
    }
    static boolean dispatch(Port port,EditorInsertion.Snapshot source,String next,int cursor){
        if(next==null||cursor<0||cursor>next.length()||!preflight(port,source,cursor!=next.length()))return false;
        if(!port.text(next))return false;
        // A false result after SET_TEXT is still a dispatched edit. The owner observes
        // text and caret acknowledgement and must never retry on another backend.
        port.select(cursor,cursor);
        return true;
    }
    static boolean full(EditorInsertion.Snapshot s){return s!=null&&s.start>=0&&s.end>=0&&Math.min(s.start,s.end)==s.before.length()&&Math.abs(s.start-s.end)==s.selected.length();}
    static boolean same(EditorInsertion.Snapshot a,EditorInsertion.Snapshot b){return a!=null&&b!=null&&a.start==b.start&&a.end==b.end&&a.before.equals(b.before)&&a.selected.equals(b.selected)&&a.after.equals(b.after);}
    private NodeRewrite(){}
}
