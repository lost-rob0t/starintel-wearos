package actor.starintel.collector;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Projects captures into canonical StarIntel 0.10.1 media, geo, transcript, entity,
 * and wireless documents and appends them to the durable upload queue.
 *
 * Evidence stays append-only: projection never rewrites capture rows or their transcripts.
 */
public final class DocumentProjection {
    static final int MAX_QUEUE_BATCH = 500;
    static final int WIRELESS_QUEUE_HIGH_WATER = MAX_QUEUE_BATCH * 4;

    private DocumentProjection() {}

    public static class Result {
        public final int documents;
        public final String dataset;
        public final String transcriptDocId;

        Result(int documents, String dataset, String transcriptDocId) {
            this.documents = documents;
            this.dataset = dataset;
            this.transcriptDocId = transcriptDocId;
        }
    }

    /** Queues projection documents for one transcribed capture. */
    public static Result projectCapture(StarWirelessStore store, StarWirelessStore.CaptureRow capture) {
        String[] detail = store.captureDetail(capture.id);
        String state = detail.length > 0 ? detail[0] : "";
        String transcriptJson = detail.length > 1 ? detail[1] : "";
        String engine = detail.length > 2 && !detail[2].isEmpty() ? detail[2] : "unknown";
        if (!"transcribed".equals(state)) {
            throw new IllegalStateException("Capture is not transcribed");
        }
        TranscriptModels.Transcript transcript;
        try {
            transcript = TranscriptModels.fromJson(transcriptJson);
        } catch (Exception failure) {
            throw new IllegalStateException("Stored transcript unreadable", failure);
        }
        String dataset = datasetOf(capture);

        String audioJson = mediaDocument(dataset, capture);
        String audioId = idOf(audioJson);
        enqueue(store, audioJson);

        String transcriptJsonDoc = StarDocumentFactory.transcriptDocument(
                dataset, audioId, transcript, engine, 0.75d, capture.createdAtMs, capture.runId);
        String transcriptId = idOf(transcriptJsonDoc);
        enqueue(store, transcriptJsonDoc);
        int before = 2;

        List<String> entityDocs = StarDocumentFactory.entityDocuments(
                dataset, transcriptId, transcript.fullText(), capture.createdAtMs);
        for (String document : entityDocs) enqueue(store, document);
        before += entityDocs.size();

        queueCaptureLocation(store, dataset, capture);
        if (capture.lat != null && capture.lon != null) before++;
        return new Result(before, dataset, transcriptId);
    }

    /** Queues first-class media metadata immediately; extraction may happen later. */
    public static String projectMediaCapture(StarWirelessStore store, StarWirelessStore.CaptureRow capture) {
        String dataset = datasetOf(capture);
        queueCaptureLocation(store, dataset, capture);
        String media = mediaDocument(dataset, capture);
        enqueue(store, media);
        return idOf(media);
    }

    public static void projectWifiObservation(
            StarWirelessStore store,
            String dataset,
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
            long observedAtMs,
            String runId) {
        String locationId = queueLocation(store, dataset, eventKey, lat, lon, altitude, accuracy,
                observedAtMs, runId);
        enqueue(store, StarDocumentFactory.wifiObservationDocument(
                dataset, eventKey, bssid, ssid, frequency, capabilities, level,
                observedAtMs, locationId, accuracy, runId));
    }

    public static void projectBluetoothObservation(
            StarWirelessStore store,
            String dataset,
            String eventKey,
            String address,
            String name,
            int level,
            Integer txPower,
            JSONArray serviceUuids,
            Double lat,
            Double lon,
            Double altitude,
            Float accuracy,
            long observedAtMs,
            String runId) {
        String locationId = queueLocation(store, dataset, eventKey, lat, lon, altitude, accuracy,
                observedAtMs, runId);
        String station = StarDocumentFactory.bluetoothObservationDocument(
                dataset, eventKey, address, name, level, txPower, serviceUuids, observedAtMs, runId);
        enqueue(store, station);
        if (!locationId.isEmpty()) {
            enqueue(store, StarDocumentFactory.relationDocument(
                    dataset, idOf(station), "observed-at", locationId, 1.0d,
                    "Event-scoped BLE observation; not a permanent device location"));
        }
    }

    public static void queueObservationBatch(StarWirelessStore store, JSONArray documents) {
        for (int index = 0; index < documents.length(); index++) {
            JSONObject document = documents.optJSONObject(index);
            if (document == null) continue;
            enqueue(store, document.toString());
        }
    }

