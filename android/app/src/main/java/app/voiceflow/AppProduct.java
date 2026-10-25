package app.voiceflow;

/** The two APKs have separate Android identities and private storage sandboxes. */
final class AppProduct {
    private AppProduct() {}
    static boolean isNotes(){return "app.vonotation.notes".equals(BuildConfig.APPLICATION_ID);}
    static String name(){return isNotes()?"Vocading":"Voitation";}
    static int[] pages(boolean thoughts){return ProductProfile.pages(isNotes(),thoughts);}
    static boolean supports(int page){return ProductProfile.supports(isNotes(),page);}
    static boolean backupEnabled(){return false;} // On hold; saved account/configuration remains intact.
}
