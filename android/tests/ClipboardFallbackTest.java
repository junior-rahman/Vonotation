package app.voiceflow;

public final class ClipboardFallbackTest {
    public static void main(String[] args){
        for(int mask=0;mask<16;mask++){
            boolean lost=(mask&1)!=0,inserted=(mask&2)!=0,secret=(mask&4)!=0,copied=(mask&8)!=0;
            if(ClipboardFallback.shouldReveal(lost,inserted,secret,copied)!=(mask==9))throw new AssertionError("Unexpected fan reveal for "+mask);
        }
        ClipboardFallback.Pending pending=new ClipboardFallback.Pending();
        check(pending.peek(1,true,false)==null,"No fallback was queued");
        pending.offer("Saved transcript",7);
        check(pending.peek(7,true,false)==null,"Completion/render opened the preview immediately");
        check(pending.peek(8,false,false)==null,"An unknown or missing input opened the preview");
        check(pending.peek(8,true,true)==null,"A private/locked/busy input consumed the preview");
        check(pending.available(),"A deferred preview was lost");
        check("Saved transcript".equals(pending.peek(9,true,false)),"A later usable input did not reveal the transcript");
        check(pending.available(),"Showing the preview consumed it before paste");
        check(pending.peek(9,false,false)==null,"Missing field did not hide the preview");
        check("Saved transcript".equals(pending.peek(9,true,false)),"Transient focus loss discarded the unpasted text");
        check(pending.peek(10,true,true)==null,"Private field did not suppress the preview");
        check("Saved transcript".equals(pending.peek(11,true,false)),"Returning from a private field lost the copy");
        long first=pending.generation();pending.complete(first);
        check(pending.peek(12,true,false)==null,"Dismissed or pasted preview reopened");
        pending.offer("Older",10);pending.offer("Newest",11);pending.offer(" ",12);
        check(pending.peek(11,true,false)==null,"Replacing a fallback bypassed the activation gate");
        pending.complete(first);
        check("Newest".equals(pending.peek(12,true,false)),"A stale paste/dismiss callback cleared a newer fallback");
        long latest=pending.generation();pending.offer("Newest",12);pending.complete(latest);
        check("Newest".equals(pending.peek(13,true,false)),"An identical newer copy was consumed by an old callback");
        pending.complete(pending.generation());check(!pending.available(),"Explicit completion did not consume the copy");
        System.out.println("PASS: lost-input copies wait for a later usable input, survive display/focus/private/busy interruptions, and clear only on explicit completion; stale callbacks preserve newer copies");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
