package app.voiceflow;

/** Pure text insertion rules, shared by keyboard and bubble. */
public final class TextInsertion {
    private TextInsertion() {}
    public static String addition(String before,String text,String after) {
        String prefix=separate(before,text) ? " " : "";
        String suffix=separate(text,after) ? " " : "";
        return prefix+text+suffix;
    }
    private static boolean separate(String left,String right) {
        if(left.isEmpty() || right.isEmpty() || !word(right.codePointAt(0))) return false;
        int last=left.codePointBefore(left.length());
        if(word(last)) return true;
        int type=Character.getType(last);
        if(type==Character.END_PUNCTUATION || type==Character.FINAL_QUOTE_PUNCTUATION) return true;
        switch(last) {
            case '.': case ',': case '!': case '?': case ':': case ';': case 0x2026:
            case 0x3002: case 0x3001: case 0xFF0E: case 0xFF0C: case 0xFF01: case 0xFF1F: case 0xFF1A: case 0xFF1B:
            case 0x060C: case 0x061B: case 0x061F: case 0x0964: case 0x0965:
                return true;
            case '"':
                // A balanced pair closes a quotation; a lone quote opens one.
                int quotes=0;
                for(int i=0;i<left.length();i++) if(left.charAt(i)=='"') quotes++;
                return quotes%2==0;
            default:return false;
        }
    }
    private static boolean word(int codePoint) { int type=Character.getType(codePoint); return Character.isLetterOrDigit(codePoint) || type==Character.NON_SPACING_MARK || type==Character.COMBINING_SPACING_MARK; }
}
