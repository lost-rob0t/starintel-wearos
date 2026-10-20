package actor.starintel.quasar.importing;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class FileImportStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private InputStream source(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    @Test public void selectionPersistsAndCancellationPreservesPrevious() throws Exception {
        FileImportStore store = new FileImportStore(folder.getRoot());
        File first = store.select(source("{}"), () -> false);
        assertEquals(first, new FileImportStore(folder.getRoot()).current());
        assertThrows(InterruptedIOException.class, () -> store.select(source("{\"new\":true}"), () -> true));
        assertEquals(first, store.current()); assertTrue(first.isFile());
        assertEquals("ready", StreamingImport.inspect(store.checkpoint(first)).state);
    }
    @Test public void unresolvedJournalSurvivesRestartAndCannotBeReplaced() throws Exception {
        FileImportStore store = new FileImportStore(folder.getRoot());
        File first = store.select(source("{}"), () -> false);
        new StreamingImport().run(first, store.checkpoint(first), "scope", b -> { throw new IOException("unknown"); }, () -> false, r -> {});
        FileImportStore restarted = new FileImportStore(folder.getRoot());
        assertEquals("blocked", StreamingImport.inspect(restarted.checkpoint(restarted.current())).state);
        assertThrows(IOException.class, () -> restarted.select(source("{}"), () -> false));
        assertEquals(first, restarted.current()); assertTrue(store.checkpoint(first).isFile());
    }
    @Test public void cleanupOnlyRemovesNeverSubmittedOrphans() throws Exception {
        File orphan = new File(folder.getRoot(), "import-source-123.json"); Files.write(orphan.toPath(), new byte[]{1});
        File journalled = new File(folder.getRoot(), "import-source-456.json"); Files.write(journalled.toPath(), new byte[]{1});
        Files.write(new File(journalled + ".checkpoint").toPath(), new byte[]{1});
        new FileImportStore(folder.getRoot());
        assertFalse(orphan.exists()); assertTrue(journalled.exists());
    }
    @Test public void sourceQuotaCleansPartialCopy() {
        assertThrows(IOException.class, () -> StreamingImport.stage(source("0123456789"), folder.getRoot(), () -> false, 9));
        assertEquals(0, folder.getRoot().list().length);
    }
    @Test public void corruptSelectionFailsClosed() throws Exception {
        Files.write(new File(folder.getRoot(), "active").toPath(), new byte[]{1,2,3});
        assertThrows(IOException.class, () -> new FileImportStore(folder.getRoot()));
    }
}
