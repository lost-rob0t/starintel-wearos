package actor.starintel.collector;

import actor.starintel.android.config.StarIntelSharedConfig;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class HackmodeOperationClient {
    private static final String PACKAGE = "actor.starintel.hackmode";
    private static final String SERVICE = "actor.starintel.hackmode.HackmodeActorService";
    private static final String ACTION = "actor.starintel.action.HACKMODE_OPERATION";
    private static final String EXTRA = "actor.starintel.extra.HACKMODE_OPERATION_JSON";

    private HackmodeOperationClient() {}

    static void submitRecentObservations(
            Context context,
            StarWirelessStore store,
            StarIntelSharedConfig config,
            int limit) {
        JSONArray observations = store.recentObservations(limit);
        if (observations.length() == 0) {
            throw new IllegalStateException("No observations available");
        }

        JSONObject payload = new JSONObject()
                .put(
                        "dataset_hint",
                        config.get(
                                StarIntelSharedConfig.KEY_COLLECTOR_DEFAULT_DATASET,
                                "field-observations"))
                .put(
                        "ruleset",
                        config.get(
                                StarIntelSharedConfig.KEY_COLLECTOR_RULESET,
                                "field-default"))
                .put("observations", observations);

        JSONObject envelope = new JSONObject()
                .put("kind", "collector.observation_batch")
                .put("request_id", "android-" + UUID.randomUUID())
                .put("payload", payload);

        Intent intent = new Intent(ACTION);
        intent.setClassName(PACKAGE, SERVICE);
        intent.putExtra(EXTRA, envelope.toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
