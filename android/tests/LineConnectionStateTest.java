package app.voiceflow;

public final class LineConnectionStateTest {
    private static int checks;
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    public static void main(String[] args){
        LineConnectionState line=new LineConnectionState();
        check(line.select("192.168.1.8",37123),"first local endpoint selected");
        check(line.checked("192.168.1.8",37123,"ok")==LineConnectionState.Result.READY,"saved identity authenticates");
        check(!line.select("192.168.1.8",37123),"reannouncement keeps same endpoint");
        check(line.checked("192.168.1.8",37123,"auth")==LineConnectionState.Result.RETRY_AUTH,"one rejected handshake is provisional");
        check(line.checked("192.168.1.8",37123,"down")==LineConnectionState.Result.UNAVAILABLE,"network outage never requires pairing");
        check(line.checked("192.168.1.8",37123,"auth")==LineConnectionState.Result.RETRY_AUTH,"timeout broke consecutive auth rejection");
        check(line.select("192.168.1.8",39876),"wireless debugging rotates port");
        check(line.checked("192.168.1.8",37123,"auth")==LineConnectionState.Result.STALE,"late rejected old port does not disconnect new port");
        check(line.checked("192.168.1.8",37123,"ok")==LineConnectionState.Result.STALE,"late successful old port cannot prove new port");
        check(line.checked("192.168.1.9",39876,"auth")==LineConnectionState.Result.STALE,"same port on different host is ignored");
        check(line.checked("192.168.1.8",39876,"auth")==LineConnectionState.Result.RETRY_AUTH,"rotated port starts fresh rejection count");
        check(line.checked("192.168.1.8",39876,"auth")==LineConnectionState.Result.RETRY_AUTH,"second rejection still provisional");
        check(line.checked("192.168.1.8",39876,"auth")==LineConnectionState.Result.REPAIR,"three consecutive rejected keys require pairing");
        check(line.checked("192.168.1.8",39876,"ok")==LineConnectionState.Result.READY,"successful automatic reconnection recovers");
        check(line.checked("192.168.1.8",39876,"auth")==LineConnectionState.Result.RETRY_AUTH,"success cleared rejection counter");
        line.clear();
        check(line.checked("192.168.1.8",39876,"ok")==LineConnectionState.Result.STALE,"disabled or unavailable state rejects outstanding probe");
        check(line.select("127.0.0.1",39876),"local loopback endpoint can be selected again");
        System.out.println("PASS: "+checks+" connection recovery checks.");
    }
}
