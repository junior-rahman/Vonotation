package app.voiceflow;

/** One action per thought-control gesture, independent of Android event timing. */
final class ThoughtTouch {
    static final int NONE=0,TAP=1,DRAG_END=2;
    private boolean down,moved,held,cancelled;
    void begin(){down=true;moved=false;held=false;cancelled=false;}
    /** Returns true only at the first real drag threshold, for one direct feedback pulse. */
    boolean move(float dx,float dy,int slop){
        if(!down || held || cancelled || moved || dx*dx+dy*dy<=slop*(float)slop)return false;
        moved=true;return true;
    }
    boolean dragging(){return down && moved && !held && !cancelled;}
    boolean hold(){if(!down || moved || held || cancelled)return false;held=true;return true;}
    void cancel(){cancelled=true;}
    int finish(boolean aborted){
        if(!down)return NONE;
        down=false;
        if(aborted || cancelled || held)return NONE;
        return moved?DRAG_END:TAP;
    }
}
