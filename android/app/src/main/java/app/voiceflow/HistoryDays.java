package app.voiceflow;

import java.text.SimpleDateFormat;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Newest-first local-calendar history with chronological recap input and stable cache identity. */
final class HistoryDays {
    static final class Entry {
        final long id;final String text;final int words;
        /** What the speech engine returned before cleanup; equals text when unchanged or unknown. */
        final String raw;
        Entry(long id,String text,int words){this(id,text,words,text);}
        Entry(long id,String text,int words,String raw){this.id=id;this.text=text;this.words=words;this.raw=raw==null?text:raw;}
    }
    static final class Day {
        final String key;final TimeZone zone;final List<Entry> entries=new ArrayList<>();int words;
        Day(String key,TimeZone zone){this.key=key;this.zone=(TimeZone)zone.clone();}
        long time(){return entries.get(0).id;}
        String input(){SimpleDateFormat clock=new SimpleDateFormat("HH:mm",Locale.US);clock.setTimeZone(zone);StringBuilder text=new StringBuilder("Saved dictations on "+key+" ("+zone.getID()+"):\n\n");for(Entry entry:chronological(entries))text.append(clock.format(new Date(entry.id))).append("\n").append(entry.text).append("\n\n");return text.toString();}
    }
    static String key(long time,TimeZone zone){SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd",Locale.US);format.setTimeZone(zone);return format.format(new Date(time));}
    static List<Day> group(List<Entry> input,TimeZone zone){
        List<Entry> sorted=new ArrayList<>(input);Collections.sort(sorted,(a,b)->Long.compare(b.id,a.id));Map<String,Day> days=new TreeMap<>(Collections.reverseOrder());
        for(Entry entry:sorted){if(entry.text.trim().isEmpty())continue;String key=key(entry.id,zone);Day day=days.get(key);if(day==null){day=new Day(key,zone);days.put(key,day);}day.entries.add(entry);day.words+=entry.words;}
        return new ArrayList<>(days.values());
    }
    static String fingerprint(Day day,String model,String endpoint){
        return fingerprint(day,model,endpoint,"");
    }
    /** Thought recaps include both the current transcript and its original source. */
    static String fingerprint(Day day,String model,String endpoint,String scope){
        boolean thought="thought".equals(scope);
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");for(String part:new String[]{thought?"thought-daily-recap-v1":"daily-recap-v1",day.key,day.zone.getID(),model,endpoint}){digest.update(part.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}for(Entry entry:chronological(day.entries)){digest.update(Long.toString(entry.id).getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(entry.text.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);if(thought){digest.update(entry.raw.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);}}StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.US,"%02x",b&255));return result.toString();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static List<Entry> chronological(List<Entry> entries){List<Entry> sorted=new ArrayList<>(entries);Collections.sort(sorted,(a,b)->Long.compare(a.id,b.id));return sorted;}
    static List<String> chunks(String text,int limit){
        List<String> parts=new ArrayList<>();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+limit);if(end<text.length()){int boundary=text.lastIndexOf('\n',end-1);if(boundary>start+limit/2)end=boundary+1;if(Character.isHighSurrogate(text.charAt(end-1)))end--;}parts.add(text.substring(start,end));start=end;}return parts;
    }
    private HistoryDays(){}
}
