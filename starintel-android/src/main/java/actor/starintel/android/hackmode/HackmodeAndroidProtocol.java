package actor.starintel.android.hackmode;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

public final class HackmodeAndroidProtocol {
    public static final String VERSION = "HACKMODE-ANDROID/1";
    public static final String PACKAGE_HACKMODE = "actor.starintel.hackmode";
    public static final String SERVICE_HACKMODE =
            "actor.starintel.hackmode.HackmodeActorService";
    public static final String ACTION_OPERATION =
            "actor.starintel.action.HACKMODE_OPERATION";
    public static final String EXTRA_OPERATION_JSON =
            "actor.starintel.extra.HACKMODE_OPERATION_JSON";
    public static final int MAX_OPERATION_CHARS = 256 * 1024;

    public static final Set<String> ALLOWED_KINDS =
            Collections.unmodifiableSet(
                    new HashSet<>(
                            Arrays.asList(
                                    "collector.observation",
                                    "collector.observation_batch",
                                    "collector.rules_evaluate",
                                    "staratak.selection",
                                    "prolog.mapping",
                                    "media.transcription",
                                    "media.sound_classification")));

    private HackmodeAndroidProtocol() {}

    public static JSONObject newRequest(
            String kind,
            String requestId,
            String sourcePackage,
            JSONObject payload) {
        requireKind(kind);
        requireBounded("request_id", requestId, 240);
        requireBounded("source_package", sourcePackage, 240);
        if (payload == null) throw new IllegalArgumentException("payload required");

        try {
            return new JSONObject()
                    .put("protocol", VERSION)
                    .put("kind", kind)
                    .put("request_id", requestId)
                    .put("source_package", sourcePackage)
                    .put("sender_authorization", "signature_permission")
                    .put("payload", payload);
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode Hackmode Android request", error);
        }
    }

    public static JSONObject validateForBridge(String raw, String bridgePackage) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_OPERATION_CHARS) {
            throw new IllegalArgumentException("Operation must be 1..256 KiB");
        }

        final JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Operation JSON is malformed", error);
        }

        if (!VERSION.equals(root.optString("protocol"))) {
            throw new IllegalArgumentException("Unsupported Hackmode Android protocol");
        }

        requireKind(root.optString("kind"));
        requireBounded("request_id", root.optString("request_id"), 240);
        requireBounded("source_package", root.optString("source_package"), 240);

        Object payload = root.opt("payload");
        if (!(payload instanceof JSONObject)) {
            throw new IllegalArgumentException("payload must be an object");
        }

        requireBounded("bridge_package", bridgePackage, 240);
        try {
            root.put("bridge_package", bridgePackage);
            root.put("sender_authorization", "signature_permission");
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode validated operation", error);
        }
        return root;
    }

    private static void requireKind(String kind) {
        String value = kind == null ? "" : kind.trim();
        if (!ALLOWED_KINDS.contains(value)) {
            throw new IllegalArgumentException("Unsupported operation kind");
        }
    }

    private static void requireBounded(String name, String value, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() == 0 || clean.length() > max) {
            throw new IllegalArgumentException(name + " required");
        }
    }
}
