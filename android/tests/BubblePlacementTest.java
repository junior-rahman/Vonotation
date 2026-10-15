package app.voiceflow;

public final class BubblePlacementTest {
    public static void main(String[] args){
        for(int screen:new int[]{360,412,904})for(int d:new int[]{48,56,67}){
            int anchorX=screen-16-d/2,anchorY=380;
            int[][] sizes={{d+8,d+8},{d+96,2*d+16},{d+8,3*d+24}};
            for(int[] size:sizes){int width=size[0],height=size[1];
                BubblePlacement controls=new BubblePlacement(screen,32,850,width,height,d,anchorX+4,anchorY-4);
                check(controls.x+width-4-(d+1)/2==anchorX);
                check(controls.y+4+d/2==anchorY);
                BubblePlacement moved=new BubblePlacement(screen,32,850,width,height,d,anchorX-24,anchorY-84);
                check(moved.x==controls.x-28 && moved.y==controls.y-80);
                BubblePlacement edge=new BubblePlacement(screen,32,500,width,height,d,-100,1000);
                check(edge.x==0 && edge.y+height==500);
            }
        }
        System.out.println("PASS: vertical controls retain the mic anchor across idle, recording and editing at all sizes; dragging and screen/keyboard bounds stay clamped");
    }
    static void check(boolean okay){if(!okay)throw new AssertionError("Invalid vertical control placement");}
}
