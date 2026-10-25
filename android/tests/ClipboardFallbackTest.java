package app.voiceflow;

public final class ClipboardFallbackTest {
    public static void main(String[] args){
        for(int mask=0;mask<16;mask++){
            boolean lost=(mask&1)!=0,inserted=(mask&2)!=0,secret=(mask&4)!=0,copied=(mask&8)!=0;
            if(ClipboardFallback.shouldReveal(lost,inserted,secret,copied)!=(mask==9))throw new AssertionError("Unexpected fan reveal for "+mask);
        }
        ClipboardFallback.Pending pending=new ClipboardFallback.Pending();
        check(pending.take(1,true,false)==null,"No fallback was queued");
        pending.offer("Saved transcript",7);
        check(pending.take(7,true,false)==null,"Completion/render opened the preview immediately");
        check(pending.take(8,false,false)==null,"An unknown or missing input opened the preview");
        check(pending.take(8,true,true)==null,"A private/locked/busy input consumed the preview");
        check(pending.available(),"A deferred preview was lost");
        check("Saved transcript".equals(pending.take(9,true,false)),"A later usable input did not reveal the transcript");
        check(pending.take(10,true,false)==null,"A repeated focus event reopened the preview");
        pending.offer("Older",10);pending.offer("Newest",11);pending.offer(" ",12);
        check(pending.take(11,true,false)==null,"Replacing a fallback bypassed the activation gate");
        check("Newest".equals(pending.take(12,true,false)),"Latest fallback text was not retained exactly");
        System.out.println("PASS: lost-input copies wait for a later usable input; no immediate/duplicate popup; private, absent and busy inputs defer; ordinary/failed copies do not trigger");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
