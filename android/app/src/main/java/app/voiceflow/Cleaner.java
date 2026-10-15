package app.voiceflow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic cleanup. It intentionally does not guess at self-corrections. */
public final class Cleaner {
    private Cleaner() {}
    private static final String[][] COMMANDS = {{"new paragraph", "\n\n"}, {"new line", "\n"}, {"question mark", "?"}, {"exclamation mark", "!"}, {"exclamation point", "!"}, {"full stop", "."}, {"comma", ","}, {"semicolon", ";"}, {"colon", ":"}};
    /** A preceding word that makes the punctuation name an ordinary noun ("the colon", "an Oxford comma"). */
    private static final Set<String> NOUN_BEFORE = new HashSet<>(Arrays.asList(
            "a","an","the","this","that","these","those","my","your","his","her","its","our","their","whose","which","what",
            "no","one","each","every","any","another","per","of","oxford","serial","sigmoid","ascending","descending","transverse",
            "spastic","healthy","inverted","spoken","word","words","literal","say","says","said","saying","type","typed","typing","called","named"));
    /** A following word that shows the punctuation name is being talked about, not dictated. */
    private static final Set<String> NOUN_AFTER = new HashSet<>(Arrays.asList(
            "is","was","are","were","isn't","wasn't","has","had","cancer","surgery","polyp","polyps","exam","screening","cleanse",
            "splice","splices","key","sign","symbol","rule","rules","placement","usage","goes","went","means","character","button"));
    private static final Pattern WORD_BEFORE = Pattern.compile("([\\p{L}\\p{N}'’]+)[^\\p{L}\\p{N}\\n]*$");
    private static final Pattern WORD_AFTER = Pattern.compile("^[^\\p{L}\\p{N}\\n]*([\\p{L}\\p{N}'’]+)");

    /** Backward-compatible cleanup with spoken punctuation commands enabled. */
    public static String clean(String raw, boolean enabled, String dictionary) { return clean(raw, enabled, true, dictionary); }

    public static String clean(String raw, boolean enabled, boolean punctuationCommands, String dictionary) {
        if (raw == null) return "";
        String text = raw.trim();
        if (enabled) {
            text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])(?:u+m+|u+h+|erm+)(?![\\p{L}\\p{N}])[, ]*", "");
            text = punctuationCommands ? commands(text) : text;
            text = text.replaceAll("[ \\t]+", " ").replaceAll(" +([,.;:!?])", "$1").replaceAll(" *\\n *", "\n").trim();
            text = text.replaceAll("([,;:!?])(?=[\\p{L}])", "$1 ");
            if (!text.isEmpty()) {
                int first = text.codePointAt(0);
                text = new String(Character.toChars(Character.toUpperCase(first))) + text.substring(Character.charCount(first));
            }
        }
        if (dictionary != null) for (String line : dictionary.split("\\n")) {
            int at = line.indexOf('=');
            if (at < 1) continue;
            String from = line.substring(0, at).trim(), to = line.substring(at + 1).trim();
            if (!from.isEmpty() && !to.isEmpty()) text = text.replaceAll("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(from) + "(?![\\p{L}\\p{N}])", Matcher.quoteReplacement(to));
        }
        return text;
    }

    /** True verbatim: the recognizer's text, untouched, including leading/trailing and inner whitespace. */
    public static String verbatim(String raw) { return raw == null ? "" : raw; }

    /**
     * Converts a spoken punctuation name only when it is used as a dictation command.
     * "literal comma" keeps the word. A determiner/possessive before it, or a verb such
     * as "is" after it, marks ordinary speech ("the colon is inflamed").
     */
    static String commands(String text) {
        for (String[] command : COMMANDS) {
            Pattern pattern = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(literal\\s+)?" + command[0].replace(" ", "\\s+") + "(?![\\p{L}\\p{N}])");
            Matcher matcher = pattern.matcher(text);
            StringBuffer out = new StringBuffer();
            while (matcher.find()) {
                String spoken = matcher.group().substring(matcher.group(1) == null ? 0 : matcher.group(1).length());
                String replacement;
                if (matcher.group(1) != null) replacement = spoken;
                else if (ordinarySpeech(text.substring(0, matcher.start()), text.substring(matcher.end()))) replacement = matcher.group();
                else replacement = command[1];
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            text = out.toString();
        }
        return text;
    }
    static boolean ordinarySpeech(String before, String after) {
        Matcher previous = WORD_BEFORE.matcher(before), next = WORD_AFTER.matcher(after);
        if (previous.find() && NOUN_BEFORE.contains(previous.group(1).toLowerCase(Locale.ROOT).replace('’', '\''))) return true;
        return next.find() && NOUN_AFTER.contains(next.group(1).toLowerCase(Locale.ROOT).replace('’', '\''));
    }
    public static int words(String text) { return text == null || text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length; }
    public static String languageCode(String locale) { return locale.split("[-_]")[0].toLowerCase(Locale.ROOT); }
}
