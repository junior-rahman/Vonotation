package app.voiceflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Full saved thought texts as quoted data, with stable chronological source boundaries. */
final class ThoughtDaySource {
    static String input(HistoryDays.Day day){
        StringBuilder json=document(day);
        boolean first=true;
        for(HistoryDays.Entry entry:chronological(day)){
            if(!first)json.append(',');first=false;
            json.append("{\"id\":").append(entry.id).append(",\"text\":");string(json,entry.text);json.append('}');
        }
        return json.append("]}").toString();
    }
    /** Split original text first, then group complete source objects; escaped JSON is never sliced. */
    static List<String> chunks(HistoryDays.Day day,int limit){
        if(limit<2)throw new IllegalArgumentException("Chunk limit must hold a Unicode character.");
        List<String> documents=new ArrayList<>();StringBuilder json=document(day);boolean first=true;
        for(HistoryDays.Entry entry:chronological(day)){
            List<String> pieces=HistoryDays.chunks(entry.text,limit);
            if(pieces.isEmpty())pieces=Collections.singletonList("");
            for(int segment=0;segment<pieces.size();segment++){
                StringBuilder source=new StringBuilder("{\"id\":").append(entry.id).append(",\"segment\":").append(segment+1).append(",\"segments\":").append(pieces.size()).append(",\"text\":");
                string(source,pieces.get(segment));source.append('}');
                // A single source may exceed the limit by its JSON metadata or escaping overhead.
                if(!first&&(long)json.length()+source.length()+3>limit){documents.add(json.append("]}").toString());json=document(day);first=true;}
                if(!first)json.append(',');first=false;json.append(source);
            }
        }
        if(!first||documents.isEmpty())documents.add(json.append("]}").toString());
        return documents;
    }
    private static List<HistoryDays.Entry> chronological(HistoryDays.Day day){
        List<HistoryDays.Entry> entries=new ArrayList<>(day.entries);Collections.sort(entries,(a,b)->Long.compare(a.id,b.id));return entries;
    }
    private static StringBuilder document(HistoryDays.Day day){
        StringBuilder json=new StringBuilder("{\"kind\":\"saved_quick_thoughts\",\"day\":");
        string(json,day.key);json.append(",\"timezone\":");string(json,day.zone.getID());return json.append(",\"sources\":[");
    }
    private static void string(StringBuilder json,String value){
        json.append('"');
        for(int at=0;at<value.length();at++){
            char c=value.charAt(at);
            switch(c){
                case '"':json.append("\\\"");break;
                case '\\':json.append("\\\\");break;
                case '\b':json.append("\\b");break;
                case '\f':json.append("\\f");break;
                case '\n':json.append("\\n");break;
                case '\r':json.append("\\r");break;
                case '\t':json.append("\\t");break;
                default:
                    if(c<0x20||Character.isSurrogate(c)){
                        json.append("\\u");
                        for(int shift=12;shift>=0;shift-=4)json.append("0123456789abcdef".charAt((c>>shift)&15));
                    }else json.append(c);
            }
        }
        json.append('"');
    }
    private ThoughtDaySource(){}
}
