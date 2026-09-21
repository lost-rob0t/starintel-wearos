package actor.starintel.collector;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

final class StarWirelessStore extends SQLiteOpenHelper {
    private static final String NAME = "star-wireless.db";
    private static final int VERSION = 1;

    StarWirelessStore(Context context) {
        super(context.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE network ("
                        + "bssid TEXT PRIMARY KEY NOT NULL,"
                        + "ssid TEXT NOT NULL DEFAULT '',"
                        + "frequency INTEGER NOT NULL DEFAULT 0,"
                        + "capabilities TEXT NOT NULL DEFAULT '',"
                        + "network_type TEXT NOT NULL DEFAULT 'W',"
                        + "first_seen_ms INTEGER NOT NULL,"
                        + "last_seen_ms INTEGER NOT NULL,"
                        + "best_level INTEGER NOT NULL DEFAULT -127,"
                        + "last_level INTEGER NOT NULL DEFAULT -127,"
                        + "last_lat REAL,"
                        + "last_lon REAL,"
                        + "best_lat REAL,"
                        + "best_lon REAL,"
                        + "rcois TEXT NOT NULL DEFAULT '',"
                        + "mfgrid INTEGER NOT NULL DEFAULT 0,"
                        + "service TEXT NOT NULL DEFAULT '',"
                        + "source TEXT NOT NULL DEFAULT 'star-wireless'"
                        + ")");

        db.execSQL(
                "CREATE TABLE observation ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "event_key TEXT UNIQUE NOT NULL,"
                        + "bssid TEXT NOT NULL,"
                        + "ssid TEXT NOT NULL DEFAULT '',"
                        + "frequency INTEGER NOT NULL DEFAULT 0,"
                        + "capabilities TEXT NOT NULL DEFAULT '',"
                        + "network_type TEXT NOT NULL DEFAULT 'W',"
                        + "level INTEGER NOT NULL,"
                        + "lat REAL,"
                        + "lon REAL,"
                        + "altitude REAL,"
                        + "accuracy REAL,"
                        + "observed_at_ms INTEGER NOT NULL,"
                        + "external INTEGER NOT NULL DEFAULT 0,"
                        + "mfgrid INTEGER NOT NULL DEFAULT 0,"
                        + "source TEXT NOT NULL,"
                        + "source_row_id INTEGER,"
                        + "ingested_at_ms INTEGER NOT NULL"
                        + ")");
        db.execSQL("CREATE INDEX idx_observation_bssid_time ON observation(bssid, observed_at_ms DESC)");
        db.execSQL("CREATE INDEX idx_observation_geo_time ON observation(observed_at_ms, lat, lon)");

        db.execSQL(
                "CREATE TABLE route ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "event_key TEXT UNIQUE NOT NULL,"
                        + "run_id INTEGER NOT NULL DEFAULT 0,"
                        + "wifi_visible INTEGER NOT NULL DEFAULT 0,"
                        + "cell_visible INTEGER NOT NULL DEFAULT 0,"
                        + "bt_visible INTEGER NOT NULL DEFAULT 0,"
                        + "lat REAL NOT NULL,"
                        + "lon REAL NOT NULL,"
                        + "altitude REAL,"
                        + "accuracy REAL,"
                        + "observed_at_ms INTEGER NOT NULL,"
                        + "source TEXT NOT NULL,"
                        + "source_row_id INTEGER,"
                        + "ingested_at_ms INTEGER NOT NULL"
                        + ")");
        db.execSQL("CREATE INDEX idx_route_time ON route(observed_at_ms)");

        db.execSQL(
                "CREATE TABLE import_log ("
                        + "source_id TEXT PRIMARY KEY NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "completed_at_ms INTEGER,"
                        + "network_count INTEGER NOT NULL DEFAULT 0,"
                        + "observation_count INTEGER NOT NULL DEFAULT 0,"
                        + "route_count INTEGER NOT NULL DEFAULT 0"
                        + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != newVersion) {
            throw new IllegalStateException("Star Wireless DB migration missing: " + oldVersion + " -> " + newVersion);
        }
    }

    void recordLiveObservation(
            String eventKey,
            String bssid,
            String ssid,
            int frequency,
            String capabilities,
            int level,
            Double lat,
            Double lon,
            Double altitude,
            Float accuracy,
            long observedAtMs) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            upsertNetwork(
                    db,
                    bssid,
                    ssid,
                    frequency,
                    capabilities,
                    "W",
                    level,
                    lat,
                    lon,
                    observedAtMs,
                    "",
                    0,
                    "",
                    "star-wireless");
            insertObservation(
                    db,
                    eventKey,
                    bssid,
                    ssid,
                    frequency,
                    capabilities,
                    "W",
                    level,
                    lat,
                    lon,
                    altitude,
                    accuracy,
                    observedAtMs,
                    0,
                    0,
                    "star-wireless",
                    null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    Batch beginBatch() {
        return new Batch(getWritableDatabase());
    }

    long networkCount() {
        return count("network");
    }

    long observationCount() {
        return count("observation");
    }

    long routeCount() {
        return count("route");
    }

    JSONArray recentObservations(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        JSONArray rows = new JSONArray();
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery(
                                "SELECT event_key,bssid,ssid,frequency,capabilities,network_type,"
                                        + "level,lat,lon,altitude,accuracy,observed_at_ms,external,"
                                        + "mfgrid,source,source_row_id "
                                        + "FROM observation ORDER BY observed_at_ms DESC,_id DESC LIMIT ?",
                                new String[] {Integer.toString(bounded)})) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                row.put("event_key", cursor.getString(0));
                row.put("bssid", cursor.getString(1));
                row.put("ssid", cursor.getString(2));
                row.put("frequency", cursor.getInt(3));
                row.put("capabilities", cursor.getString(4));
                row.put("network_type", cursor.getString(5));
                row.put("level", cursor.getInt(6));
                putJsonNullable(row, "lat", cursor, 7);
                putJsonNullable(row, "lon", cursor, 8);
                putJsonNullable(row, "altitude", cursor, 9);
                putJsonNullable(row, "accuracy", cursor, 10);
                row.put("observed_at_ms", cursor.getLong(11));
                row.put("external", cursor.getInt(12));
                row.put("mfgrid", cursor.getInt(13));
                row.put("source", cursor.getString(14));
                if (cursor.isNull(15)) row.put("source_row_id", JSONObject.NULL); else row.put("source_row_id", cursor.getLong(15));
                rows.put(row);
            }
        }
        return rows;
    }

    private static void putJsonNullable(JSONObject target, String key, Cursor cursor, int index) {
        if (cursor.isNull(index)) target.put(key, JSONObject.NULL);
        else target.put(key, cursor.getDouble(index));
    }

    private long count(String table) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    static void upsertNetwork(
            SQLiteDatabase db,
            String bssid,
            String ssid,
            int frequency,
            String capabilities,
            String networkType,
            int level,
            Double lat,
            Double lon,
            long observedAtMs,
            String rcois,
            int mfgrid,
            String service,
            String source) {
        ContentValues insert = new ContentValues();
        insert.put("bssid", clean(bssid, 128));
        insert.put("ssid", clean(ssid, 1024));
        insert.put("frequency", frequency);
        insert.put("capabilities", clean(capabilities, 4096));
        insert.put("network_type", clean(networkType, 16));
        insert.put("first_seen_ms", observedAtMs);
        insert.put("last_seen_ms", observedAtMs);
        insert.put("best_level", level);
        insert.put("last_level", level);
        putNullable(insert, "last_lat", lat);
        putNullable(insert, "last_lon", lon);
        putNullable(insert, "best_lat", lat);
        putNullable(insert, "best_lon", lon);
        insert.put("rcois", clean(rcois, 4096));
        insert.put("mfgrid", mfgrid);
        insert.put("service", clean(service, 4096));
        insert.put("source", clean(source, 128));

        long row = db.insertWithOnConflict("network", null, insert, SQLiteDatabase.CONFLICT_IGNORE);
        if (row != -1L) return;

        ContentValues update = new ContentValues();
        update.put("ssid", clean(ssid, 1024));
        update.put("frequency", frequency);
        update.put("capabilities", clean(capabilities, 4096));
        update.put("network_type", clean(networkType, 16));
        update.put("last_seen_ms", observedAtMs);
        update.put("last_level", level);
        putNullable(update, "last_lat", lat);
        putNullable(update, "last_lon", lon);
        update.put("rcois", clean(rcois, 4096));
        update.put("mfgrid", mfgrid);
        update.put("service", clean(service, 4096));

        try (Cursor cursor =
                db.rawQuery("SELECT best_level FROM network WHERE bssid=?", new String[] {clean(bssid, 128)})) {
            if (cursor.moveToFirst() && level > cursor.getInt(0)) {
                update.put("best_level", level);
                putNullable(update, "best_lat", lat);
                putNullable(update, "best_lon", lon);
            }
        }
        db.update("network", update, "bssid=?", new String[] {clean(bssid, 128)});
    }

    static void importNetworkSummary(
            SQLiteDatabase db,
            String bssid,
            String ssid,
            int frequency,
            String capabilities,
            String networkType,
            long lastSeenMs,
            Double lastLat,
            Double lastLon,
            int bestLevel,
            Double bestLat,
            Double bestLon,
            String rcois,
            int mfgrid,
            String service) {
        ContentValues values = new ContentValues();
        values.put("bssid", clean(bssid, 128));
        values.put("ssid", clean(ssid, 1024));
        values.put("frequency", frequency);
        values.put("capabilities", clean(capabilities, 4096));
        values.put("network_type", clean(networkType, 16));
        values.put("first_seen_ms", lastSeenMs);
        values.put("last_seen_ms", lastSeenMs);
        values.put("best_level", bestLevel);
        values.put("last_level", bestLevel);
        putNullable(values, "last_lat", lastLat);
        putNullable(values, "last_lon", lastLon);
        putNullable(values, "best_lat", bestLat);
        putNullable(values, "best_lon", bestLon);
        values.put("rcois", clean(rcois, 4096));
        values.put("mfgrid", mfgrid);
        values.put("service", clean(service, 4096));
        values.put("source", "wigle-sqlite");
        db.insertWithOnConflict("network", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    static void insertObservation(
            SQLiteDatabase db,
            String eventKey,
            String bssid,
            String ssid,
            int frequency,
            String capabilities,
            String networkType,
            int level,
            Double lat,
            Double lon,
            Double altitude,
            Float accuracy,
            long observedAtMs,
            int external,
            int mfgrid,
            String source,
            Long sourceRowId) {
        ContentValues values = new ContentValues();
        values.put("event_key", clean(eventKey, 512));
        values.put("bssid", clean(bssid, 128));
        values.put("ssid", clean(ssid, 1024));
        values.put("frequency", frequency);
        values.put("capabilities", clean(capabilities, 4096));
        values.put("network_type", clean(networkType, 16));
        values.put("level", level);
        putNullable(values, "lat", lat);
        putNullable(values, "lon", lon);
        putNullable(values, "altitude", altitude);
        if (accuracy == null) values.putNull("accuracy"); else values.put("accuracy", accuracy);
        values.put("observed_at_ms", observedAtMs);
        values.put("external", external);
        values.put("mfgrid", mfgrid);
        values.put("source", clean(source, 128));
        if (sourceRowId == null) values.putNull("source_row_id"); else values.put("source_row_id", sourceRowId);
        values.put("ingested_at_ms", System.currentTimeMillis());
        db.insertWithOnConflict("observation", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    static void insertRoute(
            SQLiteDatabase db,
            String eventKey,
            long runId,
            int wifiVisible,
            int cellVisible,
            int btVisible,
            double lat,
            double lon,
            Double altitude,
            Float accuracy,
            long observedAtMs,
            String source,
            Long sourceRowId) {
        ContentValues values = new ContentValues();
        values.put("event_key", clean(eventKey, 512));
        values.put("run_id", runId);
        values.put("wifi_visible", wifiVisible);
        values.put("cell_visible", cellVisible);
        values.put("bt_visible", btVisible);
        values.put("lat", lat);
        values.put("lon", lon);
        putNullable(values, "altitude", altitude);
        if (accuracy == null) values.putNull("accuracy"); else values.put("accuracy", accuracy);
        values.put("observed_at_ms", observedAtMs);
        values.put("source", clean(source, 128));
        if (sourceRowId == null) values.putNull("source_row_id"); else values.put("source_row_id", sourceRowId);
        values.put("ingested_at_ms", System.currentTimeMillis());
        db.insertWithOnConflict("route", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    static String clean(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static void putNullable(ContentValues values, String key, Double value) {
        if (value == null || !Double.isFinite(value)) values.putNull(key); else values.put(key, value);
    }

    static final class Batch implements AutoCloseable {
        private final SQLiteDatabase db;
        private boolean successful;
        private boolean closed;

        Batch(SQLiteDatabase db) {
            this.db = db;
            db.beginTransaction();
        }

        SQLiteDatabase db() {
            if (closed) throw new IllegalStateException("Import batch closed");
            return db;
        }

        void successful() {
            successful = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (successful) db.setTransactionSuccessful();
            db.endTransaction();
        }
    }
}
