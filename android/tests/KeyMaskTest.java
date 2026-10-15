package app.voiceflow;

/** Saved-key preview: only the last 4 characters are ever shown. */
public final class KeyMaskTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        String key = "gsk_SECRETSECRETSECRETa1b2";
        String p = KeyMask.preview(key);
        check(p.equals("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022a1b2"), "preview " + p);
        check(!p.contains("SECRET") && !p.contains("gsk"), "preview hides the key");
        check(KeyMask.preview("").isEmpty() && KeyMask.preview(null).isEmpty(), "blank only without key");
        check(KeyMask.preview("short123").equals(KeyMask.DOTS), "short keys reveal nothing");
        check(KeyMask.status(key).equals("Key saved \u00b7 \u2026a1b2"), "row status " + KeyMask.status(key));
        check(KeyMask.status("").equals("No API key"), "row status none");
        check(KeyMask.isPreview(p) && !KeyMask.isPreview(key), "preview detection");
        check(KeyMask.formatProblem(p) != null, "masked preview is never saved as a key");
        check(KeyMask.formatProblem("gsk_abc def ghi jkl mno") != null, "spaces rejected");
        check(KeyMask.formatProblem("gsk_abc\ndefghijklmnop") != null, "line break rejected");
        check(KeyMask.formatProblem("abc") != null, "too short rejected");
        check(KeyMask.formatProblem(key) == null, "valid format");
        check(KeyMask.rejection(401) != null && KeyMask.rejection(403) != null, "401/403 rejected");
        check(KeyMask.rejection(200) == null && KeyMask.rejection(429) == null, "200/429 not a rejection");
        System.out.println("PASS: 14 key-mask checks.");
    }
}
