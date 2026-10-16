package app.voiceflow;

/** Regression for summaries that formerly copied every short note and most of a long transcript. */
public final class QuickThoughtDetailedSummaryTest {
    static int checks;
    static void check(boolean valid,String reason){checks++;if(!valid)throw new AssertionError(reason);}
    static int words(String text){return TranscriptGuard.words(text).size();}
    static void reject(String raw,String candidate,String reason){check(!QuickThoughtPolicy.summaryAccepted(raw,candidate),reason);}
    static String note(int repeats){StringBuilder out=new StringBuilder();for(int i=0;i<repeats;i++){if(out.length()>0)out.append(' ');out.append(QuickThoughtSummaryTest.SOURCE);}return out.toString();}
    public static void main(String[] args){
        check(QuickThoughtPolicy.SUMMARY_VERSION>=3,"concise summaries have a new cache/upgrade identity");
        String source=QuickThoughtSummaryTest.SOURCE,concise=QuickThoughtSummaryTest.CONCISE;
        check(words(source)>100,"regression source represents a substantial spoken thought");
        check(QuickThoughtPolicy.summaryAccepted(source,concise),"faithful paraphrase is accepted without complete-sentence copying");
        reject(source,source,"a long transcript is not accepted as its own summary");
        String fallback=QuickThoughtPolicy.summary(source);
        check(words(fallback)>0&&words(fallback)<=words(source)*.4,"local fallback is materially shorter than a long transcript");
        check(QuickThoughtPolicy.summaryAccepted(source,fallback),"local concise fallback satisfies the same source guard");
        String threeSentences="The kitchen walls look worn, and repainting the kitchen is the main project for this weekend because the room needs fresh paint. "
            +"Blue paint matches the existing cabinets, while the table and chairs need moving before any painting begins in the kitchen. "
            +"Saturday is for preparation and Sunday is for painting after measuring the walls, buying paint, and covering the floor.";
        String shorter=QuickThoughtPolicy.summary(threeSentences);
        check(words(shorter)<words(threeSentences),"three long sentences no longer trigger the old copy-everything rule");
        String single="Buy blue paint for the kitchen; move the furniture before painting; clean the brushes after painting.";
        String singleSummary=QuickThoughtPolicy.summary(single);
        check(words(singleSummary)<words(single),"a long single sentence can condense at safe independent clause boundaries");
        check(QuickThoughtPolicy.summaryAccepted(single,singleSummary),"single-sentence local condensation is guarded");
        String repeated=note(6),repeatedSummary=QuickThoughtPolicy.summary(repeated);
        check(words(repeatedSummary)<=words(repeated)*.4,"repeated spoken explanation no longer forces the full-source fallback");
        check(QuickThoughtPolicy.summaryAccepted(repeated,repeatedSummary),"repeated-source fallback remains faithful");
        String large=note(200),largeSummary=QuickThoughtPolicy.summary(large);
        check(words(largeSummary)<=words(large)*.4,"large-source summary stays concise");
        check(QuickThoughtPolicy.summaryAccepted(large,largeSummary),"large-source summary remains guarded");
        String cautious="Maybe repaint the kitchen this weekend. The paint purchase is not approved. Is the blue paint available?";
        reject(cautious,"Repaint the kitchen this weekend. The paint purchase is approved.","uncertainty and negation do not become commitments");
        reject(cautious,"Blue paint is available.","a question never becomes a factual answer");
        check(QuickThoughtPolicy.summaryAccepted("Do not buy the paint.","Do not buy the paint."),"an already brief caution remains complete");
        QuickThoughtPolicy.Organized organized=QuickThoughtPolicy.organizeReply(source,QuickThoughtSummaryTest.json("Repainting the kitchen",source,concise));
        check(organized.body.equals(source),"concise summary never replaces the complete transcript body");
        check(organized.summary.equals(concise),"organizer retains a concise faithful provider response");
        check(QuickThoughtPolicy.detailedSummary(source,source).equals(fallback),"summary-only upgrades reject echoed transcripts and use the concise fallback");
        check(QuickThoughtPolicy.detailedSummary(source,concise).equals(concise),"summary-only upgrades retain a faithful paraphrase");
        check(!QuickThoughtPolicy.bodyAccepted(source,concise),"full-transcript invariants remain stricter than summary invariants");
        System.out.println("PASS: "+checks+" concise long/short/single-sentence/repeated/large summary regressions and unchanged full-transcript checks.");
    }
}
