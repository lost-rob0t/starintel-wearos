package actor.starintel.collector;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class StarWirelessStore extends SQLiteOpenHelper {
    private static final String NAME = "star-wireless.db";
    private static final int VERSION = 2;

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

        createV2Tables(db);
    }

    private static void createV2Tables(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS capture ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "event_key TEXT UNIQUE NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "file_path TEXT NOT NULL,"
                        + "media_type TEXT NOT NULL,"
                        + "size_bytes INTEGER NOT NULL DEFAULT 0,"
                        + "sha256 TEXT NOT NULL DEFAULT '',"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "duration_ms INTEGER NOT NULL DEFAULT 0,"
                        + "state TEXT NOT NULL,"
                        + "transcript TEXT NOT NULL DEFAULT '',"
                        + "transcript_engine TEXT NOT NULL DEFAULT '',"
                        + "projected INTEGER NOT NULL DEFAULT 0"
                        + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_capture_state ON capture(state, created_at_ms)");

        db.execSQL(
                "CREATE TABLE IF NOT EXISTS document_queue ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "doc_key TEXT UNIQUE NOT NULL,"
                        + "dtype TEXT NOT NULL,"
                        + "doc_json TEXT NOT NULL,"
                        + "state TEXT NOT NULL,"
                        + "attempts INTEGER NOT NULL DEFAULT 0,"
                        + "last_error TEXT NOT NULL DEFAULT '',"
                        + "created_at_ms INTEGER NOT NULL,"
                        + "updated_at_ms INTEGER NOT NULL"
                        + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_document_queue_state ON document_queue(state, created_at_ms)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2 && newVersion >= 2) {
            createV2Tables(db);
            return;
        }
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

    double[] recentRouteLatitudes(int limit) {
        return recentRouteColumn("lat", limit);
    }

    double[] recentRouteLongitudes(int limit) {
        return recentRouteColumn("lon", limit);
    }

    private double[] recentRouteColumn(String column, int limit) {
        int bounded = Math.max(1, Math.min(limit, 512));
        java.util.List<Double> values = new java.util.ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT " + column + " FROM route WHERE " + column + " IS NOT NULL "
                        + "ORDER BY observed_at_ms DESC LIMIT " + bounded, null)) {
            while (cursor.moveToNext()) {
                values.add(cursor.getDouble(0));
            }
        }
        double[] result = new double[values.size()];
        for (int index = 0; index < values.size(); index++) {
            result[index] = values.get(index);
        }
        // Chronological order for sparkline rendering.
        for (int left = 0, right = result.length - 1; left < right; left++, right--) {
            double swap = result[left];
            result[left] = result[right];
            result[right] = swap;
        }
        return result;
    }

    long captureCount() {
        return count("capture");
    }

    long queuedCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM document_queue WHERE state IN ('queued','retry')", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    long acceptedCount() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM document_queue WHERE state='accepted'", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        }
    }

    // ---- capture + document queue (v2) ----

    void insertCapture(
            String eventKey,
            String kind,
            String filePath,
            String mediaType,
            long sizeBytes,
            String sha256,
            long createdAtMs,
            long durationMs,
            String state) {
        ContentValues values = new ContentValues();
        values.put("event_key", clean(eventKey, 512));
        values.put("kind", clean(kind, 16));
        values.put("file_path", clean(filePath, 1_024));
        values.put("media_type", clean(mediaType, 128));
        values.put("size_bytes", Math.max(0L, sizeBytes));
        values.put("sha256", clean(sha256, 64));
        values.put("created_at_ms", createdAtMs);
        values.put("duration_ms", Math.max(0L, durationMs));
        values.put("state", clean(state, 32));
        values.put("transcript", "");
        values.put("transcript_engine", "");
        getWritableDatabase()
                .insertWithOnConflict("capture", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    java.util.List<CaptureRow> capturesInState(String state, int limit) {
        int bounded = Math.max(1, Math.min(limit, 64));
        java.util.List<CaptureRow> rows = new java.util.ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT _id,event_key,file_path,media_type,size_bytes,sha256,created_at_ms,duration_ms "
                        + "FROM capture WHERE state=? ORDER BY created_at_ms LIMIT ?",
                new String[] {clean(state, 32), Integer.toString(bounded)})) {
            while (cursor.moveToNext()) {
                rows.add(new CaptureRow(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getLong(4),
                        cursor.getString(5),
                        cursor.getLong(6),
                        cursor.getLong(7)));
            }
        }
        return rows;
    }

    void updateCaptureTranscript(long captureId, String transcriptJson, String engine, String state) {
        ContentValues values = new ContentValues();
        values.put("transcript", transcriptJson == null ? "" : transcriptJson);
        values.put("transcript_engine", clean(engine, 120));
        values.put("state", clean(state, 32));
        getWritableDatabase().update("capture", values, "_id=?",
                new String[] {Long.toString(captureId)});
    }

    String[] captureDetail(long captureId) {
        // returns {state, transcript_json, transcript_engine}
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT state,transcript,transcript_engine FROM capture WHERE _id=?",
                new String[] {Long.toString(captureId)})) {
            if (!cursor.moveToFirst()) return new String[] {"", "", ""};
            return new String[] {cursor.getString(0), cursor.getString(1), cursor.getString(2)};
        }
    }

    boolean captureProjected(long captureId) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT projected FROM capture WHERE _id=?",
                new String[] {Long.toString(captureId)})) {
            return cursor.moveToFirst() && cursor.getInt(0) != 0;
        }
    }

    void markCaptureProjected(long captureId) {
        ContentValues values = new ContentValues();
        values.put("projected", 1);
        getWritableDatabase().update("capture", values, "_id=?",
                new String[] {Long.toString(captureId)});
    }

    static final class CaptureRow {
        final long id;
        final String eventKey;
        final String filePath;
        final String mediaType;
        final long sizeBytes;
        final String sha256;
        final long createdAtMs;
        final long durationMs;

        CaptureRow(long id, String eventKey, String filePath, String mediaType,
                long sizeBytes, String sha256, long createdAtMs, long durationMs) {
            this.id = id;
            this.eventKey = eventKey;
            this.filePath = filePath;
            this.mediaType = mediaType;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.createdAtMs = createdAtMs;
            this.durationMs = durationMs;
        }
    }

    void enqueueDocument(String docKey, String dtype, String docJson) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("doc_key", clean(docKey, 256));
            values.put("dtype", clean(dtype, 64));
            values.put("doc_json", docJson);
            values.put("state", "queued");
            values.put("attempts", 0);
            values.put("last_error", "");
            values.put("created_at_ms", now);
            values.put("updated_at_ms", now);
            db.insertWithOnConflict("document_queue", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    java.util.List<QueuedDocument> queuedDocuments(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        java.util.List<QueuedDocument> rows = new java.util.ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT doc_key,dtype,doc_json FROM document_queue "
                        + "WHERE state IN ('queued','retry') ORDER BY created_at_ms LIMIT ?",
                new String[] {Integer.toString(bounded)})) {
            while (cursor.moveToNext()) {
                rows.add(new QueuedDocument(cursor.getString(0), cursor.getString(1), cursor.getString(2)));
            }
        }
        return rows;
    }

    void markDocument(String docKey, String state, String error, boolean incrementAttempts) {
        getWritableDatabase().execSQL(
                "UPDATE document_queue SET state=?, last_error=?, updated_at_ms=?"
                        + (incrementAttempts ? ", attempts=attempts+1 " : " ")
                        + "WHERE doc_key=?",
                new Object[] {clean(state, 32), clean(error == null ? "" : error, 512),
                        System.currentTimeMillis(), clean(docKey, 256)});
    }

    static final class QueuedDocument {
        final String docKey;
        final String dtype;
        final String docJson;

        QueuedDocument(String docKey, String dtype, String docJson) {
            this.docKey = docKey;
            this.dtype = dtype;
            this.docJson = docJson;
        }
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
                try {
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
                    row.put(
                            "source_row_id",
                            cursor.isNull(15) ? JSONObject.NULL : cursor.getLong(15));
                    rows.put(row);
                } catch (JSONException error) {
                    throw new IllegalStateException("Could not encode wireless observation", error);
                }
            }
        }
        return rows;
    }

    private static void putJsonNullable(JSONObject target, String key, Cursor cursor, int index)
            throws JSONException {
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
        String key = clean(bssid, 128);
        try (Cursor cursor =
                db.rawQuery(
                        "SELECT first_seen_ms,last_seen_ms,best_level,source "
                                + "FROM network WHERE bssid=?",
                        new String[] {key})) {
            if (!cursor.moveToFirst()) {
                ContentValues insert = new ContentValues();
                insert.put("bssid", key);
                insert.put("ssid", clean(ssid, 1024));
                insert.put("frequency", frequency);
                insert.put("capabilities", clean(capabilities, 4096));
                insert.put("network_type", clean(networkType, 16));
                insert.put("first_seen_ms", lastSeenMs);
                insert.put("last_seen_ms", lastSeenMs);
                insert.put("best_level", bestLevel);
                insert.put("last_level", bestLevel);
                putNullable(insert, "last_lat", lastLat);
                putNullable(insert, "last_lon", lastLon);
                putNullable(insert, "best_lat", bestLat);
                putNullable(insert, "best_lon", bestLon);
                insert.put("rcois", clean(rcois, 4096));
                insert.put("mfgrid", mfgrid);
                insert.put("service", clean(service, 4096));
                insert.put("source", "wigle-sqlite");
                db.insertOrThrow("network", null, insert);
                return;
            }

            long existingFirst = cursor.getLong(0);
            long existingLast = cursor.getLong(1);
            int existingBest = cursor.getInt(2);
            String existingSource = cursor.getString(3);

            ContentValues update = new ContentValues();
            update.put("first_seen_ms", Math.min(existingFirst, lastSeenMs));

            if (lastSeenMs >= existingLast) {
                update.put("ssid", clean(ssid, 1024));
                update.put("frequency", frequency);
                update.put("capabilities", clean(capabilities, 4096));
                update.put("network_type", clean(networkType, 16));
                update.put("last_seen_ms", lastSeenMs);
                update.put("last_level", bestLevel);
                putNullable(update, "last_lat", lastLat);
                putNullable(update, "last_lon", lastLon);
                update.put("rcois", clean(rcois, 4096));
                update.put("mfgrid", mfgrid);
                update.put("service", clean(service, 4096));
            }

            if (bestLevel > existingBest) {
                update.put("best_level", bestLevel);
                putNullable(update, "best_lat", bestLat);
                putNullable(update, "best_lon", bestLon);
            }

            update.put(
                    "source",
                    "wigle-sqlite".equals(existingSource) ? "wigle-sqlite" : "mixed");
            db.update("network", update, "bssid=?", new String[] {key});
        }
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
