package app.voiceflow;

public final class CaptureGateTest {
    public static void main(String[] args)throws Exception{
        CaptureGate gate=new CaptureGate();
        CaptureGate.Lease first=gate.begin(),second=gate.begin();
        assert first!=null&&second!=null;
        assert !gate.reserveInstall():"An open source must block installation";
        first.close();first.close();
        assert !gate.reserveInstall():"Closing twice must not release another capture";
        second.close();assert gate.reserveInstall();
        assert gate.begin()==null:"Confirmation must prevent new recording";
        assert gate.reserveInstall():"Continuing the same installation keeps its reservation";
        gate.releaseInstall();CaptureGate.Lease next=gate.begin();assert next!=null;next.close();
        for(int i=0;i<250;i++){
            CaptureGate racing=new CaptureGate();
            java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
            CaptureGate.Lease[] lease=new CaptureGate.Lease[1];boolean[] installed=new boolean[1];
            Thread audio=new Thread(()->{await(start);lease[0]=racing.begin();});
            Thread install=new Thread(()->{await(start);installed[0]=racing.reserveInstall();});
            audio.start();install.start();start.countDown();audio.join();install.join();
            assert (lease[0]!=null)^installed[0]:"Capture and install cannot both acquire ownership";
            if(lease[0]!=null)lease[0].close();racing.releaseInstall();
        }
        System.out.println("PASS: capture ownership, idempotent release and 250 concurrent install/start races");
    }
    private static void await(java.util.concurrent.CountDownLatch latch){try{latch.await();}catch(InterruptedException e){throw new AssertionError(e);}}
}
