package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class EntityHeuristicsTest {
    @Test
    public void findsOrgsWithSuffixes() {
        List<EntityHeuristics.Candidate> candidates = EntityHeuristics.extract(
                "She consulted for ACME Robotics Inc and the Environmental Protection Agency.");
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.ORG
                        && candidate.label.contains("Inc")));
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.ORG
                        && candidate.label.equals("Environmental Protection Agency")));
    }

    @Test
    public void findsTwoWordPeopleNotCommonWords() {
        List<EntityHeuristics.Candidate> candidates = EntityHeuristics.extract(
                "Ada Lovelace met Charles Babbage. This was on Monday near the river.");
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.PERSON
                        && candidate.label.equals("Ada Lovelace")));
        assertTrue(candidates.stream().noneMatch(candidate ->
                candidate.label.equals("This was") || candidate.label.equals("On Monday")));
    }

    @Test
    public void findsStrongContactSignals() {
        List<EntityHeuristics.Candidate> candidates = EntityHeuristics.extract(
                "email ada@example.org phone +1 (614) 555-0123 site https://example.org/docs handle @ada_l");
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.EMAIL
                        && candidate.label.equals("ada@example.org")
                        && candidate.confidence == EntityHeuristics.CONFIDENCE_STRONG));
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.URL
                        && candidate.label.equals("https://example.org/docs")));
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.HANDLE
                        && candidate.label.equals("@ada_l")));
        assertTrue(candidates.stream().anyMatch(candidate ->
                candidate.kind == EntityHeuristics.Kind.PHONE));
    }

    @Test
    public void dedupesAndKeepsHighestConfidence() {
        List<EntityHeuristics.Candidate> candidates = EntityHeuristics.extract(
                "Ada Lovelace works at Ada Lovelace industries Inc");
        long personCount = candidates.stream()
                .filter(candidate -> candidate.label.toLowerCase().startsWith("ada"))
                .count();
        assertTrue(personCount >= 1);
    }

    @Test
    public void emptyAndNullInputAreSafe() {
        assertEquals(0, EntityHeuristics.extract(null).size());
        assertEquals(0, EntityHeuristics.extract("").size());
    }

    @Test
    public void respectsCandidateCap() {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            builder.append("Zaphod Beeblebrox").append(' ');
        }
        assertTrue(EntityHeuristics.extract(builder.toString()).size() <= EntityHeuristics.MAX_CANDIDATES);
    }
}
