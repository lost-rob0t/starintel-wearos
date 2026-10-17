package actor.starintel.quasar.importing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class BulkImportTransportTest {
    @Test public void compatibilityAdapterCallsOnceAndProjectsReceipt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        BulkImportTransport t = new BulkImportTransport(records -> {
            calls.incrementAndGet(); assertEquals(1, records.length());
            return new JSONObject().put("total", 1).put("succeeded", 1).put("failed", 0)
                    .put("correlation_id", "r1").put("ignored", "x".repeat(100_000));
        });
        byte[] response = t.send("[{}]".getBytes(StandardCharsets.UTF_8));
        assertTrue(response.length < 100); assertEquals(1, calls.get());
        assertFalse(new JSONObject(new String(response, StandardCharsets.UTF_8)).has("ignored"));
    }
    @Test public void noAutomaticRetryAndBoundsBeforeClientCall() {
        AtomicInteger calls = new AtomicInteger();
        BulkImportTransport t = new BulkImportTransport(records -> { calls.incrementAndGet(); throw new IOException("unknown"); });
        assertThrows(IOException.class, () -> t.send("[{}]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, calls.get());
        assertThrows(IOException.class, () -> t.send(new byte[StreamingImport.MAX_BATCH_BYTES+1]));
        assertThrows(IOException.class, () -> t.send("[]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, calls.get());
    }
}
