package app.voiceflow;

/** Layout-only normalization. The saved transcript always retains its original text. */
final class ThoughtLengthText {
    static String normalize(String raw){
        if(raw==null||raw.isEmpty())return "";
        StringBuilder text=new StringBuilder();boolean gap=false;
        for(int at=0;at<raw.length();){
            int cp=raw.codePointAt(at);at+=Character.charCount(cp);
            if(Character.isWhitespace(cp)||Character.isSpaceChar(cp)){gap=text.length()>0;continue;}
            // Invisible formatting must not pad a tiny transcription into a longer note.
            if((Character.getType(cp)==Character.FORMAT&&cp!=0x200c&&cp!=0x200d)||Character.isISOControl(cp))continue;
            if(gap){text.append(' ');gap=false;}
            text.appendCodePoint(cp);
        }
        return text.toString();
    }
    private ThoughtLengthText(){}
}
