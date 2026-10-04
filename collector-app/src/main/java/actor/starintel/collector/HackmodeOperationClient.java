package actor.starintel.collector;

import actor.starintel.android.config.StarIntelSharedConfig;
import actor.starintel.android.hackmode.HackmodeAndroidProtocol;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

final class HackmodeOperationClient {
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

        JSONObject payload = new JSONObject();
        try {
            payload.put(
                    "dataset_hint",
                    config.get(
                            StarIntelSharedConfig.KEY_COLLECTOR_DEFAULT_DATASET,
                            "field-observations"));
            payload.put(
                    "ruleset",
                    config.get(
                            StarIntelSharedConfig.KEY_COLLECTOR_RULESET,
                            "field-default"));
            payload.put("observations", observations);
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("Could not encode observation batch", error);
        }

        JSONObject envelope =
                HackmodeAndroidProtocol.newRequest(
                        "collector.observation_batch",
                        "android-" + UUID.randomUUID(),
                        context.getPackageName(),
                        payload);

        Intent intent = new Intent(HackmodeAndroidProtocol.ACTION_OPERATION);
        intent.setClassName(
                HackmodeAndroidProtocol.PACKAGE_HACKMODE,
                HackmodeAndroidProtocol.SERVICE_HACKMODE);
        intent.putExtra(
                HackmodeAndroidProtocol.EXTRA_OPERATION_JSON,
                envelope.toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
}
