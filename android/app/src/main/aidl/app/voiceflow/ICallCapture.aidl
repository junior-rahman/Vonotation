package app.voiceflow;

interface ICallCapture {
    /** Opens the call audio. Returns sampleRate | (channels << 24), or 0 if the phone refused. */
    int open(in ParcelFileDescriptor sink);
    void close();
}
