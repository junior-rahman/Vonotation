package app.voiceflow;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public final class RecordingTransferPolicyTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static void rejected(Attempt attempt, String message) throws Exception {
        try { attempt.run(); throw new AssertionError(message); }
        catch (IOException expected) { checks++; }
    }
    interface Attempt { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        check(RecordingTransferPolicy.audioName("1792220000000-part-1.m4a"), "recording source accepted");
        for (String name : new String[]{"../escape.m4a", "x/part.m4a", "x\\part.m4a", "/root.m4a", "..m4a", "x..m4a", "", "token.json", "a\u0000.m4a"})
            check(!RecordingTransferPolicy.audioName(name), "unsafe source: " + name);
        check(RecordingTransferPolicy.archivePath("recordings.json"), "manifest accepted");
        check(RecordingTransferPolicy.archivePath("audio/1792.m4a"), "audio accepted");
        for (String name : new String[]{"audio/../recordings.json", "audio//1.m4a", "api_key", "settings.json", "audio/", "audio/one/two.m4a", "audio\\1.m4a", "dictations.json", "clipboard.json"})
            check(!RecordingTransferPolicy.archivePath(name), "unsupported archive path: " + name);
        Set<Long> ids = new HashSet<>(); ids.add(10L); ids.add(11L);
        check(RecordingTransferPolicy.nextId(ids, 10) == 12, "collision resolution preserves existing IDs");
        check(RecordingTransferPolicy.nextId(ids, 10) == 13, "consecutive imports never collide");
        check(RecordingTransferPolicy.nextId(ids, -4) == 1, "positive IDs required");
        ids.add(Long.MAX_VALUE);
        rejected(() -> RecordingTransferPolicy.nextId(ids, Long.MAX_VALUE), "overflow must fail");
        RecordingTransferPolicy.Budget budget = new RecordingTransferPolicy.Budget();
        budget.begin("recordings.json"); budget.add((int)RecordingTransferPolicy.MAX_MANIFEST);
        rejected(() -> budget.add(1), "manifest boundary");
        budget.begin("audio/first.m4a"); budget.add((int)RecordingTransferPolicy.MAX_AUDIO);
        rejected(() -> budget.add(1), "audio boundary");
        rejected(() -> budget.begin("audio/first.m4a"), "duplicate entries");
        rejected(() -> budget.begin("audio/../../escape.m4a"), "zip slip");
        RecordingTransferPolicy.Budget combined = new RecordingTransferPolicy.Budget();
        combined.begin("audio/one.m4a"); combined.add((int)RecordingTransferPolicy.MAX_AUDIO);
        combined.begin("audio/two.m4a"); combined.add((int)RecordingTransferPolicy.MAX_AUDIO);
        check(combined.total() == RecordingTransferPolicy.MAX_TOTAL, "total boundary accepted");
        combined.begin("audio/three.m4a"); rejected(() -> combined.add(1), "decompressed total is bounded");
        RecordingTransferPolicy.Budget count = new RecordingTransferPolicy.Budget();
        for (int i=0;i<RecordingTransferPolicy.MAX_PARTS;i++) count.begin("audio/"+i+".m4a");
        count.begin("recordings.json");
        rejected(() -> count.begin("audio/extra.m4a"), "entry count bounded independently of bytes");
        System.out.println("PASS: " + checks + " recording-transfer boundary, path, exclusion and collision checks.");
    }
}
