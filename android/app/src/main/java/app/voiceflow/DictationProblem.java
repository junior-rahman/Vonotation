package app.voiceflow;

import java.util.Locale;

/**
 * Turns a dictation failure into a short, visible message and the setting that fixes it.
 * Android-free so it can be unit tested. Shown in the bubble or keyboard controls instead of
 * a toast: Android 13+ suppresses toasts from a background service when notifications are off.
 */
final class DictationProblem {
    static final String FIX_API_KEY = "api_key";
    static final String FIX_MICROPHONE = "microphone";
    static final String API_KEY_REQUIRED = "API key required";
    static final String MICROPHONE_REQUIRED = "Microphone access required";

    final String title;
    /** FIX_API_KEY, FIX_MICROPHONE, or null when no setting can fix it. */
    final String fix;
    final String detail;

    private DictationProblem(String title, String fix, String detail) { this.title = title; this.fix = fix; this.detail = detail; }

    /** Preflight: checked before the microphone or recording notification is started. */
    static DictationProblem before(boolean microphoneAllowed, boolean apiKeySaved) {
        if (!microphoneAllowed) return new DictationProblem(MICROPHONE_REQUIRED, FIX_MICROPHONE, "Allow microphone access for Voitation.");
        if (!apiKeySaved) return new DictationProblem(API_KEY_REQUIRED, FIX_API_KEY, "Add your API key in Settings → API key & models.");
        return null;
    }

    /** Maps an error message from Dictation/CloudApi to a short title and fix. */
    static DictationProblem of(String message) {
        String full = message == null || message.trim().isEmpty() ? "Dictation failed. Please try again." : message.trim();
        String m = full.toLowerCase(Locale.ROOT);
        if (m.contains("api key") && (m.contains("add") || m.contains("missing") || m.contains("required")))
            return new DictationProblem(API_KEY_REQUIRED, FIX_API_KEY, full);
        if (m.contains("invalid api key") || m.contains("authentication failed") || m.contains("unauthorized") || m.contains("401") || m.contains("api key was rejected") || m.contains("incorrect api key"))
            return new DictationProblem("API key rejected", FIX_API_KEY, full);
        if (m.contains("microphone access") || m.contains("allow microphone") || m.contains("microphone permission"))
            return new DictationProblem(MICROPHONE_REQUIRED, FIX_MICROPHONE, full);
        if (m.contains("could not start the microphone") || m.contains("could not start microphone") || m.contains("microphone recording"))
            return new DictationProblem("Microphone unavailable", null, full);
        String title = full;
        int stop = title.indexOf(". ");
        if (stop > 0) title = title.substring(0, stop);
        if (title.endsWith(".")) title = title.substring(0, title.length() - 1);
        if (title.length() > 60) title = title.substring(0, 57).trim() + "…";
        return new DictationProblem(title, null, full);
    }
}
