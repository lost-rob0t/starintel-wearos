package actor.starintel.collector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/** Projects field captures into the lock-pinned StarIntel document contract. */
public final class StarDocumentFactory {
    public static final String SCHEMA_VERSION = "0.10.1";
    public static final String AUTHORITY_LIBRARY = "org.starintel/core@1";
    public static final String COLLECTOR = "actor.starintel.collector";
    public static final String COLLECTOR_VERSION = "0.4.0-alpha";
    public static final int MAX_FINDINGS = 256;
    public static final int MAX_FINDING_CHARS = 2_000;

    private StarDocumentFactory() {}

    public static String fileDocument(
            String dataset, String name, String mediaType, long sizeBytes,
            String sha256Hex, long createdAtMs) {
        requireHash(sha256Hex);
        try {
            String id = deterministicId(dataset, "file", name, sha256Hex);
            JSONObject document = base(dataset, "file", id, createdAtMs, "file-capture", "");
            putFileFields(document, name, mediaType, sizeBytes, sha256Hex, null);
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project file document", failure);
        }
    }

    public static String audioDocument(
            String dataset, String eventKey, String filename, String mediaType, String localPath,
            long sizeBytes, String sha256Hex, long capturedAtMs, long durationMs,
            String locationId, String runId) {
        requireHash(sha256Hex);
        try {
            String id = deterministicId(dataset, "audio", eventKey, sha256Hex);
            JSONObject document = base(dataset, "audio", id, capturedAtMs, "microphone", runId);
            putFileFields(document, filename, mediaType, sizeBytes, sha256Hex, localPath);
            document.put("codec", "pcm-s16le")
                    .put("container", "wav")
                    .put("sampleRateHz", WavCodec.SAMPLE_RATE_HZ)
                    .put("channels", 1)
                    .put("bitDepth", 16)
                    .put("durationSeconds", decimal(Math.max(0L, durationMs) / 1000.0d))
                    .put("capturedAt", epochSeconds(capturedAtMs));
            putReference(document, "location", locationId);
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project audio document", failure);
        }
    }

    public static String pictureDocument(
            String dataset, String eventKey, String filename, String localPath,
            long sizeBytes, String sha256Hex, long capturedAtMs, int width, int height,
            int orientation, String locationId, String runId) {
        requireHash(sha256Hex);
        try {
            String id = deterministicId(dataset, "picture", eventKey, sha256Hex);
            JSONObject document = base(dataset, "picture", id, capturedAtMs, "camera", runId);
            putFileFields(document, filename, "image/jpeg", sizeBytes, sha256Hex, localPath);
            if (width > 0) document.put("width", width);
            if (height > 0) document.put("height", height);
            if (orientation > 0) document.put("orientation", orientation);
            document.put("capturedAt", epochSeconds(capturedAtMs)).put("pictureKind", "field-capture");
            putReference(document, "location", locationId);
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project picture document", failure);
        }
    }

    public static String videoDocument(
            String dataset, String eventKey, String filename, String localPath, String mediaType,
            long sizeBytes, String sha256Hex, long capturedAtMs, long durationMs,
            int width, int height, int orientation, String codec, String locationId, String runId) {
        requireHash(sha256Hex);
        try {
            String id = deterministicId(dataset, "video", eventKey, sha256Hex);
            JSONObject document = base(dataset, "video", id, capturedAtMs, "camera-video", runId);
            putFileFields(document, filename, mediaType, sizeBytes, sha256Hex, localPath);
            document.put("container", containerOf(mediaType, filename))
                    .put("durationSeconds", decimal(Math.max(0L, durationMs) / 1000.0d))
                    .put("capturedAt", epochSeconds(capturedAtMs));
            if (width > 0) document.put("width", width);
            if (height > 0) document.put("height", height);
            if (orientation > 0) {
                document.put("extractedMetadata", new JSONObject().put("orientation", orientation));
            }
            if (codec != null && !codec.trim().isEmpty()) document.put("codec", bounded(codec, 128));
            putReference(document, "location", locationId);
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project video document", failure);
        }
    }

