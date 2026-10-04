package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import org.junit.Test;

public class WhisperModelStoreTest {
    @Test
    public void defaultPinIsHttpsSha256AndBounded() {
        assertTrue(WhisperModelStore.DEFAULT_URL.startsWith("https://"));
        assertTrue(WhisperModelStore.DEFAULT_SHA256.matches("[0-9a-f]{64}"));
        assertTrue(WhisperModelStore.DEFAULT_SIZE_BYTES > 0L);
        assertTrue(WhisperModelStore.DEFAULT_SIZE_BYTES <= WhisperModelStore.MAX_MODEL_BYTES);
    }

    @Test
    public void rejectsPlaintextDownloads() {
        assertThrows(IOException.class, () -> WhisperModelStore.requireHttps("http://models.example/m.bin"));
    }

    @Test
    public void modelFileSanitizesTags() {
        WhisperModelStore store = new WhisperModelStore(new java.io.File("/tmp/whisper-test"));
        assertEquals(new java.io.File("/tmp/whisper-test/whisper-models/ggml-base.en-q5_1.bin"),
                store.modelFile(WhisperModelStore.DEFAULT_TAG));
        assertThrows(IllegalArgumentException.class, () -> store.modelFile("../escape"));
    }

    @Test
    public void isDownloadedFalseWhenMissing() {
        WhisperModelStore store = new WhisperModelStore(
                new java.io.File("/tmp/whisper-test-" + System.nanoTime()));
        assertFalse(store.isDownloaded(WhisperModelStore.DEFAULT_TAG));
    }
}
