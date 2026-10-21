package app.voiceflow;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Bounded, allowlisted recording-transfer paths. No settings or credentials belong here. */
final class RecordingTransferPolicy {
    static final int MAX_RECORDINGS = 1000, MAX_PARTS = 4096;
    static final long MAX_MANIFEST = 8L * 1024 * 1024;
    static final long MAX_AUDIO = 256L * 1024 * 1024;
    static final long MAX_TOTAL = 512L * 1024 * 1024;
    static final String MANIFEST = "recordings.json";

    static boolean audioName(String name) {
        return name != null && name.length() <= 160
            && name.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.m4a")
            && !name.contains("..");
    }
    static boolean archivePath(String name) {
        return MANIFEST.equals(name) || (name != null && name.startsWith("audio/")
            && audioName(name.substring(6)));
    }
    static long nextId(Set<Long> used, long candidate) throws IOException {
        long id = Math.max(1L, candidate);
        while (used.contains(id)) {
            if (id == Long.MAX_VALUE) throw new IOException("No recording identifier is available.");
            id++;
        }
        used.add(id);
        return id;
    }
    static final class Budget {
        private final Set<String> names = new HashSet<>();
        private long total, entryBytes, entryLimit;
        void begin(String name) throws IOException {
            if (!archivePath(name) || !names.add(name) || names.size() > MAX_PARTS + 1)
                throw new IOException("The recording transfer contains an unsupported or duplicate file.");
            entryBytes = 0;
            entryLimit = MANIFEST.equals(name) ? MAX_MANIFEST : MAX_AUDIO;
        }
        void add(int bytes) throws IOException {
            if (bytes < 0 || bytes > entryLimit - entryBytes || bytes > MAX_TOTAL - total)
                throw new IOException("The recording transfer is too large.");
            entryBytes += bytes;
            total += bytes;
        }
        long total() { return total; }
    }
}