    public static String transcriptDocument(
            String dataset, String audioId, TranscriptModels.Transcript transcript,
            String engineLabel, double confidence, long startedAtMs, String runId) {
        if (audioId == null || audioId.isEmpty()) throw new IllegalArgumentException("audio id required");
        try {
            String text = bounded(transcript.fullText(), 32_000);
            String id = deterministicId(dataset, "transcript", audioId, engineLabel, text);
            JSONObject document = base(dataset, "transcript", id, startedAtMs, "speech-to-text", runId)
                    .put("sourceMedia", reference("audio", audioId))
                    .put("text", text)
                    .put("model", bounded(engineLabel, 160))
                    .put("actor", COLLECTOR)
                    .put("startedAt", epochSeconds(startedAtMs))
                    .put("completedAt", epochSeconds(System.currentTimeMillis()))
                    .put("confidence", confidence(confidence));
            JSONArray timings = new JSONArray();
            int included = 0;
            for (TranscriptModels.Segment segment : transcript.segments) {
                if (included++ >= MAX_FINDINGS) break;
                timings.put(new JSONObject()
                        .put("startMs", Math.round(segment.startSeconds * 1000.0d))
                        .put("endMs", Math.round(segment.endSeconds * 1000.0d))
                        .put("text", bounded(segment.text, MAX_FINDING_CHARS)));
            }
            if (timings.length() > 0) document.put("wordTimings", timings);
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project transcript document", failure);
        }
    }

    public static String geoPointDocument(
            String dataset, String eventKey, double latitude, double longitude,
            Double altitudeMeters, Float accuracyMeters, long observedAtMs, String runId) {
        if (!Double.isFinite(latitude) || latitude < -90.0d || latitude > 90.0d) {
            throw new IllegalArgumentException("latitude out of range");
        }
        if (!Double.isFinite(longitude) || longitude < -180.0d || longitude > 180.0d) {
            throw new IllegalArgumentException("longitude out of range");
        }
        try {
            String id = deterministicId(dataset, "geo-point", eventKey);
            JSONObject document = base(dataset, "geo-point", id, observedAtMs, "device-location", runId)
                    .put("geometryType", "point")
                    .put("coordinateReferenceSystem", "EPSG:4326")
                    .put("latitude", decimal(latitude))
                    .put("longitude", decimal(longitude));
            if (altitudeMeters != null && Double.isFinite(altitudeMeters)) {
                document.put("altitudeMeters", decimal(altitudeMeters));
            }
            if (accuracyMeters != null && Float.isFinite(accuracyMeters) && accuracyMeters >= 0.0f) {
                document.put("accuracyMeters", decimal(accuracyMeters.doubleValue()));
            }
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project geo point", failure);
        }
    }

    public static String wifiObservationDocument(
            String dataset, String eventKey, String bssid, String ssid, int frequencyMhz,
            String capabilities, int signalDbm, long observedAtMs, String locationId,
            Float locationAccuracyMeters, String runId) {
        if (bssid == null || bssid.trim().isEmpty()) throw new IllegalArgumentException("bssid required");
        try {
            String id = deterministicId(dataset, "wireless-network", eventKey);
            JSONObject document = base(dataset, "wireless-network", id, observedAtMs, "wifi-scan", runId)
                    .put("bssid", bounded(bssid.toLowerCase(Locale.ROOT), 128))
                    .put("security", wifiSecurity(capabilities))
                    .put("signalDbm", signalDbm)
                    .put("sourceNetworkId", eventKey)
                    .put("observations", 1)
                    .put("firstSeen", epochSeconds(observedAtMs))
                    .put("lastSeen", epochSeconds(observedAtMs));
            if (ssid != null && !ssid.isEmpty()) document.put("ssid", bounded(ssid, 1_024));
            if (frequencyMhz > 0) {
                document.put("frequencyMhz", frequencyMhz)
                        .put("channel", wifiChannel(frequencyMhz))
                        .put("band", wifiBand(frequencyMhz));
            }
            if (capabilities != null && !capabilities.isEmpty()) {
                document.put("authMode", bounded(capabilities, 512));
            }
            putReference(document, "location", locationId);
            if (locationAccuracyMeters != null && Float.isFinite(locationAccuracyMeters)) {
                document.put("locationAccuracyMeters", decimal(locationAccuracyMeters.doubleValue()));
            }
            return document.toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project Wi-Fi observation", failure);
        }
    }

