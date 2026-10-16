package app.voiceflow;

public final class CursorDeletionTest {
    static int checks;
    static final class Clock implements CursorDeletion.Clock {long time=100;public long now(){return time;}}
    static final class Editor implements CursorDeletion.Port {
        String text;int a,b,dispatches;boolean current=true,accept=true,ack=true,relative,full;
        CursorDeletion.Plan queued;
        Editor(String text,int a,int b){this.text=text;this.a=a;this.b=b;}
        public boolean current(){return current;}
        public EditorInsertion.Snapshot read(){if(!current||a<0||b<0)return null;int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(text.substring(full?0:Math.max(0,lo-512),lo),text.substring(lo,hi),text.substring(hi,full?text.length():Math.min(text.length(),hi+512)),relative?-1:a,relative?-1:b);}
        public boolean delete(CursorDeletion.Plan plan){dispatches++;if(!accept||!CursorDeletion.same(plan.source,read()))return false;queued=plan;if(ack)acknowledge();return true;}
        void acknowledge(){CursorDeletion.Plan plan=queued;if(plan==null)return;int start=plan.absolute()?plan.start:Math.min(a,b)-plan.beforeUnits,end=plan.absolute()?plan.end:Math.max(a,b);text=text.substring(0,start)+text.substring(end);a=b=start;queued=null;}
    }
    static void check(boolean yes,String detail){checks++;if(!yes)throw new AssertionError(detail);}
    static void eq(Object expected,Object actual){check(expected.equals(actual),"Expected ["+expected+"] got ["+actual+"]");}
    static void one(String text,int a,int b,String expected,int caret){Editor e=new Editor(text,a,b);CursorDeletion.Session session=new CursorDeletion.Session(e);check(session.delete(),"delete accepted");eq(CursorDeletion.APPLIED,session.status());eq(expected,e.text);eq(caret,e.a);eq(caret,e.b);}
    public static void main(String[] args){
        one("one two three",7,7,"one  three",4);
        one("prefix chosen suffix",7,13,"prefix  suffix",7);one("prefix chosen suffix",13,7,"prefix  suffix",7);
        one("first\nsecond\nlast",12,12,"first\n\nlast",6);one("one two   tail",10,10,"one tail",4);one("one\u00a0two tail",7,7,"one\u00a0 tail",4);
        one("one 😀 tail",6,6,"one  tail",4);one("one 😀 tail",4,6,"one  tail",4);one("one 😀 tail",6,4,"one  tail",4);one("আমার কথা শেষ",8,8,"আমার  শেষ",5);
        Editor e=new Editor("one two three tail",13,13);CursorDeletion.Session held=new CursorDeletion.Session(e);
        check(held.delete(),"first held delete");eq("one two  tail",e.text);check(held.delete(),"second held delete");eq("one  tail",e.text);eq(4,e.a);
        e=new Editor("one two three tail",13,13);held=new CursorDeletion.Session(e);check(held.delete(),"held starts");e.a=e.b=3;String manual=e.text;check(!held.delete(),"manual cursor move stops repeat");eq(manual,e.text);eq(3,e.a);check(!held.delete(),"stopped hold cannot restart itself");
        check(new CursorDeletion.Session(e).delete(),"new touch uses user's moved cursor");eq(" two  tail",e.text);eq(0,e.a);
        e=new Editor("one two three",7,7);held=new CursorDeletion.Session(e);check(held.delete(),"first before manual edit");e.text="one manual three";e.a=e.b=4;check(!held.delete(),"manual text edit stops repeat");eq("one manual three",e.text);
        e=new Editor("one two",7,7);held=new CursorDeletion.Session(e);check(held.delete(),"first field edit");e.current=false;check(!held.delete(),"field change stops repeat");eq("one ",e.text);
        e=new Editor("do not delete end",-1,-1);check(!new CursorDeletion.Session(e).delete(),"missing actual selection fails closed");eq("do not delete end",e.text);eq(0,e.dispatches);
        e=new Editor("one two",0,0);check(!new CursorDeletion.Session(e).delete(),"start of input is no-op");eq("one two",e.text);
        Clock clock=new Clock();e=new Editor("one two",7,7);e.ack=false;held=new CursorDeletion.Session(e,clock);check(held.delete(),"queued native dispatch");eq(CursorDeletion.PENDING,held.status());check(held.delete(),"pending repeat only observes");eq(1,e.dispatches);eq("one two",e.text);
        clock.time+=500;e.acknowledge();eq(CursorDeletion.APPLIED,held.check());eq("one ",e.text);eq(CursorDeletion.IDLE,held.check());check(held.delete(),"hold resumes only after actual acknowledgement");eq(2,e.dispatches);
        clock=new Clock();e=new Editor("one two",7,7);e.ack=false;held=new CursorDeletion.Session(e,clock);check(held.delete(),"unapplied dispatch accepted as pending");clock.time+=CursorDeletion.ACK_MS;eq(CursorDeletion.FAILED,held.check());check(!held.delete(),"timed out action cannot be duplicated");eq(1,e.dispatches);
        e=new Editor("one two",7,7);e.accept=false;check(!new CursorDeletion.Session(e).delete(),"rejected action fails closed");eq("one two",e.text);
        e=new Editor("one two tail",7,7);e.relative=true;held=new CursorDeletion.Session(e);check(held.delete(),"unknown absolute offset uses fresh relative caret");eq("one  tail",e.text);eq(4,e.a);eq(CursorDeletion.APPLIED,held.status());
        e=new Editor("prefix chosen suffix",13,7);e.relative=true;check(new CursorDeletion.Session(e).delete(),"unknown absolute selected range");eq("prefix  suffix",e.text);eq(7,e.a);
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("x","","y",-1,-1))==null,"unknown truncated word start is not guessed");
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("x","bad","y",1,2))==null,"inconsistent selected length rejected");
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("one \ud83d","","\ude00 tail",5,5))==null,"split-surrogate caret rejected");check(CursorDeletion.plan(new EditorInsertion.Snapshot("one \ud83d","\ude00"," tail",5,6))==null,"split-surrogate selection rejected");
        EditorInsertion.Snapshot relative=new EditorInsertion.Snapshot("hello",""," tail",-1,-1),node=new EditorInsertion.Snapshot("hello",""," tail",5,5);
        EditorInsertion.Snapshot anchored=CursorDeletion.anchored(relative,node);check(CursorDeletion.absolute(anchored),"fresh matching node anchors first-word native context");check(CursorDeletion.plan(anchored)!=null,"first word plannable after verified anchor");
        check(!CursorDeletion.absolute(CursorDeletion.anchored(relative,new EditorInsertion.Snapshot("different",""," tail",9,9))),"mismatching node cannot invent native cursor");
        check(CursorDeletion.same(relative,anchored),"same current context can gain absolute metadata");
        check(CursorDeletion.matchesNode(new EditorInsertion.Snapshot("lo",""," ta",5,5),node),"short native context matches full node cursor");
        check(!CursorDeletion.matchesNode(new EditorInsertion.Snapshot("hello",""," tail",6,6),node),"same text at another absolute cursor is not the same node context");
        check(!CursorDeletion.matchesNode(new EditorInsertion.Snapshot("other",""," tail",5,5),node),"stale native field text is not accepted for a current node");
        check(CursorDeletion.matchesNode(relative,node),"unknown native offset can match a readable node");
        StringBuilder longWord=new StringBuilder();for(int i=0;i<800;i++)longWord.append('x');e=new Editor(longWord+" suffix",800,800);check(!new CursorDeletion.Session(e).delete(),"truncated long word is not partially guessed");eq(0,e.dispatches);e.full=true;check(new CursorDeletion.Session(e).delete(),"full node word plan handles native truncation");eq(" suffix",e.text);eq(0,e.a);
        StringBuilder prefix=new StringBuilder();for(int i=0;i<100;i++)prefix.append("a word ");String longText=prefix+"target suffix";e=new Editor(longText,prefix.length()+6,prefix.length()+6);held=new CursorDeletion.Session(e);check(held.delete(),"long field current word");check(held.delete(),"native context refill preserves held deletion");check(e.text.endsWith("a  suffix"),"long field suffix preserved");
        clock=new Clock();e=new Editor("one two tail",7,7);e.full=true;e.ack=false;held=new CursorDeletion.Session(e,clock);check(held.delete(),"node action dispatched");e.text="one  tail";e.a=e.b=e.text.length();eq(CursorDeletion.PENDING,held.check());e.a=e.b=4;eq(CursorDeletion.APPLIED,held.check());eq(1,e.dispatches);
        clock=new Clock();final Editor uncertain=new Editor("one two tail",7,7);uncertain.ack=false;CursorDeletion.Port remote=new CursorDeletion.Port(){public boolean current(){return uncertain.current();}public EditorInsertion.Snapshot read(){return uncertain.read();}public boolean delete(CursorDeletion.Plan plan){uncertain.delete(plan);throw new IllegalStateException("remote result lost after dispatch");}};
        held=new CursorDeletion.Session(remote,clock);check(held.delete(),"exception after remote dispatch remains pending");eq(CursorDeletion.PENDING,held.status());check(held.delete(),"pending exception only observes");eq(1,uncertain.dispatches);uncertain.acknowledge();eq(CursorDeletion.APPLIED,held.check());eq("one  tail",uncertain.text);
        clock=new Clock();final Editor lagging=new Editor("one two tail",7,7);lagging.ack=false;final boolean[] unavailable={false};CursorDeletion.Port flaky=new CursorDeletion.Port(){public boolean current(){return lagging.current();}public EditorInsertion.Snapshot read(){if(unavailable[0])throw new IllegalStateException("remote read temporarily unavailable");return lagging.read();}public boolean delete(CursorDeletion.Plan plan){return lagging.delete(plan);}};
        held=new CursorDeletion.Session(flaky,clock);check(held.delete(),"dispatch before temporarily unavailable read");unavailable[0]=true;eq(CursorDeletion.PENDING,held.check());check(held.delete(),"unavailable pending read never resends");eq(1,lagging.dispatches);lagging.acknowledge();unavailable[0]=false;eq(CursorDeletion.APPLIED,held.check());eq("one  tail",lagging.text);
        lagging.text="one two tail";lagging.a=lagging.b=7;held=new CursorDeletion.Session(flaky,clock);check(held.delete(),"second pending read timeout dispatch");unavailable[0]=true;clock.time+=CursorDeletion.ACK_MS;eq(CursorDeletion.FAILED,held.check());eq(2,lagging.dispatches);
        System.out.println("PASS: "+checks+" current-cursor deletion, relative offsets, delayed acknowledgement, no duplicate dispatch and fallback range checks.");
    }
}
