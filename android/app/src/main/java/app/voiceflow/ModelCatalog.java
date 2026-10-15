package app.voiceflow;

/** Short display names stay separate from the exact provider model IDs. */
final class ModelCatalog {
    static final String[] TRANSCRIPT_NAMES={"Large v3","Turbo"};
    static final String[] TRANSCRIPT_IDS={"whisper-large-v3","whisper-large-v3-turbo"};
    static final String[] CLEANUP_NAMES={"OSS 20B","OSS 120B","Instant","Versatile"};
    static final String[] CLEANUP_IDS={"openai/gpt-oss-20b","openai/gpt-oss-120b","llama-3.1-8b-instant","llama-3.3-70b-versatile"};
    private ModelCatalog() {}
}
