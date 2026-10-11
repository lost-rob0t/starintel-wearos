package actor.starintel.collector;

/** Local append-only operator evidence; timestamps are wall clock, ordering is insertion order. */
final class MissionAnnotation {
    static final int MAX_TEXT_LENGTH = 2000;
    final String id, runId, kind, intervalId, text, source;
    final long atMs;

    private static boolean blank(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) return false;
        }
        return true;
    }

    MissionAnnotation(String id, String runId, String kind, String intervalId,
            String text, long atMs, String source) {
        if (id == null || blank(id) || runId == null || blank(runId) || atMs <= 0) {
            throw new IllegalArgumentException("Mission and timestamp are required");
        }
        if (!"note".equals(kind) && !"start".equals(kind) && !"end".equals(kind)) {
            throw new IllegalArgumentException("Unknown annotation kind");
        }
        if (!"operator".equals(source) && !("end".equals(kind) && "service_stop".equals(source))) {
            throw new IllegalArgumentException("Invalid annotation source");
        }
        if (!"note".equals(kind) && (intervalId == null || blank(intervalId))) {
            throw new IllegalArgumentException("Interval ID is required");
        }
        if ("start".equals(kind) && !id.equals(intervalId)) {
            throw new IllegalArgumentException("Start event must identify its interval");
        }
        if (text == null || text.length() > MAX_TEXT_LENGTH
                || (!"end".equals(kind) && blank(text))) {
            throw new IllegalArgumentException("Enter between 1 and 2000 characters");
        }
        this.id = id;
        this.runId = runId;
        this.kind = kind;
        this.intervalId = intervalId;
        this.text = text;
        this.atMs = atMs;
        this.source = source;
    }
}
