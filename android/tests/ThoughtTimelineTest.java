package app.voiceflow;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class ThoughtTimelineTest {
    private static long at(String date)throws Exception{return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX",Locale.US).parse(date).getTime();}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    private static ThoughtTimeline.Item item(long id,String title,String text,String raw){return new ThoughtTimeline.Item(id,title,text,raw,1,false,false,false);}

    public static void main(String[] args)throws Exception {
        TimeZone zone=TimeZone.getTimeZone("America/New_York");
        long night=at("2026-10-03T03:59:00Z"),morning=at("2026-10-03T04:01:00Z"),later=at("2026-10-03T13:00:00Z");
        String original="  Buy milk\nthen call মা 😀.  ",edited="  Buy oat milk\nthen call মা 😀.  ";
        ThoughtTimeline.Item old=item(night,"Old thought","Yesterday","Yesterday source");
        ThoughtTimeline.Item legacy=item(morning,"Legacy title","","  Full legacy transcript\nnot a summary.  ");
        ThoughtTimeline.Item current=item(later,"An entire untruncated thought title",edited,original);
        List<ThoughtTimeline.Item> input=Arrays.asList(legacy,old,current);
        List<ThoughtTimeline.Day> days=ThoughtTimeline.group(input,zone);
        check(days.size()==2,"Local midnight must split calendar days");
        ThoughtTimeline.Day today=days.get(0);
        check(today.key.equals("2026-10-03")&&days.get(1).key.equals("2026-10-02"),"Days must be newest first");
        check(today.items.get(0)==current&&today.items.get(1)==legacy&&today.time()==later,"Rows and header time must follow IDs");
        check(input.get(0)==legacy&&input.get(1)==old,"Projection must not reorder its input");
        check(current.display().equals(current.title),"Display must preserve the complete title for UI truncation");
        check(current.source().equals(edited),"Current body including whitespace and Unicode must remain exact");
        check(legacy.source().equals(legacy.raw),"Legacy body must fall back to the complete original transcript");
        check(today.recap!=null&&today.recap.entries.size()==2,"Completed thoughts must become recap entries");
        HistoryDays.Entry projected=today.recap.entries.get(0);
        check(projected.id==current.id&&projected.text.equals(edited)&&projected.raw.equals(original),"Recap must separate current body from original transcript without metadata noise");
        HistoryDays.Entry legacyEntry=today.recap.entries.get(1);
        check(legacyEntry.text.equals(legacy.raw)&&legacyEntry.raw.equals(legacy.raw),"Legacy source fallback must remain exact");
        check(today.recap.input().indexOf(legacy.raw)<today.recap.input().indexOf(edited),"Recap input remains chronological");

        ThoughtTimeline.Item recording=new ThoughtTimeline.Item(later+3,"Already titled","Partial words","partial raw",7,true,false,false);
        ThoughtTimeline.Item pending=new ThoughtTimeline.Item(later+2,"Waiting title","Previous text","previous raw",8,false,true,false);
        ThoughtTimeline.Item empty=item(later+1,"Silent recording"," \n\t "," \n ");
        ThoughtTimeline.Item interrupted=new ThoughtTimeline.Item(later+4,"","","",9,false,false,true);
        ThoughtTimeline.Day unfinished=ThoughtTimeline.group(Arrays.asList(empty,pending,recording,interrupted),zone).get(0);
        check(unfinished.items.size()==4&&unfinished.items.get(0)==interrupted&&unfinished.time()==interrupted.id,"Unfinished and empty rows must remain visible");
        check(unfinished.recap==null,"A day without completed nonempty bodies has no recap");
        check(recording.source().isEmpty()&&pending.source().isEmpty()&&empty.source().isEmpty(),"Recording, queued and empty content must be excluded");
        check(recording.display().equals("Recording…")&&pending.display().equals("Source saved. Waiting for transcription."),"Capture status must override stale titles");
        check(interrupted.display().equals("Recording interrupted."),"Interrupted sources need a visible fallback");
        ThoughtTimeline.Day mixed=ThoughtTimeline.group(Arrays.asList(current,pending,recording),zone).get(0);
        check(mixed.items.size()==3&&mixed.recap.entries.size()==1&&mixed.recap.time()==current.id,"Pending rows must not contaminate recap or its source time");

        ThoughtTimeline.Item editedAgain=new ThoughtTimeline.Item(later,current.title,"Second edit",original,2,false,false,false);
        ThoughtTimeline.Item rawChanged=new ThoughtTimeline.Item(later,current.title,edited,"Different source",2,false,false,false);
        ThoughtTimeline.Item revisionOnly=new ThoughtTimeline.Item(later,current.title,edited,original,99,false,false,false);
        HistoryDays.Entry editedEntry=ThoughtTimeline.group(Collections.singletonList(editedAgain),zone).get(0).recap.entries.get(0);
        HistoryDays.Entry rawEntry=ThoughtTimeline.group(Collections.singletonList(rawChanged),zone).get(0).recap.entries.get(0);
        HistoryDays.Entry revisionEntry=ThoughtTimeline.group(Collections.singletonList(revisionOnly),zone).get(0).recap.entries.get(0);
        check(!projected.text.equals(editedEntry.text)&&projected.raw.equals(editedEntry.raw),"Body edits change current recap content without rewriting its source");
        check(projected.text.equals(rawEntry.text)&&!projected.raw.equals(rawEntry.raw),"Original source changes must be visible to scoped recap identity");
        check(projected.text.equals(revisionEntry.text)&&projected.raw.equals(revisionEntry.raw),"Revision counters must not add metadata to recap payloads");
        ThoughtTimeline.Item rawless=item(later,"","  text-only 😀  ",null);
        HistoryDays.Entry rawlessEntry=ThoughtTimeline.group(Collections.singletonList(rawless),zone).get(0).recap.entries.get(0);
        check(rawlessEntry.text.equals(rawless.text)&&rawlessEntry.raw.equals(rawless.text),"Old text-only notes must have an original fallback");

        ThoughtTimeline.Item firstHour=item(at("2026-11-01T05:30:00Z"),"First","First 1:30","First 1:30");
        ThoughtTimeline.Item secondHour=item(at("2026-11-01T06:30:00Z"),"Second","Second 1:30","Second 1:30");
        ThoughtTimeline.Day dst=ThoughtTimeline.group(Arrays.asList(firstHour,secondHour),zone).get(0);
        check(dst.items.size()==2&&dst.items.get(0)==secondHour&&dst.items.get(1)==firstHour,"Repeated DST hours must retain real chronological order");
        zone.setRawOffset(0);
        check(today.zone.getRawOffset()!=0&&today.recap.zone.getRawOffset()!=0,"Stored day zones must not change when the caller mutates its zone");
        check(ThoughtTimeline.group(Collections.<ThoughtTimeline.Item>emptyList(),zone).isEmpty(),"No rows means no days or recaps");
        check(ThoughtTimeline.group(null,zone).isEmpty(),"An absent input has no days");
        System.out.println("PASS: exact thought body/source projection, legacy fallback, pending exclusion with retained rows, local days, edits, Unicode, DST ordering and empty recaps");
    }
}
