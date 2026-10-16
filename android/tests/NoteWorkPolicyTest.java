package app.voiceflow;
public final class NoteWorkPolicyTest {
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        String captured="id=4;revision=2;raw=first;parts=a,b";
        check(NoteWorkPolicy.canApply(captured,captured),"unchanged result can save");
        check(!NoteWorkPolicy.canApply(captured,null),"deleted recording must never be recreated");
        check(!NoteWorkPolicy.canApply(captured,"id=4;revision=3;raw=edited;parts=a,b"),"edit supersedes an in-flight result");
        check(!NoteWorkPolicy.canApply(captured,"id=4;revision=2;raw=first;parts=c"),"restored source supersedes old request");
        long now=1000,failedUntil=now+NoteWorkPolicy.retryDelay(1);
        check(!NoteWorkPolicy.eligible(true,false,failedUntil,now),"failed head yields during cooldown");
        check(NoteWorkPolicy.eligible(true,false,0,now),"healthy later item remains eligible");
        check(NoteWorkPolicy.eligible(true,false,failedUntil,failedUntil),"failed item retries after cooldown");
        check(!NoteWorkPolicy.eligible(true,true,0,now),"never transcribe a live source");
        check(!NoteWorkPolicy.eligible(false,false,0,now),"complete items stay out of drain");
        check(NoteWorkPolicy.retryDelay(20)==NoteWorkPolicy.retryDelay(7),"persistent failures have bounded backoff");
        System.out.println("PASS: 10 note work reliability checks.");
    }
}
