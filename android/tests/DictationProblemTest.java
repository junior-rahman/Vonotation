package app.voiceflow;

/** Visible dictation errors: missing key and denied mic must map to a Fix action. */
public final class DictationProblemTest {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        DictationProblem p = DictationProblem.before(true, false);
        check(p != null && p.title.equals("API key required") && DictationProblem.FIX_API_KEY.equals(p.fix), "no key blocks start");
        p = DictationProblem.before(false, false);
        check(p != null && DictationProblem.FIX_MICROPHONE.equals(p.fix), "mic checked first");
        check(DictationProblem.before(true, true) == null, "ready starts");
        p = DictationProblem.of("Add your API key in Settings to use cloud transcription.");
        check(p.title.equals("API key required") && DictationProblem.FIX_API_KEY.equals(p.fix), "cloud no-key message");
        p = DictationProblem.of("Invalid API Key (401)");
        check(p.title.equals("API key rejected") && DictationProblem.FIX_API_KEY.equals(p.fix), "rejected key");
        p = DictationProblem.of("API authentication failed. Check your key and endpoint.");
        check(p.title.equals("API key rejected") && DictationProblem.FIX_API_KEY.equals(p.fix), "auth failed maps to key fix");
        p = DictationProblem.of("Allow microphone access first.");
        check(DictationProblem.FIX_MICROPHONE.equals(p.fix), "mic message");
        p = DictationProblem.of("No Android speech engine is installed. Install or enable Google Speech Services, or choose cloud transcription.");
        check(p.fix == null && p.title.equals("No Android speech engine is installed"), "generic shortened: " + p.title);
        p = DictationProblem.of(null);
        check(p.title.startsWith("Dictation failed"), "null message");
        System.out.println("PASS: 9 dictation-problem checks.");
    }
}