    /**
     * Refills the upload queue from durable observations without materializing a large WiGLE
     * database as duplicate JSON all at once. Rows are marked projected only after every document
     * for that observation has been durably enqueued, so interruption is safe to replay.
     */
    public static int refillWirelessQueue(StarWirelessStore store, int highWaterDocuments) {
        int highWater = Math.max(1, highWaterDocuments);
        long queued = store.queuedCount();
        int projected = 0;
        while (queued < highWater) {
            JSONArray rows = store.unprojectedObservations(MAX_QUEUE_BATCH);
            if (rows.length() == 0) break;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                boolean located = !row.isNull("lat") && !row.isNull("lon");
                boolean bluetooth = "B".equalsIgnoreCase(row.optString("network_type"));
                int documentCount = located ? (bluetooth ? 3 : 2) : 1;
                if (projected > 0 && queued + documentCount > highWater) return projected;

                String eventKey = row.optString("event_key");
                String dataset = datasetForCapture(row.optLong("observed_at_ms"));
                Double lat = nullableDouble(row, "lat");
                Double lon = nullableDouble(row, "lon");
                Double altitude = nullableDouble(row, "altitude");
                Float accuracy = nullableFloat(row, "accuracy");
                String runId = "import:" + row.optString("source", "wireless");
                if (bluetooth) {
                    projectBluetoothObservation(
                            store, dataset, eventKey, row.optString("bssid"), row.optString("ssid"),
                            row.optInt("level", -127), null, new JSONArray(), lat, lon, altitude,
                            accuracy, row.optLong("observed_at_ms"), runId);
                } else {
                    projectWifiObservation(
                            store, dataset, eventKey, row.optString("bssid"), row.optString("ssid"),
                            row.optInt("frequency"), row.optString("capabilities"),
                            row.optInt("level", -127), lat, lon, altitude, accuracy,
                            row.optLong("observed_at_ms"), runId);
                }
                store.markObservationProjected(eventKey);
                projected++;
                queued += documentCount;
            }
        }
        return projected;
    }

    private static void enqueue(StarWirelessStore store, String documentJson) {
        JSONObject document;
        try {
            document = new JSONObject(documentJson);
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Projected document is malformed", failure);
        }
        store.enqueueDocument(document.optString("id"), document.optString("dtype"), documentJson);
    }

    private static Double nullableDouble(JSONObject row, String key) {
        return row.isNull(key) ? null : row.optDouble(key);
    }

    private static Float nullableFloat(JSONObject row, String key) {
        return row.isNull(key) ? null : (float) row.optDouble(key);
    }

    private static String idOf(String documentJson) {
        try {
            return new JSONObject(documentJson).optString("id");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not read document id", failure);
        }
    }

    private static String datasetOf(StarWirelessStore.CaptureRow capture) {
        return datasetForCapture(capture.createdAtMs);
    }

    public static String datasetForCapture(long createdAtMs) {
        String day = StarDocumentFactory.rfc3339(createdAtMs).substring(0, 10);
        return "starintel-field-" + day;
    }

    private static String mediaDocument(String dataset, StarWirelessStore.CaptureRow capture) {
        String locationId = capture.lat == null || capture.lon == null
                ? ""
                : StarDocumentFactory.deterministicId(dataset, "geo-point", capture.eventKey + ":location");
        String filename = new java.io.File(capture.filePath).getName();
        switch (capture.kind) {
            case "audio":
                return StarDocumentFactory.audioDocument(
                        dataset, capture.eventKey, filename, capture.mediaType, capture.filePath,
                        capture.sizeBytes, capture.sha256, capture.createdAtMs, capture.durationMs,
                        locationId, capture.runId);
            case "image":
                return StarDocumentFactory.pictureDocument(
                        dataset, capture.eventKey, filename, capture.filePath, capture.sizeBytes,
                        capture.sha256, capture.createdAtMs, capture.width, capture.height,
                        capture.orientation, locationId, capture.runId);
            case "video":
                return StarDocumentFactory.videoDocument(
                        dataset, capture.eventKey, filename, capture.filePath, capture.mediaType,
                        capture.sizeBytes, capture.sha256, capture.createdAtMs, capture.durationMs,
                        capture.width, capture.height, capture.orientation, capture.codec,
                        locationId, capture.runId);
            default:
                return StarDocumentFactory.fileDocument(
                        dataset, filename, capture.mediaType, capture.sizeBytes,
                        capture.sha256, capture.createdAtMs);
        }
    }

    private static void queueCaptureLocation(
            StarWirelessStore store, String dataset, StarWirelessStore.CaptureRow capture) {
        queueLocation(store, dataset, capture.eventKey, capture.lat, capture.lon, capture.altitude,
                capture.accuracy, capture.createdAtMs, capture.runId);
    }

    private static String queueLocation(
            StarWirelessStore store,
            String dataset,
            String eventKey,
            Double lat,
            Double lon,
            Double altitude,
            Float accuracy,
            long observedAtMs,
            String runId) {
        if (lat == null || lon == null) return "";
        String geo = StarDocumentFactory.geoPointDocument(
                dataset, eventKey + ":location", lat, lon, altitude, accuracy, observedAtMs, runId);
        enqueue(store, geo);
        return idOf(geo);
    }
}
