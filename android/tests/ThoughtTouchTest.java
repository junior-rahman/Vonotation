package app.voiceflow;

public final class ThoughtTouchTest {
    public static void main(String[] args){
        ThoughtTouch touch=new ThoughtTouch();
        require(touch.finish(false)==ThoughtTouch.NONE,"A stray release must not activate a thought");
        touch.begin();require(!touch.move(3,4,5),"Exactly at touch slop is still a tap");
        require(touch.finish(false)==ThoughtTouch.TAP,"A steady tap must activate once");
        require(touch.finish(false)==ThoughtTouch.NONE,"Duplicate release must not activate again");
        touch.begin();require(touch.hold(),"First valid hold must open saved thoughts");
        require(!touch.hold(),"A repeated hold callback must not pulse or activate again");
        require(!touch.move(100,100,5)&&!touch.dragging(),"A completed hold cannot turn into a drag");
        require(touch.finish(false)==ThoughtTouch.NONE,"Releasing a hold must not also record");
        touch.begin();require(touch.move(6,0,5),"Crossing slop starts a drag and its feedback");
        require(!touch.move(40,50,5)&&touch.dragging(),"Moving an active drag must not pulse again");
        require(!touch.hold(),"A drag must cancel the pending hold");
        require(touch.finish(false)==ThoughtTouch.DRAG_END,"Dropping a drag must save its anchor");
        require(!touch.dragging(),"A completed drag must not keep moving");
        touch.begin();touch.cancel();require(!touch.hold()&&!touch.move(50,50,5),"A second pointer cancels activation and drag feedback");
        require(touch.finish(false)==ThoughtTouch.NONE,"A cancelled multi-touch must not record");
        touch.begin();require(touch.finish(true)==ThoughtTouch.NONE,"An Android cancellation must not record");
        touch.begin();touch.move(12,0,5);touch.cancel();require(!touch.dragging()&&touch.finish(false)==ThoughtTouch.NONE,"Cancellation must not save a partial drag");
        touch.begin();require(touch.finish(false)==ThoughtTouch.TAP,"A fresh gesture must work after cancellation");
        System.out.println("PASS: (tap, hold, drag threshold, cancellation and single feedback events)");
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
