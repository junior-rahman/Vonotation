package app.voiceflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

/** A day projection that retains unfinished thoughts without sending them to recaps. */
final class ThoughtTimeline {
    static final class Item {
        final long id;
        final String title, text, raw;
        final long revision;
        final boolean recording, pending, interrupted;

        Item(long id,String title,String text,String raw,long revision,boolean recording,boolean pending,boolean interrupted) {
            this.id=id;
            this.title=value(title);
            this.text=value(text);
            this.raw=value(raw);
            this.revision=revision;
            this.recording=recording;
            this.pending=pending;
            this.interrupted=interrupted;
        }

        String display() {
            if(recording)return "Recording…";
            if(pending)return "Source saved. Waiting for transcription.";
            if(!title.trim().isEmpty())return title;
            return interrupted?"Recording interrupted.":"Quick thought";
        }

        /** Latest full body, with the original transcript as a legacy fallback. */
        String source() {
            if(recording||pending)return "";
            if(!text.trim().isEmpty())return text;
            return raw.trim().isEmpty()?"":raw;
        }

        private String original() {
            return raw.trim().isEmpty()?source():raw;
        }
    }

    static final class Day {
        final String key;
        final TimeZone zone;
        final List<Item> items;
        /** Null when the day has no completed, nonempty thought body. */
        final HistoryDays.Day recap;

        private Day(String key,TimeZone zone,List<Item> items) {
            this.key=key;
            this.zone=(TimeZone)zone.clone();
            this.items=Collections.unmodifiableList(new ArrayList<>(items));
            HistoryDays.Day completed=new HistoryDays.Day(key,zone);
            for(Item item:items) {
                String source=item.source();
                if(source.isEmpty())continue;
                int words=source.trim().split("\\s+").length;
                completed.entries.add(new HistoryDays.Entry(item.id,source,words,item.original()));
                completed.words+=words;
            }
            recap=completed.entries.isEmpty()?null:completed;
        }

        long time(){return items.get(0).id;}
    }

    static List<Day> group(List<Item> input,TimeZone zone) {
        TimeZone local=(TimeZone)(zone==null?TimeZone.getDefault():zone).clone();
        List<Item> sorted=new ArrayList<>();
        if(input!=null)for(Item item:input)if(item!=null)sorted.add(item);
        Collections.sort(sorted,(a,b)->Long.compare(b.id,a.id));
        Map<String,List<Item>> grouped=new TreeMap<>(Collections.reverseOrder());
        for(Item item:sorted) {
            String key=HistoryDays.key(item.id,local);
            List<Item> items=grouped.get(key);
            if(items==null){items=new ArrayList<>();grouped.put(key,items);}
            items.add(item);
        }
        List<Day> days=new ArrayList<>();
        for(Map.Entry<String,List<Item>> entry:grouped.entrySet())days.add(new Day(entry.getKey(),local,entry.getValue()));
        return days;
    }

    private static String value(String text){return text==null?"":text;}
    private ThoughtTimeline(){}
}
