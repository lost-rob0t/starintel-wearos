package actor.starintel.collector;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

final class WigleSqliteImporter {
    private static final int BATCH_SIZE = 2_000;

    private WigleSqliteImporter() {}

    static ImportStats importDatabase(Context context, Uri uri, StarWirelessStore store) throws Exception {
        CopiedDatabase copied = copyToCache(context, uri);
        SQLiteDatabase source =
                SQLiteDatabase.openDatabase(copied.file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            requireTable(source, "network");
            requireTable(source, "location");

            ImportStats stats = new ImportStats();
            importNetworks(source, store, stats);
            importObservations(source, store, copied.sourceId, stats);
            if (hasTable(source, "route")) importRoutes(source, store, copied.sourceId, stats);
            return stats;
        } finally {
            source.close();
            if (!copied.file.delete()) copied.file.deleteOnExit();
        }
    }

    private static void importNetworks(
            SQLiteDatabase source, StarWirelessStore store, ImportStats stats) {
        try (Cursor cursor = source.rawQuery("SELECT * FROM network", null)) {
            StarWirelessStore.Batch batch = store.beginBatch();
            int inBatch = 0;
            try {
                while (cursor.moveToNext()) {
                    String bssid = string(cursor, "bssid", "");
                    if (bssid.isEmpty()) continue;

                    long lastSeen = normalizeTime(longValue(cursor, "lasttime", System.currentTimeMillis()));
                    StarWirelessStore.importNetworkSummary(
                            batch.db(),
                            bssid,
                            string(cursor, "ssid", ""),
                            intValue(cursor, "frequency", 0),
                            string(cursor, "capabilities", ""),
                            string(cursor, "type", "W"),
                            lastSeen,
                            nullableDouble(cursor, "lastlat"),
                            nullableDouble(cursor, "lastlon"),
                            intValue(cursor, "bestlevel", -127),
                            nullableDouble(cursor, "bestlat"),
                            nullableDouble(cursor, "bestlon"),
                            string(cursor, "rcois", ""),
                            intValue(cursor, "mfgrid", 0),
                            string(cursor, "service", ""));
                    stats.networks++;
                    inBatch++;
                    if (inBatch >= BATCH_SIZE) {
                        batch.successful();
                        batch.close();
                        batch = store.beginBatch();
                        inBatch = 0;
                    }
                }
                batch.successful();
            } finally {
                batch.close();
            }
        }
    }

    private static void importObservations(
            SQLiteDatabase source,
            StarWirelessStore store,
            String sourceId,
            ImportStats stats) {
        String sql =
                "SELECT l._id AS row_id,l.bssid,l.level,l.lat,l.lon,l.altitude,l.accuracy,"
                        + "l.time,l.external,l.mfgrid,"
                        + "n.ssid,n.frequency,n.capabilities,n.type "
                        + "FROM location l LEFT JOIN network n ON n.bssid=l.bssid "
                        + "ORDER BY l._id";

        try (Cursor cursor = source.rawQuery(sql, null)) {
            StarWirelessStore.Batch batch = store.beginBatch();
            int inBatch = 0;
            try {
                while (cursor.moveToNext()) {
                    long rowId = longValue(cursor, "row_id", 0L);
                    String bssid = string(cursor, "bssid", "");
                    if (bssid.isEmpty()) continue;

                    long observedAt = normalizeTime(longValue(cursor, "time", 0L));
                    StarWirelessStore.insertObservation(
                            batch.db(),
                            sourceId + ":location:" + rowId,
                            bssid,
                            string(cursor, "ssid", ""),
                            intValue(cursor, "frequency", 0),
                            string(cursor, "capabilities", ""),
                            string(cursor, "type", "W"),
                            intValue(cursor, "level", -127),
                            nullableDouble(cursor, "lat"),
                            nullableDouble(cursor, "lon"),
                            nullableDouble(cursor, "altitude"),
                            nullableFloat(cursor, "accuracy"),
                            observedAt,
                            intValue(cursor, "external", 0),
                            intValue(cursor, "mfgrid", 0),
                            "wigle-sqlite",
                            rowId);
                    stats.observations++;
                    inBatch++;
                    if (inBatch >= BATCH_SIZE) {
                        batch.successful();
                        batch.close();
                        batch = store.beginBatch();
                        inBatch = 0;
                    }
                }
                batch.successful();
            } finally {
                batch.close();
            }
        }
    }

