package actor.starintel.quasar.importing;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Properties;

/** Metadata only: no document text, credentials or server error bodies. Caller uses app-private storage. */
final class ImportCheckpoint {
    String source, scope, state = "ready", correlation = "", job = "";
    long offset, accepted;
    int pending, succeeded = -1, failed = -1;

    static ImportCheckpoint load(File file, String source, String scope) throws IOException {
        ImportCheckpoint c = new ImportCheckpoint();
        c.source = source; c.scope = scope;
        if (!file.exists()) return c;
        if (file.length() > 8192) throw new IOException("Checkpoint exceeds bound");
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(file)) { p.load(in); }
        try {
            if (!"1".equals(p.getProperty("version")) || !source.equals(p.getProperty("source")) ||
                    !scope.equals(p.getProperty("scope"))) throw new IOException("Checkpoint source or destination mismatch");
            c.offset = Long.parseLong(p.getProperty("offset"));
            c.accepted = Long.parseLong(p.getProperty("accepted"));
            c.pending = Integer.parseInt(p.getProperty("pending"));
            c.state = p.getProperty("state");
            if (c.offset < 0 || c.accepted < 0 || c.pending < 0 || c.pending > StreamingImport.MAX_BATCH_RECORDS ||
                    (c.accepted == 0 && c.offset != 0) || (c.accepted > 0 && c.offset == 0) ||
                    (("ready".equals(c.state) || "complete".equals(c.state)) && c.pending != 0) ||
                    (("in-flight".equals(c.state) || "blocked".equals(c.state)) && c.pending == 0) ||
                    !("ready".equals(c.state) || "in-flight".equals(c.state) || "blocked".equals(c.state) || "complete".equals(c.state)))
                throw new IOException("Invalid checkpoint");
            c.succeeded = Integer.parseInt(p.getProperty("succeeded"));
            c.failed = Integer.parseInt(p.getProperty("failed"));
            c.correlation = p.getProperty("correlation", "");
            c.job = p.getProperty("job", "");
            return c;
        } catch (RuntimeException e) { throw new IOException("Invalid checkpoint", e); }
    }

    void save(File file) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", "1"); p.setProperty("source", source); p.setProperty("scope", scope);
        p.setProperty("offset", Long.toString(offset)); p.setProperty("accepted", Long.toString(accepted));
        p.setProperty("pending", Integer.toString(pending)); p.setProperty("state", state);
        p.setProperty("succeeded", Integer.toString(succeeded)); p.setProperty("failed", Integer.toString(failed));
        p.setProperty("correlation", correlation); p.setProperty("job", job);
        File temporary = File.createTempFile("import-checkpoint-", ".tmp", file.getAbsoluteFile().getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { p.store(out, null); out.getFD().sync(); }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Persist the rename before any mutation can leave this process.
            try (FileChannel directory = FileChannel.open(file.getAbsoluteFile().getParentFile().toPath(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary.toPath()); }
    }
}
