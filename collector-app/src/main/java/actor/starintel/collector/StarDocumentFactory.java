package actor.starintel.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Projects collector captures into canonical StarIntel 0.9 documents.
 *
 * Identifiers are content-derived so re-running extraction or re-syncing the same
 * capture produces the same document id (idempotency by construction, never by mutation).
 */
public final class StarDocumentFactory {
    public static final String SCHEMA_VERSION = "0.9.0";
    public static final String SOURCE_LABEL = "actor.starintel.collector";
    public static final int MAX_FINDINGS = 256;
    public static final int MAX_FINDING_CHARS = 2_000;

    private StarDocumentFactory() {}

    public static String fileDocument(
            String dataset,
            String name,
            String mediaType,
            long sizeBytes,
            String sha256Hex,
            long createdAtMs) {
        try {
            JSONObject data = new JSONObject()
                    .put("name", bounded(name, 512))
                    .put("media_type", bounded(mediaType, 128));
            if (sizeBytes >= 0L) data.put("size_bytes", sizeBytes);
            if (sha256Hex != null && !sha256Hex.isEmpty()) {
                data.put("content_hash", sha256Hex).put("hash_algorithm", "sha256");
            }
            if (createdAtMs > 0L) data.put("created_at", rfc3339(createdAtMs));
            return envelope(dataset, "file", data, deterministicId(dataset, "file", name, sha256Hex));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project file document", failure);
        }
    }

    public static String transcriptAnalysisDocument(
            String dataset,
            String fileId,
            TranscriptModels.Transcript transcript,
            String engineLabel,
            double confidence) {
        if (fileId == null || fileId.isEmpty()) throw new IllegalArgumentException("file id required");
        JSONArray findings = new JSONArray();
        int included = 0;
        for (TranscriptModels.Segment segment : transcript.segments) {
            if (included >= MAX_FINDINGS) break;
            findings.put(bounded(
                    String.format(Locale.US, "[%06.1f-%06.1f] %s",
                            segment.startSeconds, segment.endSeconds, segment.text),
                    MAX_FINDING_CHARS));
            included++;
        }
        try {
            JSONObject data = new JSONObject()
                    .put("method", bounded(engineLabel, 160))
                    .put("scope", bounded("audio transcription", 64))
                    .put("input_ids", new JSONArray().put(fileId))
                    .put("findings", findings)
                    .put("conclusions", new JSONArray().put(bounded(transcript.fullText(), 32_000)))
                    .put("limitations", new JSONArray()
                            .put("automated transcription without speaker diarization")
                            .put("timestamps are approximate"))
                    .put("confidence", clamp01(confidence));
            return envelope(
                    dataset,
                    "analysis",
                    data,
                    deterministicId(dataset, "analysis", fileId, engineLabel, transcript.fullText()));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project transcript analysis", failure);
        }
    }

    public static String personDocument(String dataset, String displayName, String sourceDocId) {
        try {
            JSONObject data = new JSONObject()
                    .put("display_name", bounded(displayName, 256))
                    .put("description", bounded(
                            "Name candidate extracted by heuristic transcript analysis", 256));
            return envelope(
                    dataset, "person", data,
                    deterministicId(dataset, "person", displayName, sourceDocId));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project person document", failure);
        }
    }

    public static String orgDocument(String dataset, String displayName, String sourceDocId) {
        try {
            JSONObject data = new JSONObject()
                    .put("display_name", bounded(displayName, 256))
                    .put("description", bounded(
                            "Organization candidate extracted by heuristic transcript analysis", 256));
            return envelope(
                    dataset, "org", data,
                    deterministicId(dataset, "org", displayName, sourceDocId));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project org document", failure);
        }
    }

    public static String relationDocument(
            String dataset,
            String subjectId,
            String predicate,
            String objectId,
            double confidence,
            String note) {
        if (subjectId == null || subjectId.isEmpty()) throw new IllegalArgumentException("subject required");
        if (objectId == null || objectId.isEmpty()) throw new IllegalArgumentException("object required");
        try {
            JSONObject data = new JSONObject()
                    .put("subject", subjectId)
                    .put("predicate", bounded(predicate, 64))
                    .put("object", objectId)
                    .put("directed", true)
                    .put("confidence", clamp01(confidence))
                    .put("note", bounded(note, 512));
            return envelope(
                    dataset, "relation", data,
                    deterministicId(dataset, "relation", subjectId, predicate, objectId));
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project relation document", failure);
        }
    }

    /** Deterministic, collision-resistant document id from the projection inputs. */
    public static String deterministicId(String dataset, String dtype, String... keyParts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
        digest.update(dtype.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update((dataset == null ? "" : dataset).getBytes(StandardCharsets.UTF_8));
        for (String part : keyParts) {
            digest.update((byte) 0);
            digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
        }
        StringBuilder hex = new StringBuilder("starintel:").append(dtype).append(':');
        for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    public static String rfc3339(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }

    private static String envelope(String dataset, String dtype, JSONObject data, String id) {
        if (dataset == null || dataset.trim().isEmpty()) throw new IllegalArgumentException("dataset required");
        try {
            return new JSONObject()
                    .put("_id", id)
                    .put("dataset", bounded(dataset.trim(), 128))
                    .put("dtype", dtype)
                    .put("schema_version", SCHEMA_VERSION)
                    .put("version", 1)
                    .put("date_added", rfc3339(System.currentTimeMillis()))
                    .put("date_updated", rfc3339(System.currentTimeMillis()))
                    .put("sources", new JSONArray().put(SOURCE_LABEL))
                    .put("evidence", new JSONArray())
                    .put("data", data)
                    .toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not encode StarIntel document", failure);
        }
    }

    private static double clamp01(double value) {
        if (!(Double.isFinite(value))) return 0.0d;
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    /** Collects heuristic candidates into entity + relation StarIntel documents. */
    public static List<String> entityDocuments(
            String dataset,
            String analysisDocId,
            String transcriptText,
            long capturedAtMs) {
        List<String> documents = new java.util.ArrayList<>();
        List<EntityHeuristics.Candidate> candidates = EntityHeuristics.extract(transcriptText);
        for (EntityHeuristics.Candidate candidate : candidates) {
            String entityDoc;
            switch (candidate.kind) {
                case PERSON:
                    entityDoc = personDocument(dataset, candidate.label, analysisDocId);
                    break;
                case ORG:
                    entityDoc = orgDocument(dataset, candidate.label, analysisDocId);
                    break;
                default:
                    continue;
            }
            String entityId;
            try {
                entityId = new JSONObject(entityDoc).optString("_id");
            } catch (org.json.JSONException failure) {
                throw new IllegalStateException("Could not read entity id", failure);
            }
            documents.add(entityDoc);
            documents.add(relationDocument(
                    dataset,
                    entityId,
                    "mentioned-in",
                    analysisDocId,
                    candidate.confidence,
                    String.format(Locale.US, "offset %d-%d extracted at %s",
                            candidate.start, candidate.end, rfc3339(capturedAtMs))));
        }
        return documents;
    }
}
