package actor.starintel.quasar.detail;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Display-only previews, never JSON for export or mutation. Input must remain immutable.
 * Traversal and output are bounded independently of the already-parsed response size.
 * Never invokes a container's toString(), even for fields or unexpected summary values.
 */
public final class RecordDetailPreview {
    public static final int MAX_CHARS = 16384;
    public static final int MAX_NODES = 1024;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_FIELDS = 64;

    public static final class Result {
        public final String text;
        public final boolean truncated;
        public final int visitedNodes;
        public final int scannedCharacters;
        Result(String text, boolean truncated, int nodes, int characters) {
            this.text = text; this.truncated = truncated;
            this.visitedNodes = nodes; this.scannedCharacters = characters;
        }
    }

    private RecordDetailPreview() {}

    public static Result raw(JSONObject root, BooleanSupplier cancelled) {
        return render(root, false, cancelled);
    }

    public static Result fields(JSONObject data, BooleanSupplier cancelled) {
        return render(data, true, cancelled);
    }

    /** Scalar-only summary/provenance access; JSONObject.optString can serialize containers. */
    public static String scalar(JSONObject object, String key, int limit) {
        if (limit < 0 || limit > MAX_CHARS) throw new IllegalArgumentException("Invalid text limit");
        Object value = object.opt(key);
        if (value instanceof String) {
            String text = (String) value;
            return text.substring(0, Math.min(text.length(), limit));
        }
        if (value instanceof Boolean || standardNumber(value)) {
            String text = value.toString();
            return text.substring(0, Math.min(text.length(), limit));
        }
        return "";
    }

    private static boolean standardNumber(Object v) {
        return v instanceof Integer || v instanceof Long || v instanceof Double ||
            v instanceof Float || v instanceof Short || v instanceof Byte;
    }

    private static Result render(JSONObject root, boolean fields, BooleanSupplier cancelled) {
        Writer writer = new Writer(cancelled);
        try {
            if (fields) {
                Iterator<String> keys = root.keys();
                int count = 0;
                while (keys.hasNext()) {
                    if (count++ == MAX_FIELDS) throw Limit.INSTANCE;
                    writer.check();
                    String key = keys.next();
                    writer.string(key);
                    writer.append(": ");
                    writer.value(root.opt(key), 0);
                    writer.append("\n\n");
                }
                if (count == 0) writer.append("No structured fields supplied.");
            } else writer.value(root, 0);
        } catch (Limit exhausted) {
            writer.truncated = true;
        }
        writer.check();
        return new Result(writer.out.toString(), writer.truncated, writer.nodes, writer.characters);
    }

    private static final class Limit extends RuntimeException {
        static final Limit INSTANCE = new Limit();
        private Limit() { super(null, null, false, false); }
    }

    private static final class Writer {
        final StringBuilder out = new StringBuilder(MAX_CHARS);
        final BooleanSupplier cancelled;
        int nodes;
        int characters;
        boolean truncated;
        Writer(BooleanSupplier cancelled) { this.cancelled = cancelled; }
        void check() {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new CancellationException();
        }
        void append(String text) {
            check();
            int remaining = MAX_CHARS - out.length();
            int length = Math.min(remaining, text.length());
            out.append(text, 0, length);
            if (length < text.length()) throw Limit.INSTANCE;
        }
        void string(String text) {
            append("\"");
            for (int i = 0; i < text.length(); i++) {
                check();
                if (out.length() == MAX_CHARS) throw Limit.INSTANCE;
                char c = text.charAt(i);
                characters++;
                switch (c) {
                    case '"': append("\\\""); break;
                    case '\\': append("\\\\"); break;
                    case '\n': append("\\n"); break;
                    case '\r': append("\\r"); break;
                    case '\t': append("\\t"); break;
                    default:
                        if (c < 0x20 || Character.isSurrogate(c)) {
                            String hex = Integer.toHexString(c);
                            append("\\u" + "0000".substring(hex.length()) + hex);
                        } else out.append(c);
                }
            }
            append("\"");
        }
        void value(Object value, int depth) {
            check();
            if (nodes == MAX_NODES || out.length() == MAX_CHARS || depth > MAX_DEPTH)
                throw Limit.INSTANCE;
            nodes++;
            if (value == null || value == JSONObject.NULL) append("null");
            else if (value instanceof JSONObject) {
                append("{");
                JSONObject object = (JSONObject) value;
                Iterator<String> keys = object.keys();
                boolean first = true;
                while (keys.hasNext()) {
                    check();
                    if (!first) append(", ");
                    first = false;
                    String key = keys.next();
                    string(key); append(": "); value(object.opt(key), depth + 1);
                }
                append("}");
            } else if (value instanceof JSONArray) {
                append("[");
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    if (i > 0) append(", ");
                    value(array.opt(i), depth + 1);
                }
                append("]");
            } else if (value instanceof String) string((String) value);
            else if (value instanceof Boolean || standardNumber(value)) append(value.toString());
            else {
                // BigInteger/BigDecimal and arbitrary user objects may have unbounded toString.
                append("\"[unsupported scalar]\"");
                truncated = true;
            }
        }
    }
}
