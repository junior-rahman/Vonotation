package app.voiceflow;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Recorder-state regressions use local synthetic bytes, not a real microphone or encoded media. */
public final class CaptureSegmentsTest {
    public static void main(String[] args) throws Exception {
        File directory=new File(System.getProperty("java.io.tmpdir"),"capture-segments-"+System.nanoTime());
        if(!directory.mkdirs())throw new AssertionError("Cannot create test folder");
        try {
            File first=bytes(directory,"first.m4a");
            File finalPart=bytes(directory,"last.m4a");
            File empty=new File(directory,"empty.m4a");
            if(!empty.createNewFile())throw new AssertionError("Cannot create empty fixture");

            CaptureSegments normal=new CaptureSegments();
            check(!normal.complete(),"No source is never a complete capture");
            check(normal.sealed(first,true),"First finalized source should be accepted");
            check(normal.sealed(finalPart,true),"Finalized tail should be accepted");
            check(normal.complete(),"Every finalized source can be marked complete");
            check(normal.files().size()==2,"All completed sources are handed off");
            File removed=bytes(directory,"removed.m4a");
            CaptureSegments removedSource=new CaptureSegments();removedSource.sealed(removed,true);removed.delete();
            check(!removedSource.complete(),"A source removed before handoff cannot certify completeness");

            CaptureSegments brokenTail=new CaptureSegments();
            brokenTail.sealed(first,true);
            check(!brokenTail.sealed(finalPart,false),"stop failure must not certify its bytes");
            check(!brokenTail.complete(),"Earlier valid audio cannot conceal failed finalization");
            check(brokenTail.files().size()==2,"Nonempty failed tail must remain recoverable");

            CaptureSegments emptyTail=new CaptureSegments();
            emptyTail.sealed(first,true);
            emptyTail.sealed(empty,false);
            check(!emptyTail.complete(),"Empty failed tail must not certify the earlier subset");
            check(emptyTail.files().size()==1,"Empty output is excluded without dropping earlier audio");

            CaptureSegments reopenFailure=new CaptureSegments();
            reopenFailure.sealed(first,true);
            reopenFailure.interrupted();
            check(!reopenFailure.complete(),"A next-segment start failure is incomplete");
            check(reopenFailure.files().size()==1,"Rotation failure preserves completed segments");
            reopenFailure.sealed(finalPart,true);
            check(!reopenFailure.complete(),"Later valid files cannot reset an interruption");
            reopenFailure.sealed(first,true);
            check(reopenFailure.files().size()==2,"Repeated finalization does not duplicate a source");
            List<File> snapshot=reopenFailure.files();snapshot.clear();
            check(reopenFailure.files().size()==2,"Callbacks cannot mutate owned source tracking");
            System.out.println("PASS: normal, failed and empty tails, rotation failure, sticky state, source retention");
        } finally {
            File[] files=directory.listFiles();if(files!=null)for(File file:files)file.delete();directory.delete();
        }
    }

    private static File bytes(File folder,String name) throws Exception {
        File file=new File(folder,name);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2,3});}
        return file;
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
