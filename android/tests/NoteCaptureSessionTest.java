package app.voiceflow;

/** Foreground ownership must survive finalization and reject stale notification actions. */
public final class NoteCaptureSessionTest {
    private static int checks;
    private static void check(boolean condition,String what){checks++;if(!condition)throw new AssertionError(what);}
    public static void main(String[] args)throws Exception {
        NoteCaptureSession session=new NoteCaptureSession();
        check(!session.begin(0)&&!session.begin(-1),"invalid capture ids rejected");
        check(session.begin(10)&&session.busy()&&session.owns(10),"capture claims service before recorder starts");
        check(!session.begin(11)&&!session.ready(11),"other session cannot start or mark recorder ready");
        check(session.ready(10)&&!session.ready(10),"readiness dispatch runs only once");
        check(session.finalizing(10)&&session.busy(),"foreground owner remains during source finalization");
        check(!session.begin(11)&&!session.release(9),"saving blocks new capture and stale completion");
        check(session.release(10)&&!session.busy(),"completed source releases its own service");
        check(session.begin(11)&&session.ready(11),"new recording can start after prior finalization");
        check(!session.release(10)&&!session.finalizing(10)&&session.owns(11),"late finish or cancel cannot affect newer capture");
        check(session.finalizing(11)&&session.release(11),"cancel finalization releases exact session");
        check(session.begin(12)&&session.release(12)&&!session.ready(12),"startup failure prevents delayed recording");
        final NoteCaptureSession race=new NoteCaptureSession();
        final java.util.concurrent.atomic.AtomicInteger winners=new java.util.concurrent.atomic.AtomicInteger();
        Thread[] threads=new Thread[100];
        for(int i=0;i<threads.length;i++){final int id=100+i;threads[i]=new Thread(()->{if(race.begin(id))winners.incrementAndGet();});threads[i].start();}
        for(Thread thread:threads)thread.join();
        check(winners.get()==1&&race.busy(),"concurrent starts claim only one microphone foreground session");
        System.out.println("PASS: "+checks+" note capture service ownership checks.");
    }
}
