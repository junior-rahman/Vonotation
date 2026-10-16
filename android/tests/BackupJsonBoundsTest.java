package app.voiceflow;

/** Parser allocation guard, sized against the actual portable schema. */
public final class BackupJsonBoundsTest {
    public static void main(String[] args) throws Exception {
        String mixed=archive(300,100);
        BackupJsonBounds.check(mixed);
        BackupJsonBounds.check(archive(BackupJsonBounds.MAX_THOUGHTS,BackupJsonBounds.MAX_HISTORY));
        BackupJsonBounds.check(" {\"text\":\"braces { } [ ], comma, escaped \\\" quotation \\\\ slash\"} ");
        reject("{\"thoughts\":[}]}");
        reject("{\"thoughts\":[]}} ");
        reject("{} {}");
        reject("[]");
        reject("{\"text\":\"unfinished}");
        reject("{\"text\":\"control\ncharacter\"}");
        reject("{\"text\":\"trailing escape\\");
        StringBuilder deep=new StringBuilder("{");for(int i=0;i<16;i++)deep.append("\"nested\":{");for(int i=0;i<17;i++)deep.append('}');reject(deep.toString());
        StringBuilder broad=new StringBuilder("{\"many\":[0");for(int i=0;i<90000;i++)broad.append(",0");broad.append("]}");reject(broad.toString());
        System.out.println("PASS: 300 and 10,000 thoughts with 100 dictations fit parser bounds; quoted punctuation, malformed nesting, trailing data, incomplete strings, depth and allocation limits checked");
    }
    static String archive(int thoughts,int history) {
        StringBuilder json=new StringBuilder("{\"format\":\"voiceflow-backup\",\"schema\":1,\"created_at\":1700000000000,\"settings\":{\"quick_thoughts\":true,\"notes_polish_model\":\"openai/gpt-oss-120b\"},\"history\":[");
        for(int i=0;i<history;i++){if(i>0)json.append(',');json.append("{\"id\":").append(i+1).append(",\"text\":\"A dictation\",\"raw\":\"A dictation\",\"duration\":1000,\"words\":2}");}
        json.append("],\"thoughts\":[");
        for(int i=0;i<thoughts;i++){if(i>0)json.append(',');json.append("{\"id\":").append(i+1).append(",\"created\":1700000000000,\"text\":\"A saved thought\",\"raw\":\"A saved thought\",\"title\":\"A title\",\"summary\":\"Summary, with punctuation { [ ] }\",\"duration\":1000,\"lang\":\"en\"}");}
        return json.append("],\"thoughts_included\":true,\"draft\":\"Draft words\"}").toString();
    }
    private static void reject(String json) throws Exception {try{BackupJsonBounds.check(json);}catch(java.io.IOException expected){return;}throw new AssertionError("Unsafe JSON guard input was accepted");}
}
