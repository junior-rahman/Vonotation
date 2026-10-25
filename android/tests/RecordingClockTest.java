package app.voiceflow;
public final class RecordingClockTest {
    public static void main(String[] args){
        RecordingClock c=new RecordingClock();assert c.elapsed(50)==0;c.start(100);assert c.elapsed(600)==500;
        c.pause(600);c.pause(900);assert c.elapsed(1600)==500;c.resume(2600);c.resume(2700);assert c.elapsed(3100)==1000;
        c.pause(3200);assert c.elapsed(9000)==1100;c.resume(9300);assert c.elapsed(9400)==1200;
        c.start(10000);assert c.elapsed(10100)==100;System.out.println("PASS");
    }
}
