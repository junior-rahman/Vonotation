package app.voiceflow;

import java.io.IOException;

/** Bounds JSON parser work before allocating an imported archive's object tree. */
final class BackupJsonBounds {
    static final int MAX_HISTORY=100, MAX_THOUGHTS=10000;
    private static final int MAX_DEPTH=16;
    // Each thought has eight fields and each dictation has six. Include array
    // separators, then leave room for every setting, envelope and legacy field.
    private static final int MAX_SEPARATORS=MAX_THOUGHTS*8+MAX_HISTORY*6+256;

    static void check(String json) throws IOException {
        if(json==null)throw invalid();
        char[] stack=new char[MAX_DEPTH];int depth=0,separators=0;
        boolean quoted=false,escaped=false,started=false,finished=false;
        for(int i=0;i<json.length();i++) {
            char value=json.charAt(i);
            if(quoted) {
                if(value<0x20)throw invalid();
                if(escaped)escaped=false;
                else if(value=='\\')escaped=true;
                else if(value=='"')quoted=false;
                continue;
            }
            if(Character.isWhitespace(value))continue;
            if(finished)throw invalid();
            if(!started){if(value!='{')throw invalid();started=true;}
            if(value=='"')quoted=true;
            else if(value=='{' || value=='[') {
                if(depth==MAX_DEPTH)throw new IOException("The backup data is too complex.");
                stack[depth++]=value;
            } else if(value=='}' || value==']') {
                if(depth==0 || stack[depth-1]!=(value=='}'?'{':'['))throw invalid();
                if(--depth==0)finished=true;
            } else if(value==',' && ++separators>MAX_SEPARATORS)
                throw new IOException("The backup contains too many data fields.");
        }
        if(!started || !finished || depth!=0 || quoted || escaped)throw invalid();
    }
    private static IOException invalid(){return new IOException("The backup data is not readable.");}
    private BackupJsonBounds(){}
}
