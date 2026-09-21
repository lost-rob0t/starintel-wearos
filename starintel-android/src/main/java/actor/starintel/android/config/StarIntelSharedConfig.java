package actor.starintel.android.config;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class StarIntelSharedConfig {
    public static final String AUTHORITY = "actor.starintel.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);

    public static final String KEY_SERVER_URL = "server.url";
    public static final String KEY_MAP_TILES_BASE_URL = "map.tiles.base_url";
    public static final String KEY_MAP_STYLE_URL = "map.style_url";
    public static final String KEY_PROLOG_RLM_ENDPOINT = "prolog_rlm.endpoint";
    public static final String KEY_HACKMODE_HOME = "hackmode.home";
    public static final String KEY_HACKMODE_COMMAND = "hackmode.command";
    public static final String KEY_HACKMODE_KALI_COMMAND = "hackmode.kali.command";
    public static final String KEY_COLLECTOR_DEFAULT_DATASET = "collector.dataset.default";
    public static final String KEY_COLLECTOR_RULESET = "collector.ruleset";

    private final ContentResolver resolver;

    public StarIntelSharedConfig(Context context) {
        this.resolver = context.getApplicationContext().getContentResolver();
    }

    public String get(String key, String fallback) {
        try {
            Bundle args = new Bundle();
            args.putString("key", key);
            Bundle response = resolver.call(URI, "get", null, args);
            if (response == null) return fallback;
            String value = response.getString("value");
            return value == null ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
    }

    public boolean put(String key, String value) {
        try {
            Bundle args = new Bundle();
            args.putString("key", key);
            args.putString("value", value == null ? "" : value);
            Bundle response = resolver.call(URI, "put", null, args);
            return response != null && response.getBoolean("ok", false);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public Map<String, String> snapshot() {
        try {
            Bundle response = resolver.call(URI, "snapshot", null, null);
            if (response == null) return Collections.emptyMap();
            Bundle values = response.getBundle("values");
            if (values == null) return Collections.emptyMap();
            Map<String, String> result = new HashMap<>();
            for (String key : values.keySet()) {
                Object value = values.get(key);
                if (value instanceof String) result.put(key, (String) value);
            }
            return Collections.unmodifiableMap(result);
        } catch (RuntimeException unavailable) {
            return Collections.emptyMap();
        }
    }
}
