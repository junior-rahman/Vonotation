package app.voiceflow;

/** Current-selection word deletion. Native offsets may be relative; destructive node edits require absolute ones. */
final class CursorDeletion {
    static final int FAILED=-1,IDLE=0,PENDING=1,APPLIED=2;
    static final long ACK_MS=2000;
    interface Port {
        boolean current();
        EditorInsertion.Snapshot read();
        default boolean canDelete(Plan plan){return true;}
        /** False means refused; a true dispatch is not an acknowledgement. Never switch ports after calling this. */
        boolean delete(Plan plan);
    }
    interface Clock {long now();}
    static final class Plan {
        final EditorInsertion.Snapshot source;
        final int start,end,beforeUnits;
        final boolean selected;
        final String remainingBefore;
        Plan(EditorInsertion.Snapshot source,int start,int end,int beforeUnits,boolean selected){
            this.source=source;this.start=start;this.end=end;this.beforeUnits=beforeUnits;this.selected=selected;
            remainingBefore=source.before.substring(0,source.before.length()-beforeUnits);
        }
        boolean absolute(){return start>=0&&end>=0;}
    }
    static boolean absolute(EditorInsertion.Snapshot value){return value!=null&&value.start>=0&&value.end>=0;}
    static Plan plan(EditorInsertion.Snapshot snapshot){
        if(snapshot==null || snapshot.before==null || snapshot.selected==null || snapshot.after==null)return null;
        boolean absolute=absolute(snapshot);
        if((snapshot.start<0)!=(snapshot.end<0))return null;
        int lo=absolute?Math.min(snapshot.start,snapshot.end):-1,hi=absolute?Math.max(snapshot.start,snapshot.end):-1;
        if(absolute&&(hi-lo!=snapshot.selected.length()||lo<snapshot.before.length()))return null;
        if(splitPair(snapshot.before,snapshot.selected.isEmpty()?snapshot.after:snapshot.selected)||splitPair(snapshot.selected.isEmpty()?snapshot.before:snapshot.selected,snapshot.after))return null;
        if(!snapshot.selected.isEmpty())return new Plan(snapshot,lo,hi,0,true);
        String before=snapshot.before;int at=before.length();
        while(at>0){int cp=before.codePointBefore(at);if(!space(cp))break;at-=Character.charCount(cp);}
        while(at>0){int cp=before.codePointBefore(at);if(space(cp))break;at-=Character.charCount(cp);}
        if(at==before.length())return null;
        // A relative cursor is valid, but an unseen start of a long word must not be guessed.
        if(at==0&&(!absolute||lo>before.length()))return null;
        int count=before.length()-at;return new Plan(snapshot,absolute?lo-count:-1,lo,count,false);
    }
    /** Anchor relative native context only against the same freshly read node selection and content. */
    static EditorInsertion.Snapshot anchored(EditorInsertion.Snapshot nativeText,EditorInsertion.Snapshot node){
        if(nativeText==null||absolute(nativeText)||!absolute(node))return nativeText;
        if(matchesNode(nativeText,node))return new EditorInsertion.Snapshot(nativeText.before,nativeText.selected,nativeText.after,node.start,node.end);
        return nativeText;
    }
    /** Native context may be shorter, but it must describe this exact readable node cursor. */
    static boolean matchesNode(EditorInsertion.Snapshot nativeText,EditorInsertion.Snapshot node){
        return nativeText!=null&&absolute(node)
            &&(!absolute(nativeText)||(Math.min(nativeText.start,nativeText.end)==Math.min(node.start,node.end)&&Math.max(nativeText.start,nativeText.end)==Math.max(node.start,node.end)))
            &&node.before.endsWith(nativeText.before)&&node.selected.equals(nativeText.selected)&&node.after.startsWith(nativeText.after);
    }
    private static boolean space(int cp){return Character.isWhitespace(cp)||Character.isSpaceChar(cp);}
    private static boolean splitPair(String left,String right){return !left.isEmpty()&&!right.isEmpty()&&Character.isHighSurrogate(left.charAt(left.length()-1))&&Character.isLowSurrogate(right.charAt(0));}
    static boolean same(EditorInsertion.Snapshot a,EditorInsertion.Snapshot b){
        return a!=null&&b!=null&&(!absolute(a)||!absolute(b)||(Math.min(a.start,a.end)==Math.min(b.start,b.end)&&Math.max(a.start,a.end)==Math.max(b.start,b.end)))&&a.before.equals(b.before)&&a.selected.equals(b.selected)&&a.after.equals(b.after);
    }
    static final class Session {
        private final Port port;private final Clock clock;
        private Plan previous,pending;
        private long sentAt;
        private boolean invalid;
        private int status=IDLE;
        Session(Port port){this(port,()->System.nanoTime()/1_000_000L);}
        Session(Port port,Clock clock){this.port=port;this.clock=clock;}
        boolean pending(){return pending!=null&&!invalid;}
        int status(){return status;}
        boolean delete(){
            if(invalid||port==null||!port.current())return fail();
            if(pending!=null)return check()!=FAILED;
            EditorInsertion.Snapshot current=port.read();
            if(previous!=null&&!acknowledged(previous,current))return fail();
            Plan next=plan(current);if(next==null||!port.canDelete(next))return fail();
            // Lock this session to its backend before a possibly asynchronous mutation.
            pending=next;sentAt=clock.now();status=PENDING;
            // A remote editor can throw after accepting a command. Keep observing this
            // dispatch; a retry or backend switch could otherwise delete twice.
            try{if(!port.delete(next))return fail();}catch(RuntimeException uncertain){return true;}
            return check()!=FAILED;
        }
        /** Observe only. Repeated polling can never send another deletion. */
        int check(){
            if(invalid||port==null){fail();return FAILED;}
            boolean currentPort;try{currentPort=port.current();}catch(RuntimeException unavailable){return waiting();}
            if(!currentPort){fail();return FAILED;}
            if(pending==null)return IDLE;
            EditorInsertion.Snapshot current;try{current=port.read();}catch(RuntimeException unavailable){return waiting();}
            if(acknowledged(pending,current)){previous=pending;pending=null;status=APPLIED;return APPLIED;}
            if((current!=null&&!same(pending.source,current)&&!awaitingCaret(pending,current))||clock.now()-sentAt>=ACK_MS){fail();return FAILED;}
            status=PENDING;return PENDING;
        }
        private int waiting(){if(pending==null||clock.now()-sentAt>=ACK_MS){fail();return FAILED;}status=PENDING;return PENDING;}
        private boolean fail(){invalid=true;pending=null;status=FAILED;return false;}
        private static boolean awaitingCaret(Plan plan,EditorInsertion.Snapshot current){
            // SET_TEXT and SET_SELECTION can be applied on different turns. An intermediate
            // caret at the end is pending, never permission to send another deletion.
            return plan.absolute()&&plan.source.before.length()==Math.min(plan.source.start,plan.source.end)
                &&(current.before+current.selected+current.after).equals(plan.remainingBefore+plan.source.after);
        }
        private static boolean acknowledged(Plan plan,EditorInsertion.Snapshot current){
            return current!=null&&(!plan.absolute()||!absolute(current)||(current.start==plan.start&&current.end==plan.start))&&current.selected.isEmpty()
                &&current.before.endsWith(plan.remainingBefore)&&current.after.equals(plan.source.after)
                // Relative reads must show the actual removal, not just a matching empty suffix.
                &&(!same(plan.source,current));
        }
    }
    private CursorDeletion(){}
}