    private static void importRoutes(
            SQLiteDatabase source,
            StarWirelessStore store,
            String sourceId,
            ImportStats stats) {
        try (Cursor cursor = source.rawQuery("SELECT * FROM route ORDER BY _id", null)) {
            StarWirelessStore.Batch batch = store.beginBatch();
            int inBatch = 0;
            try {
                while (cursor.moveToNext()) {
                    long rowId = longValue(cursor, "_id", 0L);
                    StarWirelessStore.insertRoute(
                            batch.db(),
                            sourceId + ":route:" + rowId,
                            longValue(cursor, "run_id", 0L),
                            intValue(cursor, "wifi_visible", 0),
                            intValue(cursor, "cell_visible", 0),
                            intValue(cursor, "bt_visible", 0),
                            doubleValue(cursor, "lat", 0.0),
                            doubleValue(cursor, "lon", 0.0),
                            nullableDouble(cursor, "altitude"),
                            nullableFloat(cursor, "accuracy"),
                            normalizeTime(longValue(cursor, "time", 0L)),
                            "wigle-sqlite",
                            rowId);
                    stats.routes++;
                    inBatch++;
                    if (inBatch >= BATCH_SIZE) {
                        batch.successful();
                        batch.close();
                        batch = store.beginBatch();
                        inBatch = 0;
                    }
                }
                batch.successful();
            } finally {
                batch.close();
            }
        }
    }

    private static CopiedDatabase copyToCache(Context context, Uri uri) throws Exception {
        File file = File.createTempFile("wigle-import-", ".sqlite", context.getCacheDir());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getContentResolver().openInputStream(uri);
                FileOutputStream output = new FileOutputStream(file)) {
            if (input == null) throw new IllegalArgumentException("Could not open selected database");
            byte[] buffer = new byte[128 * 1024];
            while (true) {
                int read = input.read(buffer);
                if (read < 0) break;
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        return new CopiedDatabase(file, "wigle:" + hex(digest.digest()));
    }

    private static void requireTable(SQLiteDatabase db, String name) {
        if (!hasTable(db, name)) throw new IllegalArgumentException("Not a WiGLE SQLite database: missing " + name);
    }

    private static boolean hasTable(SQLiteDatabase db, String name) {
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
                        new String[] {name})) {
            return cursor.moveToFirst();
        }
    }

    private static int column(Cursor cursor, String name) {
        return cursor.getColumnIndex(name);
    }

    private static String string(Cursor cursor, String name, String fallback) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getString(index);
    }

    private static int intValue(Cursor cursor, String name, int fallback) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getInt(index);
    }

    private static long longValue(Cursor cursor, String name, long fallback) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getLong(index);
    }

    private static double doubleValue(Cursor cursor, String name, double fallback) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? fallback : cursor.getDouble(index);
    }

    private static Double nullableDouble(Cursor cursor, String name) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getDouble(index);
    }

    private static Float nullableFloat(Cursor cursor, String name) {
        int index = column(cursor, name);
        return index < 0 || cursor.isNull(index) ? null : cursor.getFloat(index);
    }

    private static long normalizeTime(long value) {
        if (value <= 0L) return System.currentTimeMillis();
        return value < 10_000_000_000L ? value * 1000L : value;
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return output.toString();
    }

    static final class ImportStats {
        long networks;
        long observations;
        long routes;
    }

    private static final class CopiedDatabase {
        final File file;
        final String sourceId;

        CopiedDatabase(File file, String sourceId) {
            this.file = file;
            this.sourceId = sourceId;
        }
    }
}
