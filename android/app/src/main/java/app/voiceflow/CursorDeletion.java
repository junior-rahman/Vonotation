package app.voiceflow;

/** Current-selection word deletion. Never guesses an unknown cursor or rewrites the field. */
final class CursorDeletion {
    interface Port {
        boolean current();
        EditorInsertion.Snapshot read();
        boolean delete(Plan plan);
    }
    static final class Plan {
        final EditorInsertion.Snapshot source;
        final int start,end,beforeUnits;
        final boolean selected;
        final String remainingBefore;
        Plan(EditorInsertion.Snapshot source,int start,int end,int beforeUnits,boolean selected){
            this.source=source;this.start=start;this.end=end;this.beforeUnits=beforeUnits;this.selected=selected;
            remainingBefore=source.before.substring(0,source.before.length()-beforeUnits);
        }
    }
    static Plan plan(EditorInsertion.Snapshot snapshot){
        if(snapshot==null || snapshot.start<0 || snapshot.end<0)return null;
        int lo=Math.min(snapshot.start,snapshot.end),hi=Math.max(snapshot.start,snapshot.end);
        if(hi-lo!=snapshot.selected.length() || lo<snapshot.before.length())return null;
        // Bad editor offsets must not split a UTF-16 surrogate pair.
        if(splitPair(snapshot.before,snapshot.selected.isEmpty()?snapshot.after:snapshot.selected) || splitPair(snapshot.selected.isEmpty()?snapshot.before:snapshot.selected,snapshot.after))return null;
        if(hi>lo)return new Plan(snapshot,lo,hi,0,true);
        String before=snapshot.before;int at=before.length();
        while(at>0){int cp=before.codePointBefore(at);if(!space(cp))break;at-=Character.charCount(cp);}
        while(at>0){int cp=before.codePointBefore(at);if(space(cp))break;at-=Character.charCount(cp);}
        if(at==before.length())return null;
        // Context may be truncated; do not delete an arbitrary suffix of a longer word.
        if(at==0 && lo>before.length())return null;
        int count=before.length()-at;
        return new Plan(snapshot,lo-count,lo,count,false);
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    private static boolean splitPair(String left,String right){return !left.isEmpty() && !right.isEmpty() && Character.isHighSurrogate(left.charAt(left.length()-1)) && Character.isLowSurrogate(right.charAt(0));}
    static boolean same(EditorInsertion.Snapshot a,EditorInsertion.Snapshot b){return a!=null && b!=null && Math.min(a.start,a.end)==Math.min(b.start,b.end) && Math.max(a.start,a.end)==Math.max(b.start,b.end) && a.before.equals(b.before) && a.selected.equals(b.selected) && a.after.equals(b.after);}
    static final class Session {
        private final Port port;
        private Plan previous;
        private boolean invalid;
        Session(Port port){this.port=port;}
        boolean delete(){
            if(invalid || port==null || !port.current()){invalid=true;return false;}
            EditorInsertion.Snapshot current=port.read();
            if(previous!=null && !acknowledged(current)){invalid=true;return false;}
            Plan next=plan(current);
            if(next==null || !port.delete(next)){invalid=true;return false;}
            previous=next;return true;
        }
        private boolean acknowledged(EditorInsertion.Snapshot current){
            return current!=null && current.start==previous.start && current.end==previous.start && current.selected.isEmpty()
                && current.before.endsWith(previous.remainingBefore) && current.after.equals(previous.source.after);
        }
    }
    private CursorDeletion(){}
}
