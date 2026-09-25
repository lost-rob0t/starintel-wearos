package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

public class TranscriptModelsTest {
    @Test
    public void jsonRoundTripPreservesSegments() throws Exception {
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "whisper.cpp:base.en-q5_1",
                "en",
                Arrays.asList(
                        new TranscriptModels.Segment(0.0d, 1.5d, "hello world"),
                        new TranscriptModels.Segment(1.5d, 3.0d, "second segment")));
        String raw = TranscriptModels.toJson(transcript).toString();
        TranscriptModels.Transcript decoded = TranscriptModels.fromJson(raw);
        assertEquals(transcript.engine, decoded.engine);
        assertEquals(transcript.language, decoded.language);
        assertEquals(2, decoded.segments.size());
        assertEquals("hello world", decoded.segments.get(0).text);
        assertEquals(1.5d, decoded.segments.get(1).startSeconds, 0.0001d);
    }

    @Test
    public void fullTextJoinsSegmentsAndCapsLength() {
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "engine", "en",
                Arrays.asList(
                        new TranscriptModels.Segment(0.0d, 1.0d, "alpha"),
                        new TranscriptModels.Segment(1.0d, 2.0d, "beta")));
        assertEquals("alpha beta", transcript.fullText());
        String huge = new String(new char[5_000]).replace('\0', 'x');
        TranscriptModels.Transcript oversized = new TranscriptModels.Transcript(
                "engine", "en",
                Collections.singletonList(new TranscriptModels.Segment(0.0d, 1.0d, huge + huge)));
        assertTrue(transcript.fullText().length() <= TranscriptModels.MAX_TEXT_CHARS);
        assertTrue(oversized.fullText().length() <= TranscriptModels.MAX_TEXT_CHARS);
    }

    @Test
    public void rejectsInvalidSegmentsAndEngines() {
        assertThrows(IllegalArgumentException.class, () -> new TranscriptModels.Segment(-1.0d, 0.0d, "x"));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptModels.Segment(2.0d, 1.0d, "x"));
        assertThrows(IllegalArgumentException.class, () -> new TranscriptModels.Transcript("", "en", null));
        assertThrows(IllegalArgumentException.class,
                () -> new TranscriptModels.Transcript(new String(new char[200]).replace('\0', 'e'), "en", null));
    }

    @Test
    public void fromJsonToleratesErrorPayloads() throws Exception {
        TranscriptModels.Transcript decoded = TranscriptModels.fromJson(
                new JSONObject().put("ok", false).put("error", "x").toString());
        assertEquals(0, decoded.segments.size());
    }
}
