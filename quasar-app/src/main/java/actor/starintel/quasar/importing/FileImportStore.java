package actor.starintel.quasar.importing;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.function.BooleanSupplier;

/** One active private snapshot; all calls belong on the application's single import worker. */
public final class FileImportStore {
    public static final long MAX_SOURCE_BYTES = 1024L * 1024 * 1024;
    private final File directory, pointer;
    public FileImportStore(File directory) throws IOException {
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create import storage");
        pointer = new File(directory, "active");
        File selected = current(); // Fail closed on a corrupt pointer; never infer an active import.
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot read import storage");
        for (File file : files) {
            // An interrupted staging copy has no journal and has never been submitted.
            if (file.getName().startsWith("import-source-") && file.getName().endsWith(".json") &&
                    !file.equals(selected) && !checkpoint(file).exists()) Files.deleteIfExists(file.toPath());
            if (file.getName().startsWith("selection-") && file.getName().endsWith(".tmp")) Files.deleteIfExists(file.toPath());
        }
    }
    public File current() throws IOException {
        if (!pointer.exists()) return null;
        if (pointer.length() > 256) throw new IOException("Invalid import selection");
        String name;
        try (DataInputStream in = new DataInputStream(new FileInputStream(pointer))) {
            name = in.readUTF(); if (in.read() != -1) throw new IOException("Invalid import selection");
        }
        if (!name.matches("import-source-[A-Za-z0-9-]+\\.json")) throw new IOException("Invalid source name");
        File source = new File(directory, name);
        if (!source.isFile()) throw new IOException("Import snapshot is missing; do not recreate its journal");
        return source;
    }
    public File checkpoint(File source) { return new File(source.getPath() + ".checkpoint"); }
    public static boolean replaceable(StreamingImport.Result result) {
        return "complete".equals(result.state) || "ready".equals(result.state) && result.verifiedAccepted == 0;
    }
    public File select(InputStream stream, BooleanSupplier cancelled) throws IOException {
        File previous;
        try {
            previous = current();
            if (previous != null && !replaceable(StreamingImport.inspect(checkpoint(previous))))
                throw new IOException("Preserve this import until unresolved records are reconciled");
        } catch (IOException failure) { stream.close(); throw failure; }
        File source = StreamingImport.stage(stream, directory, cancelled, MAX_SOURCE_BYTES);
        File temporary = File.createTempFile("selection-", ".tmp", directory);
        boolean selected = false;
        try {
            try (FileOutputStream file = new FileOutputStream(temporary); DataOutputStream out = new DataOutputStream(file)) {
                out.writeUTF(source.getName()); out.flush(); file.getFD().sync();
            }
            Files.move(temporary.toPath(), pointer.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel folder = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) { folder.force(true); }
            selected = true;
        } finally {
            Files.deleteIfExists(temporary.toPath());
            // A rename may have succeeded before fsync failed. Preserve its target in that case.
            if (!selected && (!pointer.exists() || !source.equals(current()))) Files.deleteIfExists(source.toPath());
        }
        // Only fully completed or never-started snapshots can be replaced. No pending work is evicted.
        if (previous != null) {
            Files.deleteIfExists(previous.toPath());
            Files.deleteIfExists(checkpoint(previous).toPath());
            Files.deleteIfExists(new File(checkpoint(previous) + ".lock").toPath());
        }
        return source;
    }
}
