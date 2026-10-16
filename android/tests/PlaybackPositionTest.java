package app.voiceflow;
public final class PlaybackPositionTest {
    static void check(long[] spans,long target,int segment,long offset,long before){PlaybackPosition p=PlaybackPosition.locate(spans,target);if(p.index!=segment||p.offset!=offset||p.before!=before)throw new AssertionError("Wrong recording seek for "+target);}
    public static void main(String[] args){
        long[] spans={180000,180000,27000};
        check(spans,178000,0,178000,0);check(spans,180000,1,0,180000);check(spans,183000,1,3000,180000);
        check(spans,360000,2,0,360000);check(spans,-3000,0,0,0);check(spans,999999,2,26999,360000);
        check(new long[]{0,2000},500,1,500,0);check(new long[0],500,0,0,0);
        System.out.println("PASS: 8 whole-recording seek checks.");
    }
}