    public static String bluetoothObservationDocument(
            String dataset, String eventKey, String address, String name, int signalDbm,
            Integer txPower, JSONArray serviceUuids, long observedAtMs, String runId) {
        if (address == null || address.trim().isEmpty()) throw new IllegalArgumentException("address required");
        try {
            String id = deterministicId(dataset, "wireless-station", eventKey);
            JSONObject extensions = new JSONObject().put("transport", "bluetooth-le");
            if (name != null && !name.trim().isEmpty()) {
                extensions.put("advertisedName", bounded(name, 256));
            }
            if (txPower != null) extensions.put("txPowerDbm", txPower);
            if (serviceUuids != null && serviceUuids.length() > 0) {
                extensions.put("serviceUuids", serviceUuids);
            }
            return base(dataset, "wireless-station", id, observedAtMs, "bluetooth-le-scan", runId)
                    .put("mac", bounded(address.toLowerCase(Locale.ROOT), 128))
                    .put("stationType", "station")
                    .put("signalDbm", signalDbm)
                    .put("observations", 1)
                    .put("firstSeen", epochSeconds(observedAtMs))
                    .put("lastSeen", epochSeconds(observedAtMs))
                    .put("extensions", extensions)
                    .toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project Bluetooth observation", failure);
        }
    }

    public static String personDocument(String dataset, String displayName, String sourceDocId) {
        try {
            String id = deterministicId(dataset, "person", displayName, sourceDocId);
            return base(dataset, "person", id, System.currentTimeMillis(), "entity-extraction", "")
                    .put("displayName", bounded(displayName, 256))
                    .put("bio", "Name candidate extracted from a transcript")
                    .put("confidence", confidence(0.5d))
                    .toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project person document", failure);
        }
    }

    public static String orgDocument(String dataset, String displayName, String sourceDocId) {
        try {
            String id = deterministicId(dataset, "org", displayName, sourceDocId);
            return base(dataset, "org", id, System.currentTimeMillis(), "entity-extraction", "")
                    .put("name", bounded(displayName, 256))
                    .put("description", "Organization candidate extracted from a transcript")
                    .put("confidence", confidence(0.5d))
                    .toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project org document", failure);
        }
    }

    public static String relationDocument(
            String dataset, String sourceId, String predicate, String destinationId,
            double confidence, String note) {
        if (sourceId == null || sourceId.isEmpty()) throw new IllegalArgumentException("source required");
        if (destinationId == null || destinationId.isEmpty()) {
            throw new IllegalArgumentException("destination required");
        }
        try {
            String id = deterministicId(dataset, "relation", sourceId, predicate, destinationId);
            return base(dataset, "relation", id, System.currentTimeMillis(), "document-linking", "")
                    .put("source", reference(dtypeOf(sourceId), sourceId))
                    .put("predicate", bounded(predicate, 128))
                    .put("destination", reference(dtypeOf(destinationId), destinationId))
                    .put("direction", "directed")
                    .put("weight", confidence(confidence))
                    .put("confidence", confidence(confidence))
                    .put("note", bounded(note, 512))
                    .toString();
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not project relation document", failure);
        }
    }

    public static List<String> entityDocuments(
            String dataset, String transcriptDocId, String transcriptText, long capturedAtMs) {
        List<String> documents = new java.util.ArrayList<>();
        for (EntityHeuristics.Candidate candidate : EntityHeuristics.extract(transcriptText)) {
            String entityDoc;
            switch (candidate.kind) {
                case PERSON:
                    entityDoc = personDocument(dataset, candidate.label, transcriptDocId);
                    break;
                case ORG:
                    entityDoc = orgDocument(dataset, candidate.label, transcriptDocId);
                    break;
                default:
                    continue;
            }
            String entityId = idOf(entityDoc);
            documents.add(entityDoc);
            documents.add(relationDocument(
                    dataset, entityId, "mentioned-in", transcriptDocId, candidate.confidence,
                    String.format(Locale.US, "offset %d-%d extracted at %s",
                            candidate.start, candidate.end, rfc3339(capturedAtMs))));
        }
        return documents;
    }

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

    public static long epochSeconds(long epochMs) {
        return Math.max(0L, epochMs / 1_000L);
    }

