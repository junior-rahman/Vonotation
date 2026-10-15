package app.voiceflow;

final class BubbleSize {
    static float scale(String size){return "large".equals(size)?1.2f:"medium".equals(size)?1f:.8f;}
    private BubbleSize(){}
}
