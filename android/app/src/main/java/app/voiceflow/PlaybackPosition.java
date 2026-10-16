package app.voiceflow;

/** A seek belongs to the whole recording, even when storage is split into segments. */
final class PlaybackPosition {
    final int index;final long offset,before;
    private PlaybackPosition(int index,long offset,long before){this.index=index;this.offset=offset;this.before=before;}
    static PlaybackPosition locate(long[] spans,long requested){
        if(spans==null||spans.length==0)return new PlaybackPosition(0,0,0);
        long total=0;for(long span:spans)total+=Math.max(0,span);
        long position=Math.max(0,Math.min(Math.max(0,total-1),requested)),before=0;
        for(int i=0;i<spans.length;i++){long span=Math.max(0,spans[i]);if(position<before+span||i==spans.length-1)return new PlaybackPosition(i,Math.max(0,position-before),before);before+=span;}
        return new PlaybackPosition(0,0,0);
    }
}
