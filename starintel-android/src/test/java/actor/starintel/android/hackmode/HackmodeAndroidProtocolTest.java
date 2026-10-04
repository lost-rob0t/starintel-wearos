package actor.starintel.android.hackmode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONObject;
import org.junit.Test;

public final class HackmodeAndroidProtocolTest {
    @Test
    public void roundTripKeepsSourceAndAddsBridge() throws Exception {
        JSONObject request =
                HackmodeAndroidProtocol.newRequest(
                        "collector.observation_batch",
                        "req-1",
                        "actor.starintel.collector",
                        new JSONObject().put("count", 2));

        JSONObject validated =
                HackmodeAndroidProtocol.validateForBridge(
                        request.toString(),
                        "actor.starintel.hackmode");

        assertEquals(HackmodeAndroidProtocol.VERSION, validated.optString("protocol"));
        assertEquals("actor.starintel.collector", validated.optString("source_package"));
        assertEquals("actor.starintel.hackmode", validated.optString("bridge_package"));
        assertEquals("signature_permission", validated.optString("sender_authorization"));
    }

    @Test
    public void unknownKindsFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HackmodeAndroidProtocol.newRequest(
                                "shell.execute",
                                "req-2",
                                "actor.starintel.collector",
                                new JSONObject()));
    }

    @Test
    public void protocolDowngradeFailsClosed() throws Exception {
        JSONObject request =
                new JSONObject()
                        .put("protocol", "HACKMODE-ANDROID/0")
                        .put("kind", "collector.observation")
                        .put("request_id", "req-3")
                        .put("source_package", "actor.starintel.collector")
                        .put("payload", new JSONObject());

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HackmodeAndroidProtocol.validateForBridge(
                                request.toString(),
                                "actor.starintel.hackmode"));
    }

    @Test
    public void malformedJsonFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HackmodeAndroidProtocol.validateForBridge(
                                "{",
                                "actor.starintel.hackmode"));
    }

    @Test
    public void oversizedRequestsFailBeforeParsing() {
        String oversized = "x".repeat(HackmodeAndroidProtocol.MAX_OPERATION_CHARS + 1);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        HackmodeAndroidProtocol.validateForBridge(
                                oversized,
                                "actor.starintel.hackmode"));
    }
}
