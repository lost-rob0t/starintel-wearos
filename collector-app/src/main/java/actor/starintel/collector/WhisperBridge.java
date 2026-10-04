package actor.starintel.collector;

/** JNI surface for the vendored whisper.cpp build. Kept intentionally tiny. */
public final class WhisperBridge {
    static final int MAX_TRANSCRIPT_JSON_BYTES = 2 * 1_024 * 1_024;

    private WhisperBridge() {}

    public static boolean libraryAvailable() {
        try {
            System.loadLibrary("starwireless_jni");
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /**
     * Transcribes a 16kHz mono PCM16 WAV file.
     *
     * @return JSON {"ok":true,"segments":[{"start":s,"end":s,"text":"..."}]}
     * @throws RuntimeException with a bounded message when native transcription fails.
     */
    public static native String transcribe(String modelPath, String wavPath, int threads, String language);
}
