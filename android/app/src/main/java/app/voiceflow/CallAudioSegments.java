package app.voiceflow;

import java.io.File;

/** Every transport attempt owns a separate file, including failed connections with partial audio. */
final class CallAudioSegments {
    private CallAudioSegments(){}
    static File attempt(File base,int attempt){
        String name=base.getName();
        int dot=name.lastIndexOf('.');
        String stem=dot<0?name:name.substring(0,dot);
        String suffix=dot<0?".m4a":name.substring(dot);
        return new File(base.getParentFile(),stem+"-"+Math.max(0,attempt)+suffix);
    }
}
