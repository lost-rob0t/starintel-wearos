package actor.starintel.collector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Transcript value objects shared between engines, the store, and document projection. */
public final class TranscriptModels {
    public static final int MAX_SEGMENTS = 512;
    public static final int MAX_SEGMENT_CHARS = 4_000;
    public static final int MAX_TEXT_CHARS = 64_000;

    private TranscriptModels() {}

    public static final class Segment {
        public final double startSeconds;
        public final double endSeconds;
        public final String text;

        public Segment(double startSeconds, double endSeconds, String text) {
            if (!(Double.isFinite(startSeconds)) || startSeconds < 0.0) {
                throw new IllegalArgumentException("segment start out of range");
            }
            if (!(Double.isFinite(endSeconds)) || endSeconds < startSeconds) {
                throw new IllegalArgumentException("segment end out of range");
            }
            String clean = text == null ? "" : text.trim();
            if (clean.length() > MAX_SEGMENT_CHARS) clean = clean.substring(0, MAX_SEGMENT_CHARS);
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
            this.text = clean;
        }
    }

    public static final class Transcript {
        public final String engine;
        public final String language;
        public final List<Segment> segments;

        public Transcript(String engine, String language, List<Segment> segments) {
            String cleanEngine = engine == null ? "" : engine.trim();
            if (cleanEngine.isEmpty() || cleanEngine.length() > 120) {
                throw new IllegalArgumentException("transcript engine label invalid");
            }
            String cleanLanguage = language == null ? "auto" : language.trim().toLowerCase(Locale.ROOT);
            if (cleanLanguage.length() > 12) cleanLanguage = cleanLanguage.substring(0, 12);
            List<Segment> clean = segments == null
                    ? Collections.<Segment>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(segments));
            if (clean.size() > MAX_SEGMENTS) {
                throw new IllegalArgumentException("transcript has too many segments");
            }
            this.engine = cleanEngine;
            this.language = cleanLanguage;
            this.segments = clean;
        }

        public String fullText() {
            StringBuilder builder = new StringBuilder();
            for (Segment segment : segments) {
                if (builder.length() >= MAX_TEXT_CHARS) break;
                if (builder.length() > 0) builder.append(' ');
                builder.append(segment.text);
            }
            return builder.length() <= MAX_TEXT_CHARS
                    ? builder.toString()
                    : builder.substring(0, MAX_TEXT_CHARS);
        }
    }

    public static JSONObject toJson(Transcript transcript) throws JSONException {
        JSONArray rows = new JSONArray();
        for (Segment segment : transcript.segments) {
            rows.put(new JSONObject()
                    .put("start", segment.startSeconds)
                    .put("end", segment.endSeconds)
                    .put("text", segment.text));
        }
        return new JSONObject()
                .put("engine", transcript.engine)
                .put("language", transcript.language)
                .put("segments", rows);
    }

    public static Transcript fromJson(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        JSONArray rows = root.optJSONArray("segments");
        List<Segment> segments = new ArrayList<>();
        if (rows != null) {
            int count = Math.min(rows.length(), MAX_SEGMENTS);
            for (int index = 0; index < count; index++) {
                JSONObject row = rows.optJSONObject(index);
                if (row == null) continue;
                segments.add(new Segment(
                        row.optDouble("start", 0.0d),
                        row.optDouble("end", 0.0d),
                        row.optString("text", "")));
            }
        }
        return new Transcript(root.optString("engine", "unknown"), root.optString("language", "auto"), segments);
    }
}
