package actor.starintel.collector;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public final class StarDocumentSyncTest {
    @Test
    public void parseBatchKeepsMalformedRowsOutOfTheAcceptedUploadSet() {
        StarWirelessStore.QueuedDocument valid =
                new StarWirelessStore.QueuedDocument("valid", "analysis", "{\"id\":\"valid\"}");
        StarWirelessStore.QueuedDocument malformed =
                new StarWirelessStore.QueuedDocument("malformed", "analysis", "{not-json");

        StarDocumentSync.ParsedBatch parsed =
                StarDocumentSync.parseBatch(List.of(valid, malformed));

        assertEquals(1, parsed.documents.length());
        assertEquals(List.of(valid), parsed.valid);
        assertEquals(List.of(malformed), parsed.malformed);
    }
}
