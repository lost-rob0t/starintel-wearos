package actor.starintel.collector;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Android SQLite tests: run only on an isolated emulator, never a collection device. */
public class MissionAnnotationStoreTest {
    private Context context;

    @Before public void reset() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("star-wireless.db");
        java.io.File directory = context.getDatabasePath("star-wireless.db").getParentFile();
        assertTrue(directory.isDirectory() || directory.mkdirs());
    }

    @After public void cleanup() { context.deleteDatabase("star-wireless.db"); }

    @Test public void upgradePreservesVersionFourEvidence() {
        try (SQLiteDatabase old = SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath("star-wireless.db"), null)) {
            old.execSQL("CREATE TABLE observation (_id INTEGER PRIMARY KEY,event_key TEXT NOT NULL)");
            old.execSQL("INSERT INTO observation VALUES (1,'preserved-raw-evidence')");
            old.setVersion(4);
        }
        try (StarWirelessStore store = new StarWirelessStore(context)) {
            store.appendAnnotation("mission", "note", "local note", 100, "operator");
            assertEquals(5, store.getReadableDatabase().getVersion());
            try (Cursor c = store.getReadableDatabase().rawQuery("SELECT event_key FROM observation", null)) {
                assertTrue(c.moveToFirst());
                assertEquals("preserved-raw-evidence", c.getString(0));
                assertFalse(c.moveToNext());
            }
        }
    }

    @Test public void intervalAndNotesSurviveReopenAndUseInsertionOrder() {
        String interval;
        try (StarWirelessStore store = new StarWirelessStore(context)) {
            interval = store.appendAnnotation("mission", "start", "North entrance", 300, "operator").id;
            // A wall-clock correction must neither lose nor reorder evidence.
            MissionAnnotation note = store.appendAnnotation("mission", "note", "Observation", 200, "operator");
            assertEquals(interval, note.intervalId);
        }
        try (StarWirelessStore store = new StarWirelessStore(context)) {
            assertEquals(interval, store.activeAnnotation("mission").id);
            assertEquals("note", store.recentAnnotations("mission").get(0).kind);
            MissionAnnotation end = store.appendAnnotation("mission", "end", "", 100, "service_stop");
            assertEquals(interval, end.intervalId);
            assertNull(store.activeAnnotation("mission"));
            assertEquals(3, store.recentAnnotations("mission").size());
            assertEquals("service_stop", store.recentAnnotations("mission").get(0).source);
        }
    }

    @Test public void invalidTransitionsRollbackAndMissionsRemainIndependent() {
        try (StarWirelessStore a = new StarWirelessStore(context);
                StarWirelessStore b = new StarWirelessStore(context)) {
            a.appendAnnotation("a", "start", "First", 1, "operator");
            assertThrows(IllegalStateException.class, () ->
                    b.appendAnnotation("a", "start", "Duplicate", 2, "operator"));
            assertThrows(IllegalArgumentException.class, () ->
                    b.appendAnnotation("a", "note", "", 2, "operator"));
            b.appendAnnotation("b", "start", "Separate", 2, "operator");
            assertEquals(1, a.recentAnnotations("a").size());
            a.appendAnnotation("a", "end", "", 3, "operator");
            assertThrows(IllegalStateException.class, () ->
                    b.appendAnnotation("a", "end", "", 4, "operator"));
            assertNotNull(b.activeAnnotation("b"));
            assertEquals(2, b.recentAnnotations("a").size());
            a.appendAnnotation("a", "start", "Next", 4, "operator");
            assertEquals("Next", b.activeAnnotation("a").text);
        }
    }
}
