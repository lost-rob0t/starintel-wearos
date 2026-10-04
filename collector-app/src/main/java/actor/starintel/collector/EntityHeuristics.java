package actor.starintel.collector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded deterministic entity candidates from transcript text.
 *
 * This is intentionally heuristic: every candidate carries a low confidence and the
 * extraction method, so downstream documents never over-claim. No network, no randomness.
 */
public final class EntityHeuristics {
    public static final int MAX_CANDIDATES = 64;
    public static final int MAX_LABEL_CHARS = 120;
    public static final double CONFIDENCE_WEAK = 0.3d;
    public static final double CONFIDENCE_ORG_SUFFIX = 0.6d;
    public static final double CONFIDENCE_STRONG = 0.9d;

    public enum Kind { PERSON, ORG, EMAIL, PHONE, URL, HANDLE }

    public static final class Candidate {
        public final Kind kind;
        public final String label;
        public final int start;
        public final int end;
        public final double confidence;

        Candidate(Kind kind, String label, int start, int end, double confidence) {
            this.kind = kind;
            this.label = label;
            this.start = start;
            this.end = end;
            this.confidence = confidence;
        }
    }

    private static final String ORG_SUFFIX =
            "(?:Inc|LLC|L\\.L\\.C\\.|Ltd|Corp|Corporation|Company|Co|Group|Agency|Department|"
                    + "Foundation|University|Institute|Committee|Council|Bureau|Office|Services|Solutions|Systems)";

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,191}\\.[A-Za-z]{2,24}");
    private static final Pattern URL = Pattern.compile(
            "https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{2,512}");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![0-9A-Za-z])\\+?[0-9][0-9(). \\-]{6,26}[0-9](?![0-9])");
    private static final Pattern HANDLE = Pattern.compile(
            "(?<![A-Za-z0-9@_])@[A-Za-z0-9_]{2,40}");
    private static final Pattern ORG_NAME = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9&.'-]{1,48}"
                    + "(?:(?:\\s+(?:of|the|and|for)\\s+|\\s+)[A-Z][A-Za-z0-9&.'-]{1,48}){0,3}\\s+"
                    + ORG_SUFFIX + ")\\b");
    private static final Pattern PERSON_NAME = Pattern.compile(
            "(?<![A-Z])\\b([A-Z][a-z]{1,31}(?:\\s+(?:[A-Z]\\.\\s*)?[A-Z][a-z]{1,31}){0,2})\\b");

    private static final String[] TITLES = {
        "Mr", "Mrs", "Ms", "Miss", "Dr", "Prof", "Sir", "Madam", "The", "This", "That",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August",
        "September", "October", "November", "December", "I", "He", "She", "We", "They", "It",
    };

    private EntityHeuristics() {}

    public static List<Candidate> extract(String text) {
        List<Candidate> found = new ArrayList<>();
        if (text == null || text.isEmpty()) return found;
        collect(found, EMAIL.matcher(text), Kind.EMAIL, CONFIDENCE_STRONG);
        collect(found, URL.matcher(text), Kind.URL, CONFIDENCE_STRONG);
        collect(found, PHONE.matcher(text), Kind.PHONE, CONFIDENCE_STRONG);
        collect(found, HANDLE.matcher(text), Kind.HANDLE, CONFIDENCE_STRONG);
        collect(found, ORG_NAME.matcher(text), Kind.ORG, CONFIDENCE_ORG_SUFFIX);
        collectPeople(found, PERSON_NAME.matcher(text));
        return dedupe(found);
    }

    private static void collect(List<Candidate> sink, Matcher matcher, Kind kind, double confidence) {
        while (matcher.find() && sink.size() < MAX_CANDIDATES * 4) {
            sink.add(new Candidate(kind, clean(matcher.group()), matcher.start(), matcher.end(), confidence));
            if (sink.size() >= MAX_CANDIDATES * 4) break;
        }
    }

    private static void collectPeople(List<Candidate> sink, Matcher matcher) {
        while (matcher.find() && sink.size() < MAX_CANDIDATES * 4) {
            String label = clean(matcher.group(1));
            if (label.indexOf(' ') < 0) continue;
            if (isCommonWord(label)) continue;
            sink.add(new Candidate(Kind.PERSON, label, matcher.start(1), matcher.end(1), CONFIDENCE_WEAK));
            if (sink.size() >= MAX_CANDIDATES * 4) break;
        }
    }

    private static boolean isCommonWord(String label) {
        for (String title : TITLES) {
            if (label.regionMatches(true, 0, title, 0, title.length())
                    && (label.length() == title.length() || !Character.isLetterOrDigit(label.charAt(title.length())))) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= MAX_LABEL_CHARS ? value : value.substring(0, MAX_LABEL_CHARS);
    }

    private static List<Candidate> dedupe(List<Candidate> candidates) {
        Map<String, Candidate> unique = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String key = candidate.kind + "\u0000" + candidate.label.toLowerCase(java.util.Locale.ROOT);
            Candidate existing = unique.get(key);
            if (existing == null || candidate.confidence > existing.confidence) {
                unique.put(key, candidate);
            }
        }
        List<Candidate> ordered = new ArrayList<>(unique.values());
        java.util.Collections.sort(ordered, (left, right) -> {
            int byConfidence = Double.compare(right.confidence, left.confidence);
            if (byConfidence != 0) return byConfidence;
            return Integer.compare(left.start, right.start);
        });
        if (ordered.size() > MAX_CANDIDATES) {
            return new ArrayList<>(ordered.subList(0, MAX_CANDIDATES));
        }
        return ordered;
    }
}
