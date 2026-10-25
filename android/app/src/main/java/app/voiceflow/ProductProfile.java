package app.voiceflow;

/** Android-free feature boundaries. Saved page IDs stay stable across the split. */
final class ProductProfile {
    static final int VOICE=0, CALL=1, TRANSLATE=2, DICTATION=3, THOUGHTS=4, CLIPBOARD=5;
    private ProductProfile() {}
    static int[] pages(boolean notes,boolean thoughts) {
        if(notes)return new int[]{VOICE,CALL};
        return thoughts?new int[]{DICTATION,THOUGHTS,CLIPBOARD}:new int[]{DICTATION,CLIPBOARD};
    }
    static boolean supports(boolean notes,int page) {
        return notes?(page==VOICE||page==CALL):(page==DICTATION||page==THOUGHTS||page==CLIPBOARD);
    }
    static boolean recording(boolean notes,boolean translationMode,boolean call,boolean thought) {
        if(translationMode || (call && thought))return false; // The retired Translation card has no new recording route.
        return thought?!notes:notes;
    }
    static int restore(boolean notes,boolean thoughts,int savedPage) {
        for(int page:pages(notes,thoughts))if(page==savedPage)return page;
        return pages(notes,thoughts)[0];
    }
}
