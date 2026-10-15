package app.voiceflow;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Temporary private copies of cloud recordings whose transcription failed. Stored in
 * no-backup app storage, never in History, Drive backups or recaps. Kept for 24 hours
 * (at most three) until Retry succeeds or the user discards them.
 */
final class RetryBuffer {
    static final long KEEP_MS=24L*60*60*1000;
    static final int MAX=3;
    private RetryBuffer() {}
    static File dir(Context c){File dir=new File(c.getNoBackupFilesDir(),"retry");if(!dir.isDirectory())dir.mkdirs();return dir;}
    /** Moves a finished recording into the buffer. Returns the kept file, or null if it could not be kept. */
    static synchronized File keep(Context c,File audio) {return keep(c,audio,false);}
    static synchronized File keep(Context c,File audio,boolean thought) {
        if(audio==null || !audio.isFile() || audio.length()==0)return null;
        prune(c);
        File target=new File(dir(c),(thought?"retry-thought-":"retry-")+System.currentTimeMillis()+".m4a");
        boolean moved=audio.renameTo(target);
        if(!moved){
            try(InputStream in=new FileInputStream(audio);OutputStream out=new FileOutputStream(target)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);moved=true;}
            catch(Exception failed){target.delete();return null;}
            audio.delete();
        }
        List<File> all=list(c);
        for(int i=MAX;i<all.size();i++)all.get(i).delete();
        return target;
    }
    /** Newest first; expired files are removed. */
    static synchronized List<File> list(Context c) {
        prune(c);
        File[] files=dir(c).listFiles((d,name)->name.startsWith("retry-") && name.endsWith(".m4a"));
        List<File> out=new ArrayList<>(files==null?new ArrayList<>():Arrays.asList(files));
        out.sort((a,b)->Long.compare(time(b),time(a)));return out;
    }
    static File latest(Context c){for(File file:list(c))if(!thought(file) || Store.quickThoughts(c))return file;return null;}
    static boolean thought(File file){return file!=null && file.getName().startsWith("retry-thought-");}
    static long time(File f){try{return Long.parseLong(f.getName().replace("retry-thought-","").replace("retry-","").replace(".m4a",""));}catch(NumberFormatException e){return f.lastModified();}}
    static synchronized void discard(File f){if(f!=null)f.delete();}
    static synchronized void prune(Context c) {
        File[] files=dir(c).listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-KEEP_MS;
        for(File f:files)if(time(f)<cutoff)f.delete();
    }
}
