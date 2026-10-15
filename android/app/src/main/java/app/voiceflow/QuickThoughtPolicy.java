package app.voiceflow;

/** Pure rules for opt-in thought capture. No commands, translation or AI replies. */
final class QuickThoughtPolicy {
    static final long IDLE_MS=60_000L;
    static boolean visible(boolean enabled,boolean interactive,boolean locked,boolean appOpen,boolean captureOpen,boolean inputActive,boolean recording){
        return enabled && interactive && !locked && !appOpen && !captureOpen && !inputActive && !recording;
    }
    static boolean collapsed(long now,long lastInteraction,boolean working){
        return !working && (lastInteraction<=0 || now-lastInteraction>=IDLE_MS);
    }
    /** Reject an answer, translation, missing words or fabricated details from formatting. */
    static String formatted(String raw,String candidate){
        String source=raw==null?"":raw.trim();
        return candidate!=null && TranscriptGuard.accepts(source,source,candidate)?candidate.trim():source;
    }
    static boolean canApply(String raw,long revision,boolean exists,boolean thought,String currentRaw,long currentRevision){
        return exists && thought && revision==currentRevision && raw!=null && raw.equals(currentRaw);
    }
    /** An extractive title cannot introduce a promise, reminder or fact the speaker never said. */
    static String title(String text,String fallback){
        String line=text==null?"":text.replaceAll("\\s+"," ").trim();
        if(line.isEmpty())return fallback;
        int end=line.length();
        for(int i=0;i<line.length();i++){char c=line.charAt(i);if(c=='.'||c=='!'||c=='?'||c=='।'){end=i+1;break;}}
        line=line.substring(0,end);
        String[] words=line.split(" ");
        if(words.length>8){StringBuilder shortTitle=new StringBuilder();for(int i=0;i<8;i++){if(i>0)shortTitle.append(' ');shortTitle.append(words[i]);}line=shortTitle.toString()+"…";}
        if(line.length()>72){int cut=line.offsetByCodePoints(0,Math.min(68,line.codePointCount(0,line.length())));line=line.substring(0,cut).trim()+"…";}
        return line;
    }
    private QuickThoughtPolicy(){}
}
