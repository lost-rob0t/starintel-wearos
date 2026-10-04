package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.Locale;
import org.junit.Test;

public class WigleSqliteImporterLargeTest {
    private static final int NETWORKS = 25_000;
    private static final int OBSERVATIONS = 250_000;
    private static final int ROUTES = 50_000;

    @Test
    public void migratesVersionThreeObservationsForResumableProjection() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("star-wireless.db");
        File databaseFile = context.getDatabasePath("star-wireless.db");
        SQLiteDatabase versionThree = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        versionThree.execSQL(
                "CREATE TABLE observation (_id INTEGER PRIMARY KEY AUTOINCREMENT,event_key TEXT NOT NULL)");
        versionThree.setVersion(3);
        versionThree.close();

        try (StarWirelessStore ignored = new StarWirelessStore(context);
                Cursor columns = ignored.getReadableDatabase().rawQuery(
                        "PRAGMA table_info(observation)", null)) {
            boolean found = false;
            int nameColumn = columns.getColumnIndexOrThrow("name");
            while (columns.moveToNext()) {
                if ("projected".equals(columns.getString(nameColumn))) found = true;
            }
            assertTrue("v3 observation table must gain resumable projection state", found);
        } finally {
            context.deleteDatabase("star-wireless.db");
        }
    }

    @Test(timeout = 10L * 60L * 1000L)
    public void importsLargeDatabaseInBoundedBatchesAndReplaysIdempotently() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(context.getCacheDir(), "wigle-large-fixture.sqlite");
        if (source.exists()) assertTrue(source.delete());
        context.deleteDatabase("star-wireless.db");

        createFixture(source);
        assertTrue("fixture must exercise a large on-device SQLite file",
                source.length() >= 16L * 1024L * 1024L);

        try (StarWirelessStore store = new StarWirelessStore(context)) {
            WigleSqliteImporter.ImportStats first = WigleSqliteImporter.importDatabase(
                    context, Uri.fromFile(source), store);
            assertStats(first);
            assertCounts(store);
            assertEquals(OBSERVATIONS, store.unprojectedObservationCount());

            int projected = DocumentProjection.refillWirelessQueue(
                    store, DocumentProjection.WIRELESS_QUEUE_HIGH_WATER);
            assertTrue(projected > 0);
            assertTrue(store.queuedCount() <= DocumentProjection.WIRELESS_QUEUE_HIGH_WATER + 2L);
            assertEquals(OBSERVATIONS - projected, store.unprojectedObservationCount());

            WigleSqliteImporter.ImportStats replay = WigleSqliteImporter.importDatabase(
                    context, Uri.fromFile(source), store);
            assertStats(replay);
            assertCounts(store);
            assertEquals(OBSERVATIONS - projected, store.unprojectedObservationCount());
        } finally {
            context.deleteDatabase("star-wireless.db");
            if (source.exists()) assertTrue(source.delete());
        }
    }

    private static void assertStats(WigleSqliteImporter.ImportStats stats) {
        assertEquals(NETWORKS, stats.networks);
        assertEquals(OBSERVATIONS, stats.observations);
        assertEquals(ROUTES, stats.routes);
    }

    private static void assertCounts(StarWirelessStore store) {
        assertEquals(NETWORKS, store.networkCount());
        assertEquals(OBSERVATIONS, store.observationCount());
        assertEquals(ROUTES, store.routeCount());
    }

    private static void createFixture(File target) {
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(target, null);
        try {
            try (Cursor cursor = database.rawQuery("PRAGMA journal_mode=OFF", null)) {
                assertTrue(cursor.moveToFirst());
            }
            database.execSQL("PRAGMA synchronous=OFF");
            database.execSQL(
                    "CREATE TABLE network (bssid TEXT PRIMARY KEY,ssid TEXT,frequency INTEGER,"
                            + "capabilities TEXT,type TEXT,lasttime INTEGER,lastlat REAL,lastlon REAL,"
                            + "bestlevel INTEGER,bestlat REAL,bestlon REAL,rcois TEXT,mfgrid INTEGER,service TEXT)");
            database.execSQL(
                    "CREATE TABLE location (_id INTEGER PRIMARY KEY,bssid TEXT,level INTEGER,lat REAL,"
                            + "lon REAL,altitude REAL,accuracy REAL,time INTEGER,external INTEGER,mfgrid INTEGER)");
            database.execSQL(
                    "CREATE TABLE route (_id INTEGER PRIMARY KEY,run_id INTEGER,wifi_visible INTEGER,"
                            + "cell_visible INTEGER,bt_visible INTEGER,lat REAL,lon REAL,altitude REAL,"
                            + "accuracy REAL,time INTEGER)");

            database.beginTransaction();
            try (SQLiteStatement insert = database.compileStatement(
                    "INSERT INTO network VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (int index = 0; index < NETWORKS; index++) {
                    insert.bindString(1, bssid(index));
                    insert.bindString(2, "fixture-" + index);
                    insert.bindLong(3, index % 2 == 0 ? 2412 : 5180);
                    insert.bindString(4, "[WPA2-PSK-CCMP][ESS]");
                    insert.bindString(5, "W");
                    insert.bindLong(6, 1_800_000_000_000L + index);
                    insert.bindDouble(7, 40.0d + (index % 1000) / 10_000.0d);
                    insert.bindDouble(8, -74.0d - (index % 1000) / 10_000.0d);
                    insert.bindLong(9, -30 - (index % 70));
                    insert.bindDouble(10, 40.0d);
                    insert.bindDouble(11, -74.0d);
                    insert.bindString(12, "");
                    insert.bindLong(13, 0);
                    insert.bindString(14, "");
                    insert.executeInsert();
                    insert.clearBindings();
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }

            database.beginTransaction();
            try (SQLiteStatement insert = database.compileStatement(
                    "INSERT INTO location VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                for (int index = 0; index < OBSERVATIONS; index++) {
                    insert.bindLong(1, index + 1L);
                    insert.bindString(2, bssid(index % NETWORKS));
                    insert.bindLong(3, -35 - (index % 60));
                    insert.bindDouble(4, 40.0d + (index % 10_000) / 100_000.0d);
                    insert.bindDouble(5, -74.0d - (index % 10_000) / 100_000.0d);
                    insert.bindDouble(6, 10.0d + index % 20);
                    insert.bindDouble(7, 3.0d + index % 5);
                    insert.bindLong(8, 1_800_000_000_000L + index);
                    insert.bindLong(9, 0);
                    insert.bindLong(10, 0);
                    insert.executeInsert();
                    insert.clearBindings();
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }

            database.beginTransaction();
            try (SQLiteStatement insert = database.compileStatement(
                    "INSERT INTO route VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                for (int index = 0; index < ROUTES; index++) {
                    insert.bindLong(1, index + 1L);
                    insert.bindLong(2, 7L);
                    insert.bindLong(3, 20 + index % 30);
                    insert.bindLong(4, 0);
                    insert.bindLong(5, 0);
                    insert.bindDouble(6, 40.0d + (index % 10_000) / 100_000.0d);
                    insert.bindDouble(7, -74.0d - (index % 10_000) / 100_000.0d);
                    insert.bindDouble(8, 12.0d);
                    insert.bindDouble(9, 4.0d);
                    insert.bindLong(10, 1_800_000_000_000L + index);
                    insert.executeInsert();
                    insert.clearBindings();
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        } finally {
            database.close();
        }
    }

    private static String bssid(int index) {
        return String.format(
                Locale.ROOT,
                "02:00:%02x:%02x:%02x:%02x",
                (index >>> 24) & 0xff,
                (index >>> 16) & 0xff,
                (index >>> 8) & 0xff,
                index & 0xff);
    }
}
