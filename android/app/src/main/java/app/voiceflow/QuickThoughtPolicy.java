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
    static final class Organized {
        final String title,body;
        final boolean accepted;
        Organized(String title,String body,boolean accepted){this.title=title;this.body=body;this.accepted=accepted;}
    }
    /** A malformed or unfaithful response never replaces the complete original. */
    static Organized organizeReply(String raw,String response){
        try{
            String[] fields=new ReplyReader(response).read();
            return organized(raw,fields[0],fields[1]);
        }catch(IllegalArgumentException invalid){return fallback(raw);}
    }
    static Organized organized(String raw,String proposedTitle,String proposedBody){
        String source=raw==null?"":raw.trim();
        if(!bodyAccepted(source,proposedBody))return fallback(raw);
        String title=titleAccepted(source,proposedTitle)?proposedTitle.trim():title(source,"Quick thought");
        return new Organized(title,proposedBody.trim(),true);
    }
    private static Organized fallback(String raw){String source=raw==null?"":raw.trim();return new Organized(title(source,"Quick thought"),source,false);}
    /** Same spoken word order; paragraphs and plain Unicode bullets may add structure. */
    static boolean bodyAccepted(String raw,String candidate){
        if(raw==null || candidate==null || candidate.trim().isEmpty() || candidate.length()>raw.length()*4L+1024)return false;
        String source=stripBullets(raw),body=stripBullets(candidate);
        return TranscriptGuard.accepts(source,source,body) && spacedSigns(source).equals(spacedSigns(body)) && extraSymbols(source).equals(extraSymbols(body));
    }
    private static String extraSymbols(String text){StringBuilder out=new StringBuilder();for(int at=0;at<text.length();){int cp=text.codePointAt(at);at+=Character.charCount(cp);int type=Character.getType(cp);if(type==Character.OTHER_SYMBOL || type==Character.MODIFIER_SYMBOL || type==Character.SURROGATE || type==Character.FORMAT || (Character.isISOControl(cp) && cp!='\n' && cp!='\r' && cp!='\t'))out.appendCodePoint(cp);}return out.toString();}
    static String formatted(String raw,String candidate){return bodyAccepted(raw,candidate)?candidate.trim():raw==null?"":raw.trim();}
    // Only • followed by whitespace is layout. Minus signs, asterisks, # and numbered lists remain data.
    private static String stripBullets(String value){return value.replaceAll("(?m)^[ \t]*•[ \t]+","");}
    private static java.util.List<String> spacedSigns(String text){
        java.util.ArrayList<String> signs=new java.util.ArrayList<>();
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("([+\\-−–])\\s+(?=[\\p{Sc}]?\\p{N})").matcher(text);
        while(matcher.find())signs.add(matcher.group(1).replace('−','-').replace('–','-'));
        return signs;
    }
    static boolean hasUserEdits(String original,String edited){return !(original==null?"":original.trim()).equals(edited==null?"":edited.trim());}
    static boolean canApply(String raw,long revision,boolean exists,boolean thought,String currentRaw,long currentRevision){
        return exists && thought && revision==currentRevision && raw!=null && raw.equals(currentRaw);
    }
    private static final java.util.regex.Pattern WORD=java.util.regex.Pattern.compile("[\\p{L}\\p{M}\\p{N}]+(?:['’][\\p{L}\\p{M}\\p{N}]+)*");
    private static final String FILLER=" i me my we our you your a an the this that these those and or to of for on in at is are was were be been being have has had do does did it its just really very some something thing thought thoughts thinking think about wanted want write note quick then so with as ";
    private static final String PROTECTED=" no not never cannot can't don't doesn't didn't won't wouldn't shouldn't couldn't isn't aren't wasn't weren't haven't hasn't hadn't mustn't needn't shan't ain't mightn't oughtn't maybe may might can could would should perhaps possibly probably unsure uncertain unclear undecided unlikely unconfirmed unverified unproven unwilling reluctant hesitant hesitate hesitating doubt doubts doubted doubtful doubting whether refuse refuses refused refusing avoid avoids avoided avoiding cancel cancels canceled cancelled canceling cancelling decline declines declined declining reject rejects rejected rejecting deny denies denied against without instead rather wonder wondered think thinking want wanted hope hoping wish consider considering wondering if unless না নয় হয়নি হয়তো সম্ভবত যদি na nai noy nah hoyto hoytoba shombhoboto jodi नहीं शायद अगर no quizás quizas si ";
    private static String folded(String word){return java.text.Normalizer.normalize(word,java.text.Normalizer.Form.NFC).toLowerCase(java.util.Locale.ROOT).replace('’','\'');}
    private static boolean listed(String list,String word){return list.contains(" "+folded(word)+" ");}
    private static java.util.List<Token> tokens(String text){java.util.ArrayList<Token> list=new java.util.ArrayList<>();java.util.regex.Matcher matcher=WORD.matcher(text);while(matcher.find())list.add(new Token(matcher.group(),matcher.start(),matcher.end()));return list;}
    private static final class Token {final String word;final int start,end;Token(String word,int start,int end){this.word=word;this.start=start;this.end=end;}}
    private static java.util.HashSet<String> protectedWords(java.util.List<Token> source){java.util.HashSet<String> found=new java.util.HashSet<>();for(Token word:source)if(listed(PROTECTED,word.word))found.add(folded(word.word));return found;}
    private static boolean question(String raw,java.util.List<Token> words){return raw.indexOf('?')>=0 || (!words.isEmpty() && listed(" how why what when where who whether is are can could should do does did ",words.get(0).word));}
    /** Clause separators cannot disappear and attach one clause's facts to another. Decimal/time punctuation is data. */
    private static boolean crossesClause(String text){
        for(int at=0;at<text.length();at++){
            char c=text.charAt(at);if(",.;:!?।\n\r".indexOf(c)<0)continue;
            if((c=='.'||c==','||c==':') && at>0 && at+1<text.length() && Character.isDigit(text.charAt(at-1)) && Character.isDigit(text.charAt(at+1)))continue;
            for(int next=at+1;next<text.length();next++)if(Character.isLetterOrDigit(text.charAt(next)))return true;
        }
        return false;
    }
    /** AI chooses a relevant source phrase from anywhere, not a new assertion assembled from distant facts. */
    static boolean titleAccepted(String raw,String candidate){
        if(raw==null || candidate==null || candidate.trim().isEmpty() || candidate.codePointCount(0,candidate.length())>72 || candidate.matches("(?s).*[\\r\\n\\p{Cntrl}].*"))return false;
        java.util.List<Token> source=tokens(raw),title=tokens(candidate);
        if(title.isEmpty() || title.size()>8 || crossesClause(candidate))return false;
        java.util.HashSet<String> protectedWords=protectedWords(source);
        java.util.HashSet<String> titleWords=new java.util.HashSet<>();for(Token word:title)titleWords.add(folded(word.word));
        // Preserve uncertainty/negation; an idea must never become a definite commitment.
        if(!titleWords.containsAll(protectedWords) || question(raw,source)!=(candidate.indexOf('?')>=0))return false;
        for(int start=0;start+title.size()<=source.size();start++){
            boolean same=true;for(int i=0;i<title.size();i++)if(!folded(source.get(start+i).word).equals(folded(title.get(i).word))){same=false;break;}
            if(!same)continue;
            String fragment=fragment(raw,source.get(start).start,source.get(start+title.size()-1).end);
            if(!crossesClause(fragment) && TranscriptGuard.numbers(fragment).equals(TranscriptGuard.numbers(candidate)) && TranscriptGuard.symbols(fragment).equals(TranscriptGuard.symbols(candidate)) && spacedSigns(fragment).equals(spacedSigns(candidate)) && extraSymbols(fragment).equals(extraSymbols(candidate)))return true;
        }
        return false;
    }
    private static String fragment(String source,int start,int end){
        // Keep attached usernames, amounts and signs with their source token.
        int at=start;while(at>0){int cp=source.codePointBefore(at);if(Character.isWhitespace(cp)||Character.getType(cp)==Character.CURRENCY_SYMBOL||"#+@−–-%‰".indexOf(cp)>=0)at-=Character.charCount(cp);else break;}
        int tail=end;while(tail<source.length()){int cp=source.codePointAt(tail);if(Character.isWhitespace(cp)||cp=='%'||cp=='‰')tail+=Character.charCount(cp);else break;}
        return source.substring(at,tail).trim();
    }
    /** Linear bounded windows: precompute source tokens/guards once, never rescan the full note per candidate. */
    static String title(String text,String fallback){
        String source=text==null?"":text.trim();if(source.isEmpty())return fallback;
        java.util.List<Token> words=tokens(source);java.util.HashSet<String> required=protectedWords(words);
        boolean question=question(source,words);String best="";int scoreBest=Integer.MIN_VALUE;
        for(int start=0;start<words.size();start++){
            java.util.HashSet<String> included=new java.util.HashSet<>();int score=0;
            for(int count=1;count<=8 && start+count<=words.size();count++){
                Token first=words.get(start),last=words.get(start+count-1);included.add(folded(last.word));
                score+=listed(FILLER,last.word)?-3:8+Math.min(8,last.word.codePointCount(0,last.word.length()));
                if(!included.containsAll(required))continue;
                String span=fragment(source,first.start,last.end);
                if(span.length()>144 || crossesClause(span))continue;
                if(question)span+="?";
                if(span.codePointCount(0,span.length())>72)continue;
                int rank=score-(listed(FILLER,first.word)?6:0)-(listed(FILLER,last.word)?6:0);
                if(rank>scoreBest){scoreBest=rank;best=span;}
            }
        }
        return best.isEmpty()?fallback:best;
    }
    /** Small strict JSON parser: exactly two string fields, no coercion, duplicate keys or trailing prose. */
    private static final class ReplyReader {
        final String text;int at;ReplyReader(String text){if(text==null || text.length()>2_000_000)throw new IllegalArgumentException();this.text=text;}
        String[] read(){take('{');String title=null,body=null;for(int n=0;n<2;n++){String key=string();take(':');String value=string();if(key.equals("title") && title==null)title=value;else if(key.equals("body") && body==null)body=value;else throw new IllegalArgumentException();if(n==0)take(',');}take('}');space();if(at!=text.length() || title==null || body==null)throw new IllegalArgumentException();return new String[]{title,body};}
        void space(){while(at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        void take(char wanted){space();if(at>=text.length() || text.charAt(at++)!=wanted)throw new IllegalArgumentException();}
        String string(){take('"');StringBuilder out=new StringBuilder();while(at<text.length()){char c=text.charAt(at++);if(c=='"')return out.toString();if(c<' ')throw new IllegalArgumentException();if(c=='\\'){if(at>=text.length())throw new IllegalArgumentException();c=text.charAt(at++);switch(c){case '"':case '\\':case '/':out.append(c);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;case 'u':if(at+4>text.length())throw new IllegalArgumentException();try{String hex=text.substring(at,at+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw new NumberFormatException();out.append((char)Integer.parseInt(hex,16));}catch(NumberFormatException bad){throw new IllegalArgumentException();}at+=4;break;default:throw new IllegalArgumentException();}}else out.append(c);}throw new IllegalArgumentException();}
    }
    private QuickThoughtPolicy(){}
}
