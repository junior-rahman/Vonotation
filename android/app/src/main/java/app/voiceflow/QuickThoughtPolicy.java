package app.voiceflow;

/** Pure rules for opt-in thought capture. No commands, translation or AI replies. */
final class QuickThoughtPolicy {
    static final long IDLE_MS=60_000L;
    static final int SUMMARY_VERSION=3;
    static boolean visible(boolean enabled,boolean interactive,boolean locked,boolean appOpen,boolean captureOpen,boolean inputActive,boolean recording){
        return enabled && interactive && !locked && !appOpen && !captureOpen && !inputActive && !recording;
    }
    static boolean collapsed(long now,long lastInteraction,boolean working){
        return !working && (lastInteraction<=0 || now-lastInteraction>=IDLE_MS);
    }
    static final class Organized {
        final String title,body,summary;
        final boolean accepted,summarized;
        Organized(String title,String body,String summary,boolean accepted,boolean summarized){this.title=title;this.body=body;this.summary=summary;this.accepted=accepted;this.summarized=summarized;}
    }
    /** A malformed or unfaithful response never replaces the complete original. */
    static Organized organizeReply(String raw,String response){
        try{
            String[] fields=new ReplyReader(response).read();
            return organized(raw,fields[0],fields[1],fields[2]);
        }catch(IllegalArgumentException invalid){return fallback(raw);}
    }
    static Organized organized(String raw,String proposedTitle,String proposedBody){
        return organized(raw,proposedTitle,proposedBody,null);
    }
    static Organized organized(String raw,String proposedTitle,String proposedBody,String proposedSummary){
        String source=raw==null?"":raw.trim();
        if(!bodyAccepted(source,proposedBody))return fallback(raw);
        String title=titleAccepted(source,proposedTitle)?proposedTitle.trim():title(source,"Quick thought");
        Summary summary=summaryReply(source,proposedSummary);
        return new Organized(title,proposedBody.trim(),summary.text,true,summary.accepted);
    }
    private static Organized fallback(String raw){String source=raw==null?"":raw.trim();return new Organized(title(source,"Quick thought"),source,summary(source),false,false);}
    /** A concise paraphrase may differ from the transcript; these checks reject obvious unsupported facts.
     * They are conservative lexical guards, not a proof of semantic equivalence. The full body has its
     * separate exact-word guard and is never replaced by a summary. */
    static boolean summaryAccepted(String raw,String candidate){
        if(raw==null||candidate==null||candidate.trim().isEmpty()||candidate.length()>raw.length()*2L+256)return false;
        java.util.List<Token> sourceWords=tokens(raw),summaryWords=tokens(candidate);
        if(sourceWords.isEmpty()||summaryWords.isEmpty()||LanguagePolicy.scriptDrift(raw,candidate))return false;
        int size=sourceWords.size(),count=summaryWords.size();
        // A short phrase may already be its own gist. Longer notes must actually be condensed.
        if(size>12 && count>=size)return false;
        if(size>=30 && count>Math.max(12,(int)Math.ceil(size*.55)))return false;
        if(!containsItems(TranscriptGuard.numbers(raw),TranscriptGuard.numbers(candidate))
            ||!containsSymbols(TranscriptGuard.symbols(raw),TranscriptGuard.symbols(candidate))
            ||!containsItems(spacedSigns(raw),spacedSigns(candidate))
            ||!containsSymbols(extraSymbols(raw),extraSymbols(candidate)))return false;
        java.util.Set<String> vocabulary=contentWords(raw),proposed=contentWords(candidate);
        int supported=0;for(String word:proposed)if(vocabulary.contains(word))supported++;
        if(proposed.isEmpty()||(vocabulary.size()>2&&proposed.size()<2)||supported<Math.min(2,proposed.size())||supported*100<proposed.size()*45||!namesGrounded(raw,candidate))return false;
        java.util.List<SummarySentence> source=summarySentences(raw);
        for(SummarySentence part:summarySentences(candidate)){
            java.util.Set<String> words=contentWords(part.text);SummarySentence best=null;int bestScore=0;
            for(SummarySentence original:source){
                int score=overlap(words,original.content);
                // Later equal matches win so an earlier claim cannot hide its later correction.
                if(score>0 && score>=bestScore){bestScore=score;best=original;}
            }
            if(best==null||bestScore<Math.min(2,words.size())||!qualifiersKept(best.text,part.text))return false;
            if(!numericContextKept(best.text,part.text))return false;
        }
        return true;
    }
    static final class Summary {
        final String text;final boolean accepted;
        Summary(String text,boolean accepted){this.text=text;this.accepted=accepted;}
    }
    static Summary summaryReply(String raw,String candidate){boolean accepted=summaryAccepted(raw,candidate);return new Summary(accepted?candidate.trim():summary(raw),accepted);}
    static String detailedSummary(String raw,String candidate){return summaryReply(raw,candidate).text;}
    private static final String SUMMARY_STOP=" i me my we our you your he she they them his her their a an the this that these those and or to of for on in at is are was were be been being have has had do does did it its just really very some something thing thought thoughts note quick then so with as also because from by into one more much all any about ";
    private static final String NEGATIVE=" no not never unapproved unconfirmed undecided cannot can't don't doesn't didn't won't wouldn't shouldn't couldn't isn't aren't wasn't weren't haven't hasn't hadn't mustn't needn't shan't ain't refuse refuses refused avoid avoids avoided cancel cancels canceled cancelled decline declines declined reject rejects rejected deny denies denied without না নয় হয়নি na nai noy nah नहीं ";
    private static final String TENTATIVE=" maybe may might could would should perhaps possibly probably unsure uncertain unclear undecided unlikely unconfirmed unverified unproven reluctant hesitant doubt doubts doubtful whether wonder wondering think thinking hope hoping wish consider considering হয়তো সম্ভবত hoyto hoytoba shombhoboto शायद quizás quizas ";
    private static final String CONDITION=" if unless until only যদি jodi अगर si ";
    private static final String ACTION=" buy sell call pay send book cancel order move measure paint repaint prepare deliver ship leave bring keep delete save record meet approve approved reject rejected accept accepted ";
    private static java.util.Set<String> contentWords(String text){java.util.LinkedHashSet<String> result=new java.util.LinkedHashSet<>();for(Token word:tokens(text))if(!listed(SUMMARY_STOP,word.word))result.add(folded(word.word));return result;}
    private static boolean namesGrounded(String source,String candidate){
        java.util.HashSet<String> known=new java.util.HashSet<>();for(Token word:tokens(source))known.add(folded(word.word));
        for(Token word:tokens(candidate)){
            if(!Character.isUpperCase(word.word.codePointAt(0))||known.contains(folded(word.word)))continue;
            int before=word.start-1;while(before>=0&&Character.isWhitespace(candidate.charAt(before)))before--;
            if(before>=0&&".!?।。！？؟•".indexOf(candidate.charAt(before))<0)return false;
        }
        return true;
    }
    private static int overlap(java.util.Set<String> a,java.util.Set<String> b){int count=0;for(String word:a)if(b.contains(word))count++;return count;}
    private static boolean containsItems(java.util.List<String> source,java.util.List<String> selected){java.util.ArrayList<String> remaining=new java.util.ArrayList<>(source);for(String item:selected)if(!remaining.remove(item))return false;return true;}
    private static boolean containsSymbols(String source,String candidate){java.util.ArrayList<String> before=new java.util.ArrayList<>(),after=new java.util.ArrayList<>();for(int at=0;at<source.length();){int cp=source.codePointAt(at);at+=Character.charCount(cp);before.add(new String(Character.toChars(cp)));}for(int at=0;at<candidate.length();){int cp=candidate.codePointAt(at);at+=Character.charCount(cp);after.add(new String(Character.toChars(cp)));}return containsItems(before,after);}
    private static boolean has(String text,String list){for(Token word:tokens(text))if(listed(list,word.word))return true;return false;}
    private static boolean negativeScopeKept(String source,String candidate){
        // "Do not want a reminder to buy milk" cannot turn into "Do not buy milk".
        // Ordinary negative paraphrases (not approved / unapproved) need no exact-word match.
        java.util.List<Token> words=tokens(source);
        for(int i=0;i+1<words.size();i++)if(listed(NEGATIVE,words.get(i).word)&&listed(" want wish desire need ",words.get(i+1).word)){
            if(!has(candidate," want wish desire need reminder request "))return false;
        }
        return true;
    }
    private static boolean qualifiersKept(String source,String candidate){
        if(has(source,NEGATIVE)!=has(candidate,NEGATIVE)||has(source,TENTATIVE)!=has(candidate,TENTATIVE))return false;
        if(!negativeScopeKept(source,candidate))return false;
        for(Token word:tokens(source))if(listed(CONDITION,word.word)&&!has(candidate," "+folded(word.word)+" "))return false;
        boolean sourceQuestion=questionMark(source),summaryQuestion=questionMark(candidate)||has(candidate," whether unresolved unknown ");
        if(sourceQuestion&&!summaryQuestion)return false;
        return !questionMark(candidate)||sourceQuestion||has(source,TENTATIVE);
    }
    /** Do not reassign an amount to another action when compressing a compound source sentence. */
    private static boolean numericContextKept(String source,String candidate){
        if(TranscriptGuard.numbers(candidate).isEmpty())return true;
        java.util.List<Token> original=tokens(source),proposed=tokens(candidate);
        for(int i=0;i<proposed.size();i++)if(proposed.get(i).word.matches(".*\\p{N}.*")){
            String value=TranscriptGuard.digits(proposed.get(i).word),action=priorAction(proposed,i);boolean found=false;
            for(int n=0;n<original.size();n++)if(value.equals(TranscriptGuard.digits(original.get(n).word))&&(action.isEmpty()||action.equals(priorAction(original,n)))){found=true;break;}
            if(!found)return false;
        }
        return true;
    }
    private static String priorAction(java.util.List<Token> words,int at){for(int i=at-1;i>=0;i--)if(listed(ACTION,words.get(i).word))return folded(words.get(i).word);return "";}
    private static final class SummarySentence {
        final String text;final int words;final java.util.Set<String> content;
        SummarySentence(String text){this.text=text;words=tokens(text).size();content=contentWords(text);}
    }
    private static java.util.List<SummarySentence> summarySentences(String raw){
        java.util.ArrayList<SummarySentence> result=new java.util.ArrayList<>();
        String normalized=raw.replaceAll("(?m)^[ \\t]*•[ \\t]+","").replaceAll("[\\r\\n]+"," ");
        for(String sentence:sentences(normalized))for(String part:independentClauses(sentence)){
            SummarySentence item=new SummarySentence(part.trim());if(item.words>0)result.add(item);
        }
        return result;
    }
    /** Semicolons can separate independent ideas; a leading condition and quoted text stay intact. */
    private static java.util.List<String> independentClauses(String text){
        java.util.ArrayList<String> result=new java.util.ArrayList<>();
        if(text.matches("(?is)^(?:if|unless|until|when|once)\\b.*")){result.add(text);return result;}
        int start=0,depth=0;boolean quoted=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);if(c=='"'||c=='“'||c=='”'){quoted=!quoted;continue;}
            if(c=='('||c=='['||c=='{'){depth++;continue;}if(c==')'||c==']'||c=='}'){depth=Math.max(0,depth-1);continue;}
            if(c==';'&&!quoted&&depth==0){String part=text.substring(start,i).trim();if(!part.isEmpty())result.add(part+".");start=i+1;}
        }
        String tail=text.substring(start).trim();if(!tail.isEmpty())result.add(tail);return result;
    }
    /** Offline gist: select a few useful complete source ideas instead of presenting the whole
     * transcript again. If one unstructured long sentence cannot be shortened safely, leave the
     * summary empty until a provider can paraphrase it; the full transcript is already preserved. */
    static String summary(String raw){
        String text=raw==null?"":raw.trim();java.util.List<SummarySentence> source=summarySentences(text);
        int total=tokens(text).size();if(total==0)return "";
        java.util.ArrayList<SummarySentence> unique=new java.util.ArrayList<>();java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(SummarySentence sentence:source){String shortened=trimSummaryFiller(sentence.text);String key=tokenKey(shortened);if(seen.add(key))unique.add(new SummarySentence(shortened));}
        if(total<=12){StringBuilder gist=new StringBuilder();for(SummarySentence sentence:unique){if(gist.length()>0)gist.append(' ');gist.append(sentence.text);}return gist.toString();}
        int budget=Math.max(8,(int)Math.ceil(total*.35));int maxWords=Math.max(12,(int)Math.ceil(total*.55));
        java.util.ArrayList<Integer> order=new java.util.ArrayList<>();for(int i=0;i<unique.size();i++)order.add(i);
        java.util.Collections.sort(order,(a,b)->Integer.compare(summaryScore(unique.get(b),b,unique.size()),summaryScore(unique.get(a),a,unique.size())));
        java.util.HashSet<Integer> selected=new java.util.HashSet<>();int kept=0;
        for(int at:order){SummarySentence sentence=unique.get(at);if(sentence.words>maxWords||(kept>0&&kept+sentence.words>budget))continue;if(selected.size()>=4)break;selected.add(at);kept+=sentence.words;if(kept>=budget)break;}
        StringBuilder gist=new StringBuilder();for(int i=0;i<unique.size();i++)if(selected.contains(i)){if(gist.length()>0)gist.append("\n\n");gist.append(unique.get(i).text);}
        String result=gist.toString();return summaryAccepted(text,result)?result:"";
    }
    private static int summaryScore(SummarySentence sentence,int at,int size){int score=contentWords(sentence.text).size()*3-Math.max(0,sentence.words-24);if(at==0)score+=24;else if(at==size-1)score+=6;if(has(sentence.text,NEGATIVE)||has(sentence.text,TENTATIVE)||questionMark(sentence.text))score+=5;if(has(sentence.text," correction corrected actually instead "))score+=20;return score;}
    private static String tokenKey(String text){StringBuilder key=new StringBuilder();for(Token word:tokens(text))key.append(folded(word.word)).append(' ');return key.toString();}
    private static String trimSummaryFiller(String text){return text.replaceFirst("(?i)^(?:(?:okay|well|um|uh)[, ]+|I (?:just )?wanted to (?:make|leave) a (?:quick )?note (?:that|about) +|this is (?:just )?(?:a )?(?:quick )?(?:thought|note) about +)+","").trim();}
    private static final String ABBREVIATIONS=" mr mrs ms dr prof sr jr st vs etc eg ie fig no approx inc ltd ";
    private static boolean questionMark(String text){return text.indexOf('?')>=0||text.indexOf('？')>=0||text.indexOf('؟')>=0;}
    /** Conservative boundaries: decimals, initials, abbreviations, and quoted/nested punctuation stay together. */
    private static java.util.List<String> sentences(String raw){
        java.util.ArrayList<String> parts=new java.util.ArrayList<>();int start=0,depth=0;boolean quoted=false,singleQuoted=false;
        for(int at=0;at<raw.length();at++){
            char c=raw.charAt(at);
            if(c=='"'||c=='“'||c=='”'){quoted=!quoted;continue;}
            if(c=='\''||c=='‘'||c=='’'){
                boolean apostrophe=at>0&&at+1<raw.length()&&Character.isLetterOrDigit(raw.charAt(at-1))&&Character.isLetterOrDigit(raw.charAt(at+1));
                if(!apostrophe)singleQuoted=!singleQuoted;
                continue;
            }
            if(c=='('||c=='['||c=='{'){depth++;continue;}
            if(c==')'||c==']'||c=='}'){if(depth>0)depth--;continue;}
            if(quoted||singleQuoted||depth>0||".!?।。！？؟".indexOf(c)<0)continue;
            int end=at+1;while(end<raw.length()&&".!?।。！？؟".indexOf(raw.charAt(end))>=0)end++;
            if(end<raw.length()&&!Character.isWhitespace(raw.charAt(end)))continue;
            if(c=='.'){
                if(end>at+1||(at>0&&raw.charAt(at-1)=='.')){at=end-1;continue;}
                if(at>0&&at+1<raw.length()&&Character.isDigit(raw.charAt(at-1))&&Character.isDigit(raw.charAt(at+1)))continue;
                int word=at;while(word>0&&Character.isLetter(raw.charAt(word-1)))word--;
                String before=raw.substring(word,at).toLowerCase(java.util.Locale.ROOT);
                if(before.length()==1||ABBREVIATIONS.contains(" "+before+" "))continue;
            }
            String part=raw.substring(start,end).trim();if(!part.isEmpty())parts.add(part);start=end;at=end-1;
        }
        String tail=raw.substring(start).trim();if(!tail.isEmpty())parts.add(tail);return parts;
    }
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
    /** Small strict JSON parser: exactly three string fields, no coercion, duplicate keys or trailing prose. */
    private static final class ReplyReader {
        final String text;int at;ReplyReader(String text){if(text==null || text.length()>2_000_000)throw new IllegalArgumentException();this.text=text;}
        String[] read(){take('{');String title=null,body=null,summary=null;for(int n=0;n<3;n++){String key=string();take(':');String value=string();if(key.equals("title") && title==null)title=value;else if(key.equals("body") && body==null)body=value;else if(key.equals("summary") && summary==null)summary=value;else throw new IllegalArgumentException();if(n<2)take(',');}take('}');space();if(at!=text.length() || title==null || body==null || summary==null)throw new IllegalArgumentException();return new String[]{title,body,summary};}
        void space(){while(at<text.length() && " \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        void take(char wanted){space();if(at>=text.length() || text.charAt(at++)!=wanted)throw new IllegalArgumentException();}
        String string(){take('"');StringBuilder out=new StringBuilder();while(at<text.length()){char c=text.charAt(at++);if(c=='"')return out.toString();if(c<' ')throw new IllegalArgumentException();if(c=='\\'){if(at>=text.length())throw new IllegalArgumentException();c=text.charAt(at++);switch(c){case '"':case '\\':case '/':out.append(c);break;case 'b':out.append('\b');break;case 'f':out.append('\f');break;case 'n':out.append('\n');break;case 'r':out.append('\r');break;case 't':out.append('\t');break;case 'u':if(at+4>text.length())throw new IllegalArgumentException();try{String hex=text.substring(at,at+4);if(!hex.matches("[0-9a-fA-F]{4}"))throw new NumberFormatException();out.append((char)Integer.parseInt(hex,16));}catch(NumberFormatException bad){throw new IllegalArgumentException();}at+=4;break;default:throw new IllegalArgumentException();}}else out.append(c);}throw new IllegalArgumentException();}
    }
    private QuickThoughtPolicy(){}
}
