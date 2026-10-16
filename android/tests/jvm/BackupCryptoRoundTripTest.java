package app.voiceflow;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Runs real production encryption and JSON allocation bounds without Android services. */
public final class BackupCryptoRoundTripTest {
    public static void main(String[] args) throws Exception {
        char[] password="synthetic archive passphrase".toCharArray();
        try {
            roundTrip(300,100,password);
            roundTrip(BackupJsonBounds.MAX_THOUGHTS,BackupJsonBounds.MAX_HISTORY,password);
            byte[] encoded=BackupArchive.encodeUtf8("{\"text\":\"Unicode বাংলা 😀\"}".getBytes(StandardCharsets.UTF_8),password);
            byte[] changed=encoded.clone();changed[changed.length-1]^=1;
            reject(()->BackupArchive.decodeUtf8(changed,password),"Changed archive authenticated");
            reject(()->BackupArchive.encodeUtf8(new byte[]{(byte)0xc3,(byte)0x28},password),"Malformed UTF-8 was encoded");
            reject(()->BackupArchive.encodeUtf8("{} {}".getBytes(StandardCharsets.UTF_8),password),"Unparseable trailing JSON was encoded");
            StringBuilder broad=new StringBuilder("{\"values\":[0");for(int i=0;i<90000;i++)broad.append(",0");broad.append("]}");
            reject(()->BackupArchive.encodeUtf8(broad.toString().getBytes(StandardCharsets.UTF_8),password),"Unreadable complexity was encoded");
            System.out.println("PASS: actual encrypted 300/10,000-thought plus 100-dictation round-trips, matching read/write bounds, Unicode, tamper rejection and pre-write UTF-8/complexity checks; no Android schema parser, Drive or device execution");
        } finally {Arrays.fill(password,'\0');}
    }
    private static void roundTrip(int thoughts,int history,char[] password) throws Exception {
        byte[] original=BackupJsonBoundsTest.archive(thoughts,history).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted=BackupArchive.encodeUtf8(original,password);
        byte[] decoded=BackupArchive.decodeUtf8(encrypted,password);
        if(!Arrays.equals(original,decoded))throw new AssertionError("Encrypted archive bytes changed");
        BackupJsonBounds.check(new String(decoded,StandardCharsets.UTF_8));
    }
    private static void reject(Task task,String message) throws Exception {try{task.run();}catch(java.io.IOException expected){return;}throw new AssertionError(message);}
    private interface Task{void run() throws Exception;}
}
