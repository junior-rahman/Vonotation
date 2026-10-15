package app.voiceflow;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.nio.ByteBuffer;

/** Writes 16-bit PCM into an m4a the rest of the app already knows how to play and transcribe. */
final class AacFile {
    private final MediaCodec codec;
    private final MediaMuxer muxer;
    private final int rate,channels,frame;
    private final MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
    private int track=-1;
    private long samples;
    private boolean muxing;

    AacFile(File dest,int rate,int channels) throws Exception {
        this.rate=rate;this.channels=Math.max(1,channels);this.frame=2*this.channels;
        MediaFormat format=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,rate,this.channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE,64000);
        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        muxer=new MediaMuxer(dest.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    void write(byte[] pcm,int length) {
        int offset=0,spins=0;
        while(offset+frame<=length){
            int in=codec.dequeueInputBuffer(10000);
            if(in<0){drain(0);if(++spins>20)break;continue;}
            spins=0;
            ByteBuffer buf=codec.getInputBuffer(in);
            if(buf==null)continue;
            buf.clear();
            int n=Math.min(buf.remaining()/frame*frame,length-offset);
            n-=n%frame;
            if(n<=0)break;
            buf.put(pcm,offset,n);
            long pts=samples*1000000L/rate;
            samples+=n/frame;
            offset+=n;
            codec.queueInputBuffer(in,0,n,pts,0);
            drain(0);
        }
    }

    void close() {
        try {
            int in=codec.dequeueInputBuffer(10000);
            if(in>=0)codec.queueInputBuffer(in,0,0,samples*1000000L/rate,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(20000);
        } catch(Exception ignored) {}
        try{codec.stop();}catch(Exception ignored){}
        try{codec.release();}catch(Exception ignored){}
        try{if(muxing)muxer.stop();}catch(Exception ignored){}
        try{muxer.release();}catch(Exception ignored){}
    }

    private void drain(long wait) {
        while(true){
            int out=codec.dequeueOutputBuffer(info,wait);
            if(out==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                if(!muxing){track=muxer.addTrack(codec.getOutputFormat());muxer.start();muxing=true;}
            } else if(out>=0){
                ByteBuffer buf=codec.getOutputBuffer(out);
                if(muxing && buf!=null && info.size>0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){
                    buf.position(info.offset);buf.limit(info.offset+info.size);
                    muxer.writeSampleData(track,buf,info);
                }
                codec.releaseOutputBuffer(out,false);
                if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)return;
            } else return;
        }
    }
}
