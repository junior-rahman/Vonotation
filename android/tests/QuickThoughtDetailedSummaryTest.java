package app.voiceflow;

public final class QuickThoughtDetailedSummaryTest {
    static int checks;
    static void check(boolean valid,String reason){checks++;if(!valid)throw new AssertionError(reason);}
    static String sentence(int at){return "Topic "+at+" covers the garden project and supplies.";}
    static String note(int size){StringBuilder out=new StringBuilder();for(int i=0;i<size;i++){if(i>0)out.append(' ');out.append(sentence(i));}return out.toString();}
    static String choose(int... indexes){StringBuilder out=new StringBuilder();for(int at:indexes){if(out.length()>0)out.append(' ');out.append(sentence(at));}return out.toString();}
    static void reject(String raw,String candidate,String reason){check(!QuickThoughtPolicy.summaryAccepted(raw,candidate),reason);}
    public static void main(String[] args){
        check(QuickThoughtPolicy.SUMMARY_VERSION>=2,"new detailed summaries have an upgrade identity");
        String raw=note(10),detailed=choose(0,1,3,4,6,7,9);
        check(QuickThoughtPolicy.summaryAccepted(raw,detailed),"seven distributed complete sentences form a detailed ten-sentence summary");
        reject(raw,choose(0),"one-sentence teaser is insufficient");reject(raw,choose(0,1,2),"old fixed three-sentence output is insufficient");
        reject(raw,choose(0,1,2,3,4,5,6),"long summary cannot omit the ending quarter");
        reject(raw,choose(0,1,4,3,6,7,9),"facts cannot be reordered");reject(raw,detailed.replace("Topic 7","Topic 99"),"facts cannot be changed");
        reject(raw,detailed+" 🤡.","wordless emoji padding is still an unspoken addition");
        StringBuilder dominant=new StringBuilder("The central project needs ");for(int i=0;i<300;i++)dominant.append("important supplies ");dominant.append("delivered tomorrow.");
        String weighted=raw.replace(sentence(4),dominant.toString());
        reject(weighted,choose(0,1,2,3,6,7,9),"many short sentences cannot hide the dominant long idea");
        String fallback=QuickThoughtPolicy.summary(weighted);check(fallback.contains(dominant),"fallback adds a dominant middle sentence to meet word coverage");check(QuickThoughtPolicy.summaryAccepted(weighted,fallback),"weighted fallback passes its guard");
        String corrected=raw.replace(sentence(2),"Correction: the offer is not approved.");
        reject(corrected,detailed,"a later correction and negation cannot disappear");check(QuickThoughtPolicy.summary(corrected).contains("Correction: the offer is not approved."),"fallback preserves correction");
        String questioned=raw.replace(sentence(2),"Is the garden project approved?");reject(questioned,detailed,"a question cannot disappear from a detailed summary");
        String uncertain=raw.replace(sentence(2),"Maybe the garden project needs more supplies.");reject(uncertain,detailed,"tentative qualifier cannot disappear from detailed coverage");
        for(int size=1;size<=100;size++){
            String source=note(size),summary=QuickThoughtPolicy.summary(source);
            check(QuickThoughtPolicy.summaryAccepted(source,summary),"fallback passes complete-source guard at "+size+" sentences");
            check(summary.contains(sentence(0))&&summary.contains(sentence(size-1)),"fallback covers beginning/end at "+size+" sentences");
            if(size<=4)for(int i=0;i<size;i++)check(summary.contains(sentence(i)),"short notes are complete, not padded");
        }
        check(QuickThoughtPolicy.summary(note(8)).contains("\n\n"),"long excerpts are grouped into readable paragraphs");
        StringBuilder duplicate=new StringBuilder();for(int i=0;i<100;i++)duplicate.append("Same sentence. ");String repeated=duplicate.toString().trim();
        check(QuickThoughtPolicy.summaryAccepted(repeated,QuickThoughtPolicy.summary(repeated)),"repeated ambiguous source sentences retain a safely validated full-source fallback");
        String large=note(2000),largeSummary=QuickThoughtPolicy.summary(large);
        check(QuickThoughtPolicy.summaryAccepted(large,largeSummary),"large complete-source summary remains guarded");check(largeSummary.length()>large.length()/2&&largeSummary.contains(sentence(1999)),"large summary is detailed and reaches the ending");
        String shortSource="Maybe call Juel tomorrow. Buy 5 apples. Meeting at 5:30 costs $5.50.";
        reject(shortSource,shortSource.replace("Maybe call","Call"),"full short summary retains maybe");reject(shortSource,shortSource.replace("5 apples","3 apples"),"full short summary retains quantities");
        reject(shortSource,shortSource.replace("5:30","6:30"),"full short summary retains time");reject(shortSource,shortSource.replace("$5.50","5.50"),"full short summary retains currency");
        reject("Pay −5 dollars. Keep $5.50 and 5%.","Pay 5 dollars. Keep $5.50 and 5%.","minus remains attached to its amount");
        reject("Is my visa approved？ Check tomorrow.","Is my visa approved. Check tomorrow.","Unicode question stays a question");
        String json=QuickThoughtOrganizationTest.json("Garden project and supplies",raw);
        QuickThoughtPolicy.Organized organized=QuickThoughtPolicy.organizeReply(raw,json);check(organized.body.equals(raw),"detailed summary fallback never replaces the full body");check(organized.summary.equals(QuickThoughtPolicy.summary(raw)),"strict organizer uses the new detailed fallback");
        check(QuickThoughtPolicy.detailedSummary(raw,choose(0)).equals(QuickThoughtPolicy.summary(raw)),"summary-only upgrade rejects too-short provider output");
        check(QuickThoughtPolicy.detailedSummary(raw,detailed).equals(detailed),"summary-only upgrade retains a detailed faithful response");
        System.out.println("PASS: "+checks+" proportional detailed summaries, distributed ideas, caveats, large sources, and unchanged full-body checks.");
    }
}
