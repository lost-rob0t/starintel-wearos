package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class StarDocumentFactoryTest {
    private static final String DATASET = "starintel-field-2026-09-24";
    private static final String HASH = "aa".repeat(32);

    @Test
    public void envelopeUsesCanonicalFlat0101Contract() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.personDocument(
                DATASET, "Ada Lovelace", "starintel:transcript:abc"));
        assertEquals("0.10.1", document.getString("schemaVersion"));
        assertEquals("person", document.getString("dtype"));
        assertEquals(DATASET, document.getString("dataset"));
        assertTrue(document.has("id"));
        assertEquals("Ada Lovelace", document.getString("displayName"));
        assertFalse(document.has("_id"));
        assertFalse(document.has("data"));
        assertFalse(document.has("schema_version"));
    }

    @Test
    public void deterministicIdsAreStableAndDistinct() {
        String first = StarDocumentFactory.deterministicId(DATASET, "person", "Ada", "src");
        String again = StarDocumentFactory.deterministicId(DATASET, "person", "Ada", "src");
        String other = StarDocumentFactory.deterministicId(DATASET, "person", "Charles", "src");
        assertEquals(first, again);
        assertNotEquals(first, other);
        assertTrue(first.startsWith("starintel:person:"));
    }

    @Test
    public void pictureCarriesHashCaptureAndGeoReference() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.pictureDocument(
                DATASET, "photo-1", "photo.jpg", "/private/photo.jpg", 1_234L, HASH,
                1_790_208_000_000L, 1920, 1080, 1, "starintel:geo-point:x", "run-1"));
        assertEquals("picture", document.getString("dtype"));
        assertEquals("image/jpeg", document.getString("mediaType"));
        assertEquals(HASH, document.getString("bytesHash"));
        assertEquals("sha256", document.getString("bytesHashAlgorithm"));
        assertEquals(1920, document.getInt("width"));
        assertReference(document.getJSONObject("location"), "geo-point", "starintel:geo-point:x");
        assertEquals("run-1", document.getString("runId"));
    }

    @Test
    public void videoIsFirstClassAndReadyForDerivedActors() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.videoDocument(
                DATASET, "video-1", "clip.mp4", "/private/clip.mp4", "video/mp4",
                99_000L, HASH, 1_790_208_000_000L, 12_500L, 1280, 720, 90,
                "h264", "starintel:geo-point:v", "run-video"));
        assertEquals("video", document.getString("dtype"));
        assertEquals("mp4", document.getString("container"));
        assertEquals("12.5", document.getString("durationSeconds"));
        assertEquals("h264", document.getString("codec"));
        assertEquals(90, document.getJSONObject("extractedMetadata").getInt("orientation"));
        assertFalse(document.has("frames"));
        assertFalse(document.has("ocrObservations"));
    }

    @Test
    public void audioAndTranscriptPreserveProvenance() throws Exception {
        String audioRaw = StarDocumentFactory.audioDocument(
                DATASET, "audio-1", "segment.wav", "audio/wav", "/private/segment.wav",
                32_000L, HASH, 1_790_208_000_000L, 2_000L,
                "starintel:geo-point:a", "run-audio");
        JSONObject audio = new JSONObject(audioRaw);
        assertEquals("audio", audio.getString("dtype"));
        assertEquals(16_000, audio.getInt("sampleRateHz"));
        assertEquals("2", audio.getString("durationSeconds"));

        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "whisper.cpp:base.en-q5_1", "en",
                Arrays.asList(new TranscriptModels.Segment(0.0d, 2.0d, "Ada spoke.")));
        JSONObject document = new JSONObject(StarDocumentFactory.transcriptDocument(
                DATASET, audio.getString("id"), transcript, transcript.engine, 0.75d,
                1_790_208_000_000L, "run-audio"));
        assertEquals("transcript", document.getString("dtype"));
        assertReference(document.getJSONObject("sourceMedia"), "audio", audio.getString("id"));
        assertEquals("Ada spoke.", document.getString("text"));
        assertEquals("0.75", document.getString("confidence"));
        assertEquals(1, document.getJSONArray("wordTimings").length());
    }

    @Test
    public void wifiObservationIsEventScopedAndGeographic() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.wifiObservationDocument(
                DATASET, "wifi-event-1", "AA:BB:CC:DD:EE:FF", "field-net", 2412,
                "[WPA2-PSK-CCMP][ESS]", -55, 1_790_208_000_000L,
                "starintel:geo-point:wifi", 4.5f, "run-wireless"));
        assertEquals("wireless-network", document.getString("dtype"));
        assertEquals("wpa2-psk", document.getString("security"));
        assertEquals(1, document.getInt("channel"));
        assertEquals("wifi-event-1", document.getString("sourceNetworkId"));
        assertEquals(document.getLong("firstSeen"), document.getLong("lastSeen"));
        assertReference(
                document.getJSONObject("location"), "geo-point", "starintel:geo-point:wifi");
    }

    @Test
    public void bluetoothObservationKeepsPlatformExposedIdentifiers() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.bluetoothObservationDocument(
                DATASET, "ble-event-1", "12:34:56:78:9A:BC", "Beacon", -62, -8,
                new JSONArray().put("0000180f-0000-1000-8000-00805f9b34fb"),
                1_790_208_000_000L, "run-wireless"));
        assertEquals("wireless-station", document.getString("dtype"));
        assertEquals("12:34:56:78:9a:bc", document.getString("mac"));
        assertEquals("bluetooth-le", document.getJSONObject("extensions").getString("transport"));
        assertFalse(document.has("location"));
    }

    @Test
    public void geoPointValidatesCoordinates() throws Exception {
        JSONObject point = new JSONObject(StarDocumentFactory.geoPointDocument(
                DATASET, "geo-1", 40.7128d, -74.0060d, 12.0d, 3.5f,
                1_790_208_000_000L, "run-geo"));
        assertEquals("geo-point", point.getString("dtype"));
        assertEquals("point", point.getString("geometryType"));
        assertEquals("EPSG:4326", point.getString("coordinateReferenceSystem"));
        assertEquals("40.7128", point.getString("latitude"));
        assertEquals("-74.006", point.getString("longitude"));
        assertEquals("12", point.getString("altitudeMeters"));
        assertEquals("3.5", point.getString("accuracyMeters"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void geoPointRejectsInvalidLatitude() {
        StarDocumentFactory.geoPointDocument(
                DATASET, "bad", 91.0d, 0.0d, null, null, 1L, "run");
    }

    @Test
    public void relationUsesCanonicalEndpoints() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.relationDocument(
                DATASET, "starintel:person:a", "mentioned-in", "starintel:transcript:b",
                0.3d, "offset 0-12"));
        assertReference(document.getJSONObject("source"), "person", "starintel:person:a");
        assertReference(
                document.getJSONObject("destination"), "transcript", "starintel:transcript:b");
        assertEquals("directed", document.getString("direction"));
        assertEquals("0.3", document.getString("weight"));
        assertEquals("0.3", document.getString("confidence"));
    }

    @Test
    public void entityProjectionYieldsPeopleOrgsAndRelations() throws Exception {
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "engine", "en", Arrays.asList(new TranscriptModels.Segment(
                        0.0d, 3.0d,
                        "Ada Lovelace emailed ada@example.org from Example Dynamics Inc")));
        List<String> documents = StarDocumentFactory.entityDocuments(
                DATASET, "starintel:transcript:proj", transcript.fullText(), 1_795_000_000_000L);
        long people = documents.stream().filter(raw -> dtypeEquals(raw, "person")).count();
        long orgs = documents.stream().filter(raw -> dtypeEquals(raw, "org")).count();
        long relations = documents.stream().filter(raw -> dtypeEquals(raw, "relation")).count();
        assertTrue(people >= 1);
        assertTrue(orgs >= 1);
        assertEquals(people + orgs, relations);
    }

    @Test
    public void commonWifiChannelsAreCorrect() {
        assertEquals(1, StarDocumentFactory.wifiChannel(2412));
        assertEquals(14, StarDocumentFactory.wifiChannel(2484));
        assertEquals(36, StarDocumentFactory.wifiChannel(5180));
        assertEquals(5, StarDocumentFactory.wifiChannel(5975));
    }

    private static boolean dtypeEquals(String raw, String dtype) {
        try {
            return new JSONObject(raw).optString("dtype").equals(dtype);
        } catch (Exception failure) {
            return false;
        }
    }

    private static void assertReference(JSONObject reference, String dtype, String id)
            throws Exception {
        assertEquals(StarDocumentFactory.AUTHORITY_LIBRARY + "/" + dtype,
                reference.getString("schema"));
        assertEquals(id, reference.getString("id"));
        assertEquals(2, reference.length());
    }
}
