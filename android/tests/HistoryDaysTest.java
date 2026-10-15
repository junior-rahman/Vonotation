package app.voiceflow;
import java.util.*;
import java.text.SimpleDateFormat;

public final class HistoryDaysTest {
    static long at(String date)throws Exception{return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX",Locale.US).parse(date).getTime();}
    static void check(boolean okay){if(!okay)throw new AssertionError("Daily history mismatch");}
    public static void main(String[] args)throws Exception{
        TimeZone zone=TimeZone.getTimeZone("America/New_York");
        HistoryDays.Entry late=new HistoryDays.Entry(at("2026-10-03T03:59:00Z"),"Late evening plans",3),morning=new HistoryDays.Entry(at("2026-10-03T04:01:00Z"),"Morning notes",2),later=new HistoryDays.Entry(at("2026-10-03T13:00:00Z"),"Keyboard improvements",2);
        List<HistoryDays.Entry> sourceEntries=Arrays.asList(later,late,morning);List<HistoryDays.Day> days=HistoryDays.group(sourceEntries,zone);check(days.size()==2);check(days.get(0).key.equals("2026-10-03"));check(days.get(1).key.equals("2026-10-02"));check(days.get(0).entries.get(0)==later && days.get(0).entries.get(1)==morning);check(days.get(0).time()==later.id);check(days.get(0).words==4);check(sourceEntries.get(0)==later && sourceEntries.get(1)==late && sourceEntries.get(2)==morning);
        List<HistoryDays.Day> ascendingInput=HistoryDays.group(Arrays.asList(late,morning,later,new HistoryDays.Entry(later.id+1,"  ",0)),zone);check(ascendingInput.get(0).entries.get(0)==later && ascendingInput.get(0).entries.get(1)==morning);check(ascendingInput.size()==2 && ascendingInput.get(0).entries.size()==2);
        check(days.get(0).input().equals("Saved dictations on 2026-10-03 (America/New_York):\n\n00:01\nMorning notes\n\n09:00\nKeyboard improvements\n\n"));
        String id=HistoryDays.fingerprint(days.get(0),"model-a","https://provider-a/v1");check(id.equals("e2d5d1c692662b88a54f4ba7fcc9d86b44e8a5bea7f2376bb70f3f2afd75bb5e"));check(id.equals(HistoryDays.fingerprint(ascendingInput.get(0),"model-a","https://provider-a/v1")));check(!id.equals(HistoryDays.fingerprint(days.get(0),"model-b","https://provider-a/v1")));check(!id.equals(HistoryDays.fingerprint(days.get(0),"model-a","https://provider-b/v1")));
        HistoryDays.Day changed=HistoryDays.group(Arrays.asList(morning),zone).get(0);check(!id.equals(HistoryDays.fingerprint(changed,"model-a","https://provider-a/v1")));
        HistoryDays.Entry firstRepeatedHour=new HistoryDays.Entry(at("2026-11-01T05:30:00Z"),"First 1:30",2),secondRepeatedHour=new HistoryDays.Entry(at("2026-11-01T06:30:00Z"),"Second 1:30",2);List<HistoryDays.Day> dst=HistoryDays.group(Arrays.asList(firstRepeatedHour,secondRepeatedHour),zone);check(dst.size()==1 && dst.get(0).entries.size()==2);check(dst.get(0).entries.get(0)==secondRepeatedHour && dst.get(0).entries.get(1)==firstRepeatedHour);
        String source="One 😀\nTwo 😀\nThree 😀\nFour 😀";List<String> chunks=HistoryDays.chunks(source,8);StringBuilder rebuilt=new StringBuilder();for(String chunk:chunks){check(chunk.length()<=8);check(!Character.isHighSurrogate(chunk.charAt(chunk.length()-1)));rebuilt.append(chunk);}check(source.equals(rebuilt.toString()));
        System.out.println("PASS: newest-first days and dictations, local midnight, repeated DST hour order, unchanged chronological recaps and cache identities, input preservation, word totals, recap invalidation, and complete Unicode-safe chunking");
    }
}
