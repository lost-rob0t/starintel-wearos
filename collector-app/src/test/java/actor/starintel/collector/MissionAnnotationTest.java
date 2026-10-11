package actor.starintel.collector;

import static org.junit.Assert.*;
import org.junit.Test;

public class MissionAnnotationTest {
    @Test public void notePreservesOperatorTextAndWallClock() {
        MissionAnnotation event = new MissionAnnotation("n1", "mission", "note", "i1",
                "  north entrance\n2 people  ", 1234L, "operator");
        assertEquals("  north entrance\n2 people  ", event.text);
        assertEquals("i1", event.intervalId);
        assertEquals(1234L, event.atMs);
    }

    @Test public void validatesTextWithoutSilentlyTruncatingEvidence() {
        new MissionAnnotation("n", "m", "note", null, "x".repeat(2000), 1, "operator");
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("n", "m", "note", null, "x".repeat(2001), 1, "operator"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("n", "m", "note", null, " \n ", 1, "operator"));
    }

    @Test public void requiresIdentifiableMissionAndInterval() {
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("n", "", "note", null, "note", 1, "operator"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("s", "m", "start", "other", "interval", 1, "operator"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("e", "m", "end", null, "", 1, "operator"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("n", "m", "note", null, "note", 0, "operator"));
    }

    @Test public void distinguishesServiceStopFromOperatorEvidence() {
        MissionAnnotation event = new MissionAnnotation("e", "m", "end", "s", "", 1, "service_stop");
        assertEquals("service_stop", event.source);
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("s", "m", "start", "s", "interval", 1, "service_stop"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("e", "m", "end", "s", "", 1, "unknown"));
        assertThrows(IllegalArgumentException.class, () ->
                new MissionAnnotation("e", "m", "edit", "s", "text", 1, "operator"));
    }
}
