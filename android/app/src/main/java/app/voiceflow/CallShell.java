package app.voiceflow;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.ParcelFileDescriptor;
import java.io.OutputStream;

/** Runs inside Shizuku's shell process, which is allowed to open the phone call itself. */
public final class CallShell extends ICallCapture.Stub {
    private volatile boolean go;
    private AudioRecord audio;
    private Thread thread;

    public void destroy() { close(); System.exit(0); }

    public static void main(String[] args) {
        CallShell shell=new CallShell();
        int[] picked=shell.arm();
        if(picked==null)System.exit(2);
        try {
            java.io.FileOutputStream out=new java.io.FileOutputStream(java.io.FileDescriptor.out);
            out.write(new byte[]{'V','F'});
            out.write(new byte[]{(byte)(picked[0]>>24),(byte)(picked[0]>>16),(byte)(picked[0]>>8),(byte)picked[0]});
            out.write(new byte[]{0,0,0,(byte)picked[1]});
            out.flush();
            byte[] buf=new byte[4096];
            while(shell.audio!=null){
                int n=shell.audio.read(buf,0,buf.length);
                if(n>0){out.write(buf,0,n);out.flush();}
                else if(n<0)break;
            }
        } catch(Exception ignored) {}
        shell.close();
    }

    @Override public int open(ParcelFileDescriptor sink) {
        close();
        int[] picked=arm();
        if(picked==null){try{sink.close();}catch(Exception ignored){} return 0;}
        go=true;
        thread=new Thread(()->pump(sink),"call-audio");
        thread.start();
        return picked[0] | (picked[1]<<24);
    }

    @Override public void close() {
        go=false;
        Thread worker=thread;thread=null;
        AudioRecord live=audio;audio=null;
        if(live!=null){try{live.stop();}catch(Exception ignored){} try{live.release();}catch(Exception ignored){}}
        if(worker!=null)try{worker.join(1500);}catch(InterruptedException ignored){}
    }

    private int[] arm() {
        int[][] tries={{48000,2},{48000,1},{16000,1},{8000,1},{44100,1}};
        for(int[] pick:tries){
            AudioRecord next=null;
            try {
                int mask=pick[1]==2?AudioFormat.CHANNEL_IN_STEREO:AudioFormat.CHANNEL_IN_MONO;
                int min=AudioRecord.getMinBufferSize(pick[0],mask,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)continue;
                next=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_CALL)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(pick[0]).setChannelMask(mask).build())
                    .setBufferSizeInBytes(min*2).build();
                if(next.getState()!=AudioRecord.STATE_INITIALIZED){next.release();continue;}
                next.startRecording();
                if(next.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING){next.release();continue;}
                audio=next;
                return pick;
            } catch(Exception error) {
                if(next!=null)try{next.release();}catch(Exception ignored){}
            }
        }
        return null;
    }

    private void pump(ParcelFileDescriptor sink) {
        try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(sink)){
            byte[] buf=new byte[4096];
            while(go && audio!=null){
                int n=audio.read(buf,0,buf.length);
                if(n>0)out.write(buf,0,n);
                else if(n<0)break;
            }
        } catch(Exception ignored) {}
        AudioRecord live=audio;audio=null;
        if(live!=null){try{live.stop();}catch(Exception ignored){} try{live.release();}catch(Exception ignored){}}
    }
}
