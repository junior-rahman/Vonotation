package app.voiceflow;

public final class ClipboardPasteTest {
    static final class Editor implements EditorInsertion.Port {
        String text="one two tail",queued;int a=7,b=7,sends;boolean current=true,delayed=true,throwAfterSend;
        public boolean current(){return current;}
        public EditorInsertion.Snapshot read(){int lo=Math.min(a,b),hi=Math.max(a,b);return new EditorInsertion.Snapshot(text.substring(Math.max(0,lo-512),lo),text.substring(lo,hi),text.substring(hi,Math.min(text.length(),hi+512)),a,b);}
        public boolean replace(int start,int end,int n,String exact){sends++;queued=text.substring(0,start)+exact+text.substring(end);if(!delayed){text=queued;a=b=start+exact.length();}if(throwAfterSend)throw new IllegalStateException("remote result lost");return true;}
        void apply(int cursor){text=queued;a=b=cursor;}
    }
    static void check(boolean okay){if(!okay)throw new AssertionError();}
    public static void main(String[] ignored){
        long[] time={0};Editor editor=new Editor();ClipboardPaste paste=new ClipboardPaste(editor,editor.read(),"COPY",()->time[0]);check(paste.dispatch());check(paste.check()==ClipboardPaste.PENDING);time[0]=150;check(paste.dispatch()&&editor.sends==1);check(paste.check()==ClipboardPaste.PENDING);editor.apply(11);check(paste.check()==ClipboardPaste.APPLIED&&editor.text.equals("one twoCOPY tail"));
        editor=new Editor();paste=new ClipboardPaste(editor,editor.read(),"COPY",()->time[0]);check(paste.dispatch());editor.text=editor.queued;editor.a=editor.b=editor.text.length();check(paste.check()==ClipboardPaste.PENDING);editor.a=editor.b=11;check(paste.check()==ClipboardPaste.APPLIED&&editor.sends==1);
        editor=new Editor();paste=new ClipboardPaste(editor,editor.read(),"COPY",()->time[0]);check(paste.dispatch());time[0]+=EditorInsertion.ACK_MS;check(paste.check()==ClipboardPaste.FAILED);check(!paste.dispatch()&&editor.sends==1);
        editor=new Editor();paste=new ClipboardPaste(editor,editor.read(),"COPY",()->time[0]);check(paste.dispatch());editor.a=editor.b=0;check(paste.check()==ClipboardPaste.FAILED&&editor.sends==1);
        editor=new Editor();editor.throwAfterSend=true;paste=new ClipboardPaste(editor,editor.read(),"COPY",()->time[0]);check(paste.dispatch());editor.apply(11);check(paste.check()==ClipboardPaste.APPLIED&&editor.sends==1);
        editor=new Editor();EditorInsertion.Snapshot original=editor.read();editor.a=editor.b=0;paste=new ClipboardPaste(editor,original,"COPY",()->time[0]);check(!paste.dispatch()&&editor.sends==0);
        System.out.println("PASS: exact paste dispatch once, delayed text/caret acknowledgement, timeout, cursor change, uncertain remote result and stale target guards");
    }
}
