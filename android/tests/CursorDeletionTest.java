package app.voiceflow;

public final class CursorDeletionTest {
    static int checks;
    static final class Editor implements CursorDeletion.Port {
        String text;int a,b,dispatches;boolean current=true,accept=true,ack=true;
        Editor(String text,int a,int b){this.text=text;this.a=a;this.b=b;}
        public boolean current(){return current;}
        public EditorInsertion.Snapshot read(){if(!current)return null;if(a<0||b<0)return new EditorInsertion.Snapshot(text,"","",a,b);int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(text.substring(Math.max(0,lo-512),lo),text.substring(lo,hi),text.substring(hi,Math.min(text.length(),hi+512)),a,b);}
        public boolean delete(CursorDeletion.Plan plan){dispatches++;if(!accept || !CursorDeletion.same(plan.source,read()))return false;if(ack){text=text.substring(0,plan.start)+text.substring(plan.end);a=b=plan.start;}return true;}
    }
    static void check(boolean yes,String detail){checks++;if(!yes)throw new AssertionError(detail);}
    static void eq(Object expected,Object actual){check(expected.equals(actual),"Expected ["+expected+"] got ["+actual+"]");}
    static void one(String text,int a,int b,String expected,int caret){Editor e=new Editor(text,a,b);check(new CursorDeletion.Session(e).delete(),"delete accepted");eq(expected,e.text);eq(caret,e.a);eq(caret,e.b);}
    public static void main(String[] args){
        one("one two three",7,7,"one  three",4);
        one("prefix chosen suffix",7,13,"prefix  suffix",7);
        one("prefix chosen suffix",13,7,"prefix  suffix",7);
        one("first\nsecond\nlast",12,12,"first\n\nlast",6);
        one("one two   tail",10,10,"one tail",4);
        one("one\u00a0two tail",7,7,"one\u00a0 tail",4);
        one("one 😀 tail",6,6,"one  tail",4);
        one("one 😀 tail",4,6,"one  tail",4);
        one("one 😀 tail",6,4,"one  tail",4);
        one("আমার কথা শেষ",8,8,"আমার  শেষ",5);
        Editor e=new Editor("one two three tail",13,13);CursorDeletion.Session held=new CursorDeletion.Session(e);
        check(held.delete(),"first held delete");eq("one two  tail",e.text);check(held.delete(),"second held delete");eq("one  tail",e.text);eq(4,e.a);
        e=new Editor("one two three tail",13,13);held=new CursorDeletion.Session(e);check(held.delete(),"held starts");e.a=e.b=3;String manual=e.text;check(!held.delete(),"manual cursor move stops repeat");eq(manual,e.text);eq(3,e.a);check(!held.delete(),"stopped hold cannot restart itself");
        check(new CursorDeletion.Session(e).delete(),"new touch uses user's moved cursor");eq(" two  tail",e.text);eq(0,e.a);
        e=new Editor("one two three",7,7);held=new CursorDeletion.Session(e);check(held.delete(),"first before manual edit");e.text="one manual three";e.a=e.b=4;check(!held.delete(),"manual text edit stops repeat");eq("one manual three",e.text);
        e=new Editor("one two",7,7);held=new CursorDeletion.Session(e);check(held.delete(),"first field edit");e.current=false;check(!held.delete(),"field change stops repeat");eq("one ",e.text);
        e=new Editor("do not delete end",-1,-1);check(!new CursorDeletion.Session(e).delete(),"unknown offsets fail closed");eq("do not delete end",e.text);eq(0,e.dispatches);
        e=new Editor("one two",0,0);check(!new CursorDeletion.Session(e).delete(),"start of input is no-op");eq("one two",e.text);
        e=new Editor("one two",7,7);e.ack=false;held=new CursorDeletion.Session(e);check(held.delete(),"queued native dispatch");check(!held.delete(),"unacknowledged edit never repeated");eq(1,e.dispatches);eq("one two",e.text);
        e=new Editor("one two",7,7);e.accept=false;check(!new CursorDeletion.Session(e).delete(),"rejected action fails closed");eq("one two",e.text);
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("x","","y",-1,-1))==null,"unknown absolute native offset rejected");
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("x","bad","y",1,2))==null,"inconsistent selected length rejected");
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("one \ud83d","","\ude00 tail",5,5))==null,"split-surrogate caret rejected");
        check(CursorDeletion.plan(new EditorInsertion.Snapshot("one \ud83d","\ude00"," tail",5,6))==null,"split-surrogate selection rejected");
        StringBuilder longWord=new StringBuilder();for(int i=0;i<800;i++)longWord.append('x');e=new Editor(longWord+" suffix",800,800);check(!new CursorDeletion.Session(e).delete(),"truncated long word is not partially guessed");eq(0,e.dispatches);
        StringBuilder prefix=new StringBuilder();for(int i=0;i<100;i++)prefix.append("a word ");String longText=prefix+"target suffix";e=new Editor(longText,prefix.length()+6,prefix.length()+6);held=new CursorDeletion.Session(e);check(held.delete(),"long field current word");check(held.delete(),"native context refill preserves held deletion");check(e.text.endsWith("a  suffix"),"long field suffix preserved");
        System.out.println("PASS: "+checks+" current-cursor word/selection deletion checks.");
    }
}
