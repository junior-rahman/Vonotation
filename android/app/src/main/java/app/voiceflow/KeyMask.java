package app.voiceflow;

/** Android-free helpers for showing a saved API key without revealing it. */
final class KeyMask {
    static final String DOTS = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022";
    private KeyMask() {}
    /** Last four characters only, for example "••••••••a1b2". Empty when no key is saved. */
    static String preview(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return "";
        if (k.length() <= 8) return DOTS; // too short to reveal any part safely
        return DOTS + k.substring(k.length() - 4);
    }
    /** Settings row status: "Key saved · …a1b2" or "No API key". */
    static String status(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return "No API key";
        return k.length() <= 8 ? "Key saved" : "Key saved \u00b7 \u2026" + k.substring(k.length() - 4);
    }
    /** True when the field still holds the masked preview (never treated as a new key). */
    static boolean isPreview(String fieldText) { return fieldText != null && fieldText.startsWith(DOTS); }
    /** Local format check before any request; null when the key looks usable. */
    static String formatProblem(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) return "Enter your API key.";
        if (isPreview(k) || k.indexOf('\u2022') >= 0) return "Paste the full API key, not the masked preview.";
        for (int i = 0; i < k.length(); i++) {
            char ch = k.charAt(i);
            if (ch <= ' ' || ch > '~') return "The API key contains spaces, line breaks or unsupported characters.";
        }
        if (k.length() < 16) return "That API key is too short.";
        return null;
    }
    /** Maps the provider's reply to the key check. null = accepted. */
    static String rejection(int httpCode) {
        if (httpCode == 401 || httpCode == 403) return "API key rejected (" + httpCode + "). It was not saved.";
        return null;
    }
}
