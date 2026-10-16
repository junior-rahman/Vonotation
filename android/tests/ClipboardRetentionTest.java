package app.voiceflow;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class ClipboardRetentionTest {
    public static void main(String[] args)throws Exception{
        boundaryAndClockEdges();
        copiesDoNotRenewEachOther();
        localDaysAndDst();
        exactValuesAndImmutableGroups();
        stableSameMillisecondOrder();
        System.out.println("PASS: exact 30-day clipboard expiry, overflow and clock rollback safety, independent copy lifetimes, timezone/DST days, full text preservation, immutable grouping, and stable same-millisecond order");
    }

    private static void boundaryAndClockEdges(){
        long life=30L*24*60*60*1000,copy=1_700_000_000_000L;
        check(ClipboardRetention.RETENTION_MILLIS==life,"Lifetime must be exactly 30 elapsed days");
        check(!ClipboardRetention.expired(copy,copy),"New copy expired");
        check(!ClipboardRetention.expired(copy,copy+life-1),"Copy expired early");
        check(ClipboardRetention.expired(copy,copy+life),"Expiry must include the boundary");
        check(ClipboardRetention.expired(copy,copy+life+1),"Old copy retained");
        check(!ClipboardRetention.expired(copy,copy-1),"Clock rollback aged a copy");
        check(!ClipboardRetention.expired(Long.MAX_VALUE,Long.MIN_VALUE),"Future copy overflow");
        check(!ClipboardRetention.expired(Long.MAX_VALUE-life+1,Long.MAX_VALUE),"Overflow became an expired copy");
        check(ClipboardRetention.expired(Long.MAX_VALUE-life,Long.MAX_VALUE),"Maximum representable exact expiry");
        check(!ClipboardRetention.expired(Long.MIN_VALUE,Long.MIN_VALUE+life-1),"Negative epoch expired early");
        check(ClipboardRetention.expired(Long.MIN_VALUE,Long.MIN_VALUE+life),"Negative epoch boundary");
        check(ClipboardRetention.expired(Long.MIN_VALUE,Long.MAX_VALUE),"Elapsed subtraction overflow");
    }

    private static void copiesDoNotRenewEachOther(){
        long start=1_700_000_000_000L,life=ClipboardRetention.RETENTION_MILLIS;
        ClipboardRetention.Entry first=new ClipboardRetention.Entry(1,start,"same text");
        ClipboardRetention.Entry later=new ClipboardRetention.Entry(2,start+life-1,"same text");
        List<ClipboardRetention.Day> days=ClipboardRetention.group(Arrays.asList(first,later),TimeZone.getTimeZone("UTC"));
        int count=0;for(ClipboardRetention.Day day:days)count+=day.entries.size();
        check(count==2,"Grouping silently deduplicated or renewed a copy");
        check(first.copiedAt==start && ClipboardRetention.expired(first.copiedAt,start+life),"A later copy renewed the first lifetime");
        check(!ClipboardRetention.expired(later.copiedAt,start+life),"Explicit new copy lost its own lifetime");
    }

    private static void localDaysAndDst()throws Exception{
        TimeZone newYork=TimeZone.getTimeZone("America/New_York"),tokyo=TimeZone.getTimeZone("Asia/Tokyo");
        ClipboardRetention.Entry before=new ClipboardRetention.Entry(1,at("2026-10-03T03:59:59Z"),"before local midnight");
        ClipboardRetention.Entry after=new ClipboardRetention.Entry(2,at("2026-10-03T04:00:00Z"),"after local midnight");
        List<ClipboardRetention.Day> days=ClipboardRetention.group(Arrays.asList(before,after),newYork);
        equal("2026-10-03",days.get(0).key);equal("2026-10-02",days.get(1).key);
        check(days.get(0).time==after.copiedAt && days.get(0).time()==after.copiedAt,"Day time must refer to newest copy");
        check(ClipboardRetention.group(Arrays.asList(before,after),tokyo).size()==1,"Timezone ignored");
        equal("2026-10-03",ClipboardRetention.key(before.copiedAt,tokyo));
        ClipboardRetention.Entry firstHour=new ClipboardRetention.Entry(3,at("2026-11-01T05:30:00Z"),"first 1:30");
        ClipboardRetention.Entry secondHour=new ClipboardRetention.Entry(4,at("2026-11-01T06:30:00Z"),"second 1:30");
        days=ClipboardRetention.group(Arrays.asList(firstHour,secondHour),newYork);
        check(days.size()==1 && days.get(0).entries.get(0)==secondHour && days.get(0).entries.get(1)==firstHour,"Repeated DST hour misordered");
        ClipboardRetention.Entry beforeJump=new ClipboardRetention.Entry(5,at("2026-03-08T06:59:59Z"),"before missing hour");
        ClipboardRetention.Entry afterJump=new ClipboardRetention.Entry(6,at("2026-03-08T07:00:00Z"),"after missing hour");
        days=ClipboardRetention.group(Arrays.asList(beforeJump,afterJump),newYork);
        check(days.size()==1 && days.get(0).entries.get(0)==afterJump,"Spring DST day split");
    }

    private static void exactValuesAndImmutableGroups(){
        String whitespace=" \t\n\r\u00a0\u2003 ";
        StringBuilder longCopy=new StringBuilder("  exact\n");for(int i=0;i<100_000;i++)longCopy.append("a😀");longCopy.append("\t end  ");
        ClipboardRetention.Entry blank=new ClipboardRetention.Entry(1,1000,whitespace),longEntry=new ClipboardRetention.Entry(2,2000,longCopy.toString());
        List<ClipboardRetention.Entry> input=new ArrayList<>(Arrays.asList(blank,longEntry));
        List<ClipboardRetention.Day> days=ClipboardRetention.group(input,TimeZone.getTimeZone("UTC"));
        check(input.get(0)==blank && input.get(1)==longEntry,"Grouping mutated source order");
        check(days.get(0).entries.size()==2,"Grouping dropped whitespace copy");
        equal(longCopy.toString(),days.get(0).entries.get(0).text);equal(whitespace,days.get(0).entries.get(1).text);
        input.clear();check(days.get(0).entries.size()==2,"Group holds a mutable input list");
        boolean blocked=false;try{days.get(0).entries.clear();}catch(UnsupportedOperationException expected){blocked=true;}check(blocked,"Day entries mutable");
        blocked=false;try{days.clear();}catch(UnsupportedOperationException expected){blocked=true;}check(blocked,"Days mutable");
        check(ClipboardRetention.group(Collections.<ClipboardRetention.Entry>emptyList(),TimeZone.getTimeZone("UTC")).isEmpty(),"Empty input has a day");
    }

    private static void stableSameMillisecondOrder(){
        ClipboardRetention.Entry oldestId=new ClipboardRetention.Entry(Long.MIN_VALUE,1000,"old ID");
        ClipboardRetention.Entry newestId=new ClipboardRetention.Entry(Long.MAX_VALUE,1000,"new ID");
        ClipboardRetention.Entry tiedFirst=new ClipboardRetention.Entry(7,1000,"first tie"),tiedSecond=new ClipboardRetention.Entry(7,1000,"second tie");
        ClipboardRetention.Entry newestTime=new ClipboardRetention.Entry(-5,1001,"newest time");
        List<ClipboardRetention.Entry> entries=ClipboardRetention.group(Arrays.asList(oldestId,tiedFirst,newestId,tiedSecond,newestTime),TimeZone.getTimeZone("UTC")).get(0).entries;
        check(entries.get(0)==newestTime && entries.get(1)==newestId && entries.get(2)==tiedFirst && entries.get(3)==tiedSecond && entries.get(4)==oldestId,"Same-ms IDs or equal ties unstable");
    }

    private static long at(String value)throws Exception{return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX",Locale.US).parse(value).getTime();}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    private static void equal(String expected,String actual){if(!expected.equals(actual))throw new AssertionError("Expected ["+expected+"] got ["+actual+"]");}
}
