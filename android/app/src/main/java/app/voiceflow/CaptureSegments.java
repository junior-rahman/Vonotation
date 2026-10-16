package app.voiceflow;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Keeps every surviving source segment while separately tracking whether capture finalized cleanly. */
final class CaptureSegments {
    private final ArrayList<File> files=new ArrayList<>();
    private boolean complete=true;

    /** A failed or empty segment makes the whole capture incomplete, even if earlier files survived. */
    synchronized boolean sealed(File file,boolean finalized) {
        boolean hasBytes=file!=null && file.isFile() && file.length()>0;
        if(!finalized || !hasBytes)complete=false;
        if(hasBytes && !files.contains(file))files.add(file);
        return finalized && hasBytes;
    }

    synchronized void interrupted(){complete=false;}
    synchronized boolean complete(){
        if(!complete || files.isEmpty())return false;
        for(File file:files)if(!file.isFile() || file.length()==0)return false;
        return true;
    }
    synchronized List<File> files(){return new ArrayList<>(files);}
    synchronized void clear(){files.clear();}
}
