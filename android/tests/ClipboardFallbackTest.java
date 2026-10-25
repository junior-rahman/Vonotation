package app.voiceflow;

public final class ClipboardFallbackTest {
    public static void main(String[] args){
        for(int mask=0;mask<16;mask++){
            boolean lost=(mask&1)!=0,inserted=(mask&2)!=0,secret=(mask&4)!=0,copied=(mask&8)!=0;
            if(ClipboardFallback.shouldReveal(lost,inserted,secret,copied)!=(mask==9))throw new AssertionError("Unexpected fan reveal for "+mask);
        }
        System.out.println("PASS: only successful lost-input fallback reveals; ordinary copies, inserted text, private text and failed copies do not");
    }
}
