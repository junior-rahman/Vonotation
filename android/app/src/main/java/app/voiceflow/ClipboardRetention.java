package app.voiceflow;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/** Exact clipboard values with a fixed lifetime and newest-first local calendar grouping. */
final class ClipboardRetention {
    static final long RETENTION_MILLIS=30L*24*60*60*1000;

    static final class Entry {
        final long id,copiedAt;
        final String text;
        Entry(long id,long copiedAt,String text){
            this.id=id;this.copiedAt=copiedAt;this.text=Objects.requireNonNull(text,"text");
        }
    }

    static final class Day {
        final String key;
        /** Timestamp of the newest copy in this day, not a synthetic midnight. */
        final long time;
        final List<Entry> entries;
        private Day(String key,List<Entry> entries){
            this.key=key;this.time=entries.get(0).copiedAt;
            this.entries=Collections.unmodifiableList(new ArrayList<>(entries));
        }
        long time(){return time;}
    }

    /** A clock rollback does not age or renew a copy; expiry includes the exact 30-day boundary. */
    static boolean expired(long copiedAt,long now){
        if(now<copiedAt || copiedAt>Long.MAX_VALUE-RETENTION_MILLIS)return false;
        return now>=copiedAt+RETENTION_MILLIS;
    }

    static String key(long copiedAt,TimeZone zone){
        SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);
        format.setTimeZone(Objects.requireNonNull(zone,"zone"));
        return format.format(new Date(copiedAt));
    }

    /** Grouping never trims, filters, deduplicates, or renews a clipboard entry. */
    static List<Day> group(List<Entry> input,TimeZone zone){
        TimeZone localZone=(TimeZone)Objects.requireNonNull(zone,"zone").clone();
        List<Entry> sorted=new ArrayList<>(Objects.requireNonNull(input,"input"));
        Collections.sort(sorted,new Comparator<Entry>(){
            @Override public int compare(Entry a,Entry b){
                int time=Long.compare(b.copiedAt,a.copiedAt);
                return time!=0?time:Long.compare(b.id,a.id);
            }
        });
        Map<String,List<Entry>> grouped=new LinkedHashMap<>();
        for(Entry entry:sorted){
            String dayKey=key(entry.copiedAt,localZone);
            List<Entry> entries=grouped.get(dayKey);
            if(entries==null){entries=new ArrayList<>();grouped.put(dayKey,entries);}
            entries.add(entry);
        }
        List<Day> days=new ArrayList<>();
        for(Map.Entry<String,List<Entry>> day:grouped.entrySet())days.add(new Day(day.getKey(),day.getValue()));
        return Collections.unmodifiableList(days);
    }

    private ClipboardRetention(){}
}