    public static String rfc3339(long epochMs) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(epochMs));
    }

    private static JSONObject base(
            String dataset, String dtype, String id, long observedAtMs, String method, String runId)
            throws org.json.JSONException {
        if (dataset == null || dataset.trim().isEmpty()) throw new IllegalArgumentException("dataset required");
        JSONObject document = new JSONObject()
                .put("id", id)
                .put("dataset", bounded(dataset, 128))
                .put("dtype", dtype)
                .put("schemaVersion", SCHEMA_VERSION)
                .put("collector", COLLECTOR)
                .put("collectorVersion", COLLECTOR_VERSION)
                .put("collectionMethod", method)
                .put("collectionStatus", "raw")
                .put("collectedAt", epochSeconds(System.currentTimeMillis()));
        if (observedAtMs > 0L) document.put("observedAt", epochSeconds(observedAtMs));
        if (runId != null && !runId.trim().isEmpty()) document.put("runId", bounded(runId, 256));
        return document;
    }

    private static void putFileFields(
            JSONObject document, String name, String mediaType, long sizeBytes,
            String sha256Hex, String localPath) throws org.json.JSONException {
        document.put("name", bounded(name, 512))
                .put("filename", bounded(name, 512))
                .put("mediaType", bounded(mediaType, 128))
                .put("sizeBytes", Math.max(0L, sizeBytes))
                .put("bytesHash", sha256Hex.toLowerCase(Locale.ROOT))
                .put("bytesHashAlgorithm", "sha256")
                .put("contentHash", sha256Hex.toLowerCase(Locale.ROOT))
                .put("hashAlgorithm", "sha256")
                .put("quarantined", false)
                .put("executable", false);
        if (localPath != null && !localPath.trim().isEmpty()) {
            document.put("path", bounded(localPath, 1_024));
        }
    }

    private static void putReference(JSONObject document, String key, String id)
            throws org.json.JSONException {
        if (id != null && !id.trim().isEmpty()) {
            document.put(key, reference("location".equals(key) ? "geo-point" : dtypeOf(id), id));
        }
    }

    private static JSONObject reference(String dtype, String id) throws org.json.JSONException {
        return new JSONObject()
                .put("schema", AUTHORITY_LIBRARY + "/" + bounded(dtype, 128))
                .put("id", id);
    }

    private static String dtypeOf(String id) {
        if (id != null && id.startsWith("starintel:")) {
            int end = id.indexOf(':', "starintel:".length());
            if (end > "starintel:".length()) {
                return id.substring("starintel:".length(), end);
            }
        }
        return "document";
    }

    private static String wifiSecurity(String capabilities) {
        String value = capabilities == null ? "" : capabilities.toUpperCase(Locale.ROOT);
        if (value.contains("WPA3-EAP") || value.contains("SUITE_B_192")) return "wpa3-enterprise";
        if (value.contains("SAE") && value.contains("PSK")) return "wpa2wpa3-psk";
        if (value.contains("SAE")) return "wpa3-psk";
        if (value.contains("EAP")) return "wpa2-enterprise";
        if (value.contains("WPA2") || value.contains("RSN") || value.contains("PSK")) return "wpa2-psk";
        if (value.contains("WPA")) return "wpa-psk";
        if (value.contains("WEP")) return "wep";
        if (value.isEmpty() || value.contains("ESS")) return "open";
        return "unknown";
    }

    static int wifiChannel(int frequencyMhz) {
        if (frequencyMhz == 2_484) return 14;
        if (frequencyMhz >= 2_412 && frequencyMhz <= 2_472) return (frequencyMhz - 2_407) / 5;
        if (frequencyMhz >= 5_000 && frequencyMhz <= 5_895) return (frequencyMhz - 5_000) / 5;
        if (frequencyMhz >= 5_955 && frequencyMhz <= 7_115) return (frequencyMhz - 5_950) / 5;
        return 0;
    }

    private static String wifiBand(int frequencyMhz) {
        if (frequencyMhz < 3_000) return "2.4GHz";
        if (frequencyMhz < 5_950) return "5GHz";
        return "6GHz";
    }

    private static String containerOf(String mediaType, String filename) {
        String value = ((mediaType == null ? "" : mediaType) + " "
                + (filename == null ? "" : filename)).toLowerCase(Locale.ROOT);
        if (value.contains("webm")) return "webm";
        if (value.contains("3gp")) return "3gp";
        return "mp4";
    }

    private static void requireHash(String sha256Hex) {
        if (sha256Hex == null || !sha256Hex.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("valid sha256 required");
        }
    }

    private static String idOf(String raw) {
        try {
            return new JSONObject(raw).optString("id");
        } catch (org.json.JSONException failure) {
            throw new IllegalStateException("Could not read document id", failure);
        }
    }

    private static String confidence(double value) {
        if (!Double.isFinite(value)) return "0";
        return decimal(Math.max(0.0d, Math.min(1.0d, value)));
    }

    private static String decimal(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("finite decimal required");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String bounded(String value, int max) {
        String clean = value == null ? "" : value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
