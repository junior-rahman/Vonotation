package app.voiceflow;

/** Keep the microphone at the right of attached controls; clamp to screen and keyboard bounds. */
final class BubblePlacement {
    final int x,y;
    BubblePlacement(int screenWidth,int top,int bottom,int width,int height,int diameter,int anchorX,int anchorY){
        x=Math.max(0,Math.min(screenWidth-width,anchorX-diameter/2-(width-diameter)));
        y=Math.max(top,Math.min(bottom-height,anchorY-diameter/2));
    }
}
