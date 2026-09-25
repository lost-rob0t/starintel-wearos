package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class StarDocumentFactoryTest {
    private static final String DATASET = "star-wireless-audio-2026-09-24";

    @Test
    public void envelopeCarriesRequiredTopLevelFields() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.personDocument(
                DATASET, "Ada Lovelace", "starintel:analysis:abc"));
        assertEquals("0.9.0", document.optString("schema_version"));
        assertEquals("person", document.optString("dtype"));
        assertEquals(DATASET, document.optString("dataset"));
        assertEquals(1, document.optInt("version"));
        assertTrue(document.has("_id"));
        assertTrue(document.has("date_added"));
        assertTrue(document.has("date_updated"));
        assertEquals("actor.starintel.collector", document.optJSONArray("sources").optString(0));
        assertEquals(0, document.optJSONArray("evidence").length());
        assertEquals("Ada Lovelace", document.optJSONObject("data").optString("display_name"));
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
    public void fileDocumentCarriesHashAndSize() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.fileDocument(
                DATASET, "segment-1.wav", "audio/wav", 1_234L,
                "aa".repeat(32), 1_790_208_000_000L));
        JSONObject data = document.optJSONObject("data");
        assertEquals("audio/wav", data.optString("media_type"));
        assertEquals(1_234L, data.optLong("size_bytes"));
        assertEquals("sha256", data.optString("hash_algorithm"));
        assertEquals("aa".repeat(32), data.optString("content_hash"));
    }

    @Test
    public void transcriptAnalysisReferencesInputFile() throws Exception {
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "whisper.cpp:base.en-q5_1", "en",
                Arrays.asList(
                        new TranscriptModels.Segment(0.0d, 2.0d, "Ada Lovelace called."),
                        new TranscriptModels.Segment(2.0d, 4.0d, "Charles Babbage answered.")));
        String raw = StarDocumentFactory.transcriptAnalysisDocument(
                DATASET, "starintel:file:x", transcript, "whisper.cpp:base.en-q5_1", 0.75d);
        JSONObject document = new JSONObject(raw);
        assertEquals("analysis", document.optString("dtype"));
        JSONObject data = document.optJSONObject("data");
        assertEquals("starintel:file:x", data.optJSONArray("input_ids").optString(0));
        assertEquals(2, data.optJSONArray("findings").length());
        assertEquals(0.75d, data.optDouble("confidence"), 0.0001d);
    }

    @Test
    public void relationIsDirectedWithConfidence() throws Exception {
        JSONObject document = new JSONObject(StarDocumentFactory.relationDocument(
                DATASET, "starintel:person:a", "mentioned-in", "starintel:analysis:b", 0.3d, "offset 0-12"));
        JSONObject data = document.optJSONObject("data");
        assertEquals("mentioned-in", data.optString("predicate"));
        assertTrue(data.optBoolean("directed"));
        assertEquals(0.3d, data.optDouble("confidence"), 0.0001d);
    }

    @Test
    public void entityProjectionYieldsPeopleOrgsAndRelations() throws Exception {
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "engine", "en",
                Arrays.asList(new TranscriptModels.Segment(
                        0.0d, 3.0d,
                        "Ada Lovelace emailed ada@example.org from Example Dynamics Inc")));
        String analysisId = "starintel:analysis:proj";
        List<String> documents = StarDocumentFactory.entityDocuments(
                DATASET, analysisId, transcript.fullText(), 1_795_000_000_000L);
        long people = documents.stream().filter(raw -> dtypeEquals(raw, "person")).count();
        long orgs = documents.stream().filter(raw -> dtypeEquals(raw, "org")).count();
        long relations = documents.stream().filter(raw -> dtypeEquals(raw, "relation")).count();
        assertTrue(people >= 1);
        assertTrue(orgs >= 1);
        assertEquals(people + orgs, relations);
    }

    private static boolean dtypeEquals(String raw, String dtype) {
        try {
            return new JSONObject(raw).optString("dtype").equals(dtype);
        } catch (Exception failure) {
            return false;
        }
    }

    @Test
    public void rfc3339IsUtcZulu() {
        assertEquals("2026-09-24T00:00:00Z",
                StarDocumentFactory.rfc3339(1_790_208_000_000L));
    }
}
