package app.voiceflow;

import java.text.Normalizer;
import java.util.Locale;

final class NotebookPolicy {
    static boolean matches(String query,String... fields){
        String q=normalize(query).trim();if(q.isEmpty())return true;
        StringBuilder hay=new StringBuilder();for(String field:fields)hay.append(' ').append(normalize(field));
        for(String word:q.split("\\s+"))if(!hay.toString().contains(word))return false;
        return true;
    }
    private static String normalize(String value){return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    static String status(boolean recording,boolean paused,boolean source,boolean interrupted,boolean working,boolean partial,boolean failed,boolean pending,boolean key,boolean online){
        if(recording)return paused?"Paused · tap Resume or Finish":"Recording · not finished";
        if(!source)return "Source audio unavailable";
        if(interrupted)return "Interrupted · inspect saved audio";
        if(working)return "Audio saved · transcribing";
        if(partial)return "Audio saved · partial transcript";
        if(failed)return "Audio saved · retry needed";
        if(pending)return !key?"Audio saved · add provider key":!online?"Audio saved · waiting for connection":"Audio saved · waiting to transcribe";
        return "Saved";
    }
    private NotebookPolicy(){}
}
