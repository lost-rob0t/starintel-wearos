package actor.starintel.quasar.importing;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * One synchronous batch at a time. Requires an immutable, app-private source snapshot and a
 * stable checkpoint path per import. Run off the UI thread. A blocked import MUST NOT be reset
 * or recreated to retry: the backend has no verified per-record reconciliation/replay contract.
 */
public final class StreamingImport {
    public static final int MAX_RECORD_BYTES = 128 * 1024;
    public static final int MAX_BATCH_BYTES = 256 * 1024;
    // Backend http-bulk-jobs.lisp switches to asynchronous acceptance above ten records.
    public static final int MAX_BATCH_RECORDS = 10;
    public static final int MAX_RECEIPT_BYTES = 8192;

    public interface Transport {
        /** Submit exactly once; bound the response while reading, before allocating/returning it. */
        byte[] send(byte[] jsonArray) throws Exception;
    }
    public interface Progress { void updated(Result result); }
    public static final class Result {
        public final String state, correlationId, jobId;
        public final long verifiedAccepted, sourceOffset;
        public final int unresolvedRecords, lastSucceeded, lastFailed;
        Result(ImportCheckpoint c) {
            state = c.state; correlationId = c.correlation; jobId = c.job;
            verifiedAccepted = c.accepted; sourceOffset = c.offset;
            unresolvedRecords = c.pending; lastSucceeded = c.succeeded; lastFailed = c.failed;
        }
    }
    private final int recordBytes, batchBytes, batchRecords;
    public StreamingImport() { this(MAX_RECORD_BYTES, MAX_BATCH_BYTES, MAX_BATCH_RECORDS); }
    public StreamingImport(int recordBytes, int batchBytes, int batchRecords) {
        if (recordBytes < 2 || recordBytes > MAX_RECORD_BYTES || batchBytes < recordBytes + 2 ||
                batchBytes > MAX_BATCH_BYTES || batchRecords < 1 || batchRecords > MAX_BATCH_RECORDS)
            throw new IllegalArgumentException("Invalid import bounds");
        this.recordBytes = recordBytes; this.batchBytes = batchBytes; this.batchRecords = batchRecords;
    }

    /** Copy SAF input to a new private snapshot with constant memory; never use URI display name as a path. */
    public static File stage(InputStream source, File privateDirectory, BooleanSupplier cancelled) throws IOException {
        File file = File.createTempFile("import-source-", ".json", privateDirectory);
        boolean complete = false;
        try (InputStream in = source; FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Import staging cancelled");
                out.write(buffer, 0, n);
            }
            out.getFD().sync(); complete = true; return file;
        } finally { if (!complete) Files.deleteIfExists(file.toPath()); }
    }

    /** destinationIdentity binds server origin AND principal; pass a stable non-secret identity. */
    public Result run(File source, File checkpoint, String destinationIdentity, Transport transport,
                      BooleanSupplier cancelled, Progress progress) throws Exception {
        if (source.getCanonicalFile().equals(checkpoint.getCanonicalFile())) throw new IOException("Source is checkpoint");
        if (destinationIdentity.isEmpty() || destinationIdentity.length() > 8192) throw new IllegalArgumentException("Destination identity required");
        try (RandomAccessFile lockFile = new RandomAccessFile(checkpoint + ".lock", "rw");
             FileLock lock = lockFile.getChannel().tryLock()) {
            if (lock == null) throw new IOException("Import already running");
            long size = source.length(), modified = source.lastModified();
            String digest = fingerprint(source, cancelled);
            ImportCheckpoint c = ImportCheckpoint.load(checkpoint, digest, hash(destinationIdentity.getBytes(StandardCharsets.UTF_8)));
            if (!"ready".equals(c.state)) return new Result(c);
            if (c.offset > size) throw new IOException("Checkpoint outside source");
            try (BoundedJsonRecords records = BoundedJsonRecords.open(source, recordBytes, c.offset, c.accepted)) {
                BoundedJsonRecords.Record carry = null;
                while (!cancelled.getAsBoolean()) {
                    ByteArrayOutputStream batch = new ByteArrayOutputStream(batchBytes);
                    batch.write('['); int count = 0; long end = c.offset;
                    while (count < batchRecords) {
                        BoundedJsonRecords.Record r = carry != null ? carry : records.next(); carry = null;
                        if (r == null) break;
                        if (batch.size() + (count == 0 ? 0 : 1) + r.bytes.length + 1 > batchBytes) { carry = r; break; }
                        if (count > 0) batch.write(',');
                        batch.write(r.bytes); count++; end = r.end;
                    }
                    if (count == 0) { c.state = "complete"; c.save(checkpoint); progress.updated(new Result(c)); break; }
                    if (cancelled.getAsBoolean()) break;
                    if (source.length() != size || source.lastModified() != modified) throw new IOException("Import source changed");
                    batch.write(']');
                    c.state = "in-flight"; c.pending = count; c.succeeded = -1; c.failed = -1; c.correlation = ""; c.job = "";
                    c.save(checkpoint); // Crash after this point cannot automatically replay the batch.
                    byte[] response;
                    try { response = transport.send(batch.toByteArray()); }
                    catch (Exception failure) {
                        c.state = "blocked"; c.save(checkpoint); progress.updated(new Result(c)); return new Result(c);
                    }
                    boolean accepted = receipt(c, response, count);
                    if (accepted) { c.accepted += count; c.offset = end; c.pending = 0; c.state = "ready"; }
                    else c.state = "blocked";
                    c.save(checkpoint); progress.updated(new Result(c));
                    if (!accepted) break;
                }
            }
            return new Result(c);
        }
    }

    private static boolean receipt(ImportCheckpoint c, byte[] response, int count) {
        if (response == null || response.length > MAX_RECEIPT_BYTES) return false;
        try {
            BoundedJsonRecords.validateObject(response);
            JSONObject root = new JSONObject(new String(response, StandardCharsets.UTF_8));
            c.correlation = boundedIdentifier(root.opt("correlation_id"));
            c.job = boundedIdentifier(root.opt("job_id"));
            if (!c.job.isEmpty() || root.has("status_url") || root.has("status")) return false;
            int total = integer(root.opt("total")), succeeded = integer(root.opt("succeeded")), failed = integer(root.opt("failed"));
            if (total != count || succeeded < 0 || failed < 0 || succeeded + failed != count) return false;
            c.succeeded = succeeded; c.failed = failed;
            return !c.correlation.isEmpty() && succeeded == count && failed == 0;
        } catch (Exception invalid) { return false; }
    }
    private static int integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) return -1;
        long n = ((Number) value).longValue(); return n >= 0 && n <= MAX_BATCH_RECORDS ? (int) n : -1;
    }
    private static String boundedIdentifier(Object value) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        return text.matches("[A-Za-z0-9_.:-]{1,200}") ? text : "";
    }
    private static String fingerprint(File file, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Import cancelled");
                digest.update(buffer, 0, n);
            }
        }
        return hex(digest.digest());
    }
    private static String hash(byte[] value) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(64); for (byte b : bytes) out.append(String.format("%02x", b & 255)); return out.toString(); }
}
