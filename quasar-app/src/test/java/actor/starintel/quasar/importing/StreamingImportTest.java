package actor.starintel.quasar.importing;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class StreamingImportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private File source(String value) throws Exception {
        File f = folder.newFile(); Files.write(f.toPath(), value.getBytes(StandardCharsets.UTF_8)); return f;
    }
    private File checkpoint() { return new File(folder.getRoot(), "checkpoint"); }
    private byte[] ok(int n) { return ("{\"total\":"+n+",\"succeeded\":"+n+",\"failed\":0,\"correlation_id\":\"receipt-1\"}").getBytes(StandardCharsets.UTF_8); }
    private StreamingImport.Result run(File f, StreamingImport.Transport t) throws Exception {
        return new StreamingImport().run(f, checkpoint(), "https://star.test|principal-1", t, () -> false, r -> {});
    }
    @Test public void millionRecordsUnderFixedHeapAndBackpressure() throws Exception {
        File f = folder.newFile();
        try (BufferedWriter writer = Files.newBufferedWriter(f.toPath())) {
            for (int i=0;i<1_000_000;i++) writer.write("{\"n\":" + i + ",\"value\":\"" + "x".repeat(100) + "\"}\n");
        }
        AtomicInteger calls = new AtomicInteger(), live = new AtomicInteger();
        StreamingImport.Result r = run(f, b -> {
            assertEquals(1, live.incrementAndGet()); assertTrue(b.length <= StreamingImport.MAX_BATCH_BYTES);
            JSONArray records = new JSONArray(new String(b, StandardCharsets.UTF_8));
            assertTrue(records.length() <= 10); calls.incrementAndGet(); live.decrementAndGet(); return ok(records.length());
        });
        assertEquals("complete", r.state); assertEquals(1_000_000, r.verifiedAccepted); assertEquals(100_000, calls.get());
    }
    @Test public void utf8ByteBoundSplitsBatchAndArrayResume() throws Exception {
        File f = source("[ {\"s\":\"éé\"}, {\"s\":\"éé\"}, {\"s\":\"éé\"} ]");
        StreamingImport importer = new StreamingImport(20, 25, 10);
        AtomicBoolean stop = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        StreamingImport.Result first = importer.run(f, checkpoint(), "scope", b -> {
            assertTrue(b.length <= 25); calls.incrementAndGet(); return ok(1);
        }, stop::get, r -> stop.set(true));
        assertEquals(1, first.verifiedAccepted); assertEquals("ready", first.state);
        StreamingImport.Result rest = importer.run(f, checkpoint(), "scope", b -> { calls.incrementAndGet(); return ok(1); }, () -> false, r -> {});
        assertEquals(3, rest.verifiedAccepted); assertEquals(3, calls.get());
    }
    @Test public void hugeRecordFailsBeforeSending() throws Exception {
        File f = source("{\"s\":\"" + "é".repeat(70_000) + "\"}");
        assertThrows(IOException.class, () -> run(f, b -> { fail("must not send oversized record"); return null; }));
    }
    @Test public void invalidGrammarAndUtf8NeverSent() throws Exception {
        for (String s : new String[]{"[{\"a\":01}]", "[{\"a\":NaN}]", "[{'a':1}]", "[{\"a\":1},]", "{\"a\":1} {}", "{\"a\":\n1}", "[{\"a\":true} garbage]", "[1]", "{\"a\":\"bad\ntext\"}"}) {
            assertThrows(s, IOException.class, () -> run(source(s), b -> { fail("invalid source sent"); return null; }));
        }
        File bad = source("{}"); Files.write(bad.toPath(), new byte[]{'{','"','x','"',':','"',(byte)0xff,'"','}'});
        assertThrows(IOException.class, () -> run(bad, b -> { fail(); return null; }));
    }
    @Test public void partialReceiptBlocksRestartWithoutReplay() throws Exception {
        File f = source("[{},{}]");
        StreamingImport.Result r = run(f, b -> "{\"total\":2,\"succeeded\":1,\"failed\":1,\"correlation_id\":\"partial-1\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("blocked", r.state); assertEquals(0, r.verifiedAccepted); assertEquals(2, r.unresolvedRecords);
        assertEquals(1, r.lastSucceeded); assertEquals(1, r.lastFailed);
        assertEquals("blocked", run(f, b -> { fail("partial replay"); return null; }).state);
    }
    @Test public void ambiguousNetworkBlocksRestart() throws Exception {
        File f = source("{}");
        assertEquals("blocked", run(f, b -> { throw new IOException("lost response"); }).state);
        assertEquals(0, run(f, b -> { fail("network replay"); return null; }).verifiedAccepted);
    }
    @Test public void crashAfterSubmitLeavesDurableInflight() throws Exception {
        File f = source("{}");
        assertThrows(AssertionError.class, () -> run(f, b -> { throw new AssertionError("simulated process death"); }));
        assertEquals("in-flight", run(f, b -> { fail("crash replay"); return null; }).state);
    }
    @Test public void acceptedCheckpointRestartsNdjsonWithoutDuplicates() throws Exception {
        File f = source("{}\n{}\n{}\n"); AtomicBoolean stop = new AtomicBoolean();
        StreamingImport importer = new StreamingImport(20, 22, 1);
        assertEquals(1, importer.run(f, checkpoint(), "scope", b -> ok(1), stop::get, r -> stop.set(true)).verifiedAccepted);
        AtomicInteger sends = new AtomicInteger();
        assertEquals(3, importer.run(f, checkpoint(), "scope", b -> { sends.incrementAndGet(); return ok(1); }, () -> false, r -> {}).verifiedAccepted);
        assertEquals(2, sends.get());
    }
    @Test public void asyncAndMalformedReceiptsNeverAdvance() throws Exception {
        String[] receipts = {"{\"status\":\"accepted\",\"job_id\":\"job-1\",\"total\":1,\"status_url\":\"/documents/bulk/job-1\",\"correlation_id\":\"async-1\"}",
            "{total:1,succeeded:1,failed:0,correlation_id:'x'}", "{\"total\":1,\"succeeded\":1,\"failed\":0}",
            "{\"total\":1,\"succeeded\":1.0,\"failed\":0,\"correlation_id\":\"x\"}", "{}", " ".repeat(8193)};
        for (int i=0; i<receipts.length; i++) {
            final String receipt = receipts[i];
            StreamingImport.Result r = new StreamingImport().run(source("{}"), new File(folder.getRoot(), "c"+i), "scope", b -> receipt.getBytes(StandardCharsets.UTF_8), () -> false, p -> {});
            assertEquals("blocked", r.state); assertEquals(0, r.verifiedAccepted);
            if (i == 0) assertEquals("job-1", r.jobId);
        }
    }
    @Test public void changedSourceOrPrincipalRejected() throws Exception {
        File f = source("{}"); run(f, b -> ok(1));
        assertThrows(IOException.class, () -> new StreamingImport().run(f, checkpoint(), "other", b -> { fail(); return null; }, () -> false, r -> {}));
        Files.write(f.toPath(), "{\"changed\":true}".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> run(f, b -> { fail(); return null; }));
    }
    @Test public void cancelledStagingCleansPartialSnapshot() throws Exception {
        assertThrows(InterruptedIOException.class, () -> StreamingImport.stage(new ByteArrayInputStream(new byte[100]), folder.getRoot(), () -> true));
        assertEquals(0, folder.getRoot().list().length);
    }
    @Test public void concurrentRunRejectedAndCompletedRunDoesNotResend() throws Exception {
        File f = source("{}");
        assertEquals("complete", run(f, b -> {
            assertThrows(java.nio.channels.OverlappingFileLockException.class, () -> run(f, bb -> { fail(); return null; }));
            return ok(1);
        }).state);
        assertEquals("complete", run(f, b -> { fail(); return null; }).state);
    }
}
