package actor.starintel.collector;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Projects transcribed captures into StarIntel documents (file + analysis + entity
 * candidates + relations) and appends them to the durable upload queue.
 *
 * Evidence stays append-only: projection never rewrites capture rows or their transcripts.
 */
public final class DocumentProjection {
    static final int MAX_QUEUE_BATCH = 500;

    private DocumentProjection() {}

    public static class Result {
        public final int documents;
        public final String dataset;
        public final String analysisDocId;

        Result(int documents, String dataset, String analysisDocId) {
            this.documents = documents;
            this.dataset = dataset;
            this.analysisDocId = analysisDocId;
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

        String fileId = idOf(StarDocumentFactory.fileDocument(
                dataset,
                capture.eventKey + ".wav",
                capture.mediaType,
                capture.sizeBytes,
                capture.sha256,
                capture.createdAtMs));

        int before = 0;
        String analysisJson = StarDocumentFactory.transcriptAnalysisDocument(
                dataset, fileId, transcript, engine, 0.75d);
        String analysisId = idOf(analysisJson);
        enqueue(store, analysisJson);
        before++;

        List<String> entityDocs = StarDocumentFactory.entityDocuments(
                dataset, analysisId, transcript.fullText(), capture.createdAtMs);
        for (String document : entityDocs) enqueue(store, document);
        before += entityDocs.size();

        // The file document is queued last so every referencing doc already has a stable id.
        enqueue(store, StarDocumentFactory.fileDocument(
                dataset, capture.eventKey + ".wav", capture.mediaType,
                capture.sizeBytes, capture.sha256, capture.createdAtMs));
        before++;

        return new Result(before, dataset, analysisId);
    }

    public static void queueObservationBatch(StarWirelessStore store, JSONArray documents) {
        for (int index = 0; index < documents.length(); index++) {
            JSONObject document = documents.optJSONObject(index);
            if (document == null) continue;
            enqueue(store, document.toString());
        }
    }

    private static void enqueue(StarWirelessStore store, String documentJson) {
        JSONObject document;
        try {
            document = new JSONObject(documentJson);
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Projected document is malformed", failure);
        }
        store.enqueueDocument(document.optString("_id"), document.optString("dtype"), documentJson);
        if (store.queuedCount() > MAX_QUEUE_BATCH * 4) {
            throw new IllegalStateException("Document queue exceeded backpressure cap");
        }
    }

    private static String idOf(String documentJson) {
        try {
            return new JSONObject(documentJson).optString("_id");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not read document id", failure);
        }
    }

    private static String datasetOf(StarWirelessStore.CaptureRow capture) {
        return datasetForCapture(capture.createdAtMs);
    }

    public static String datasetForCapture(long createdAtMs) {
        String day = StarDocumentFactory.rfc3339(createdAtMs).substring(0, 10);
        return "star-wireless-audio-" + day;
    }
}
