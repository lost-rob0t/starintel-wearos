package actor.starintel.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;

public final class StarIntelConfigProvider extends ContentProvider {
    private static final String PREFS = "starintel_shared_config_v1";

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    static {
        DEFAULTS.put("server.url", "");
        DEFAULTS.put("map.tiles.base_url", "https://maps.starintel.actor/tiles/{z}/{x}/{y}.png");
        DEFAULTS.put("map.style_url", "https://maps.starintel.actor/style.json");
        DEFAULTS.put("map.attribution", "StarIntel Maps");
        DEFAULTS.put("prolog_rlm.endpoint", "http://127.0.0.1:18765");
        DEFAULTS.put("hackmode.home", "~/hackmode");
        DEFAULTS.put("hackmode.command", "hm");
        DEFAULTS.put("hackmode.kali.command", "nethunter");
        DEFAULTS.put("collector.dataset.default", "field-observations");
        DEFAULTS.put("collector.ruleset", "field-default");
    }

    private SharedPreferences prefs;

    @Override
    public boolean onCreate() {
        prefs = getContext().getSharedPreferences(PREFS, 0);
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if ("get".equals(method)) return getValue(extras);
        if ("put".equals(method)) return putValue(extras);
        if ("snapshot".equals(method)) return snapshot();
        return super.call(method, arg, extras);
    }

    private Bundle getValue(Bundle extras) {
        String key = extras == null ? null : extras.getString("key");
        requireAllowed(key);
        Bundle result = new Bundle();
        result.putString("value", prefs.getString(key, DEFAULTS.get(key)));
        return result;
    }

    private Bundle putValue(Bundle extras) {
        String key = extras == null ? null : extras.getString("key");
        String value = extras == null ? null : extras.getString("value");
        requireAllowed(key);
        if (value == null || value.length() > 8192) throw new IllegalArgumentException("Invalid config value");
        boolean ok = prefs.edit().putString(key, value).commit();
        Bundle result = new Bundle();
        result.putBoolean("ok", ok);
        return result;
    }

    private Bundle snapshot() {
        Bundle values = new Bundle();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            values.putString(
                    entry.getKey(),
                    prefs.getString(entry.getKey(), entry.getValue()));
        }
        Bundle result = new Bundle();
        result.putBundle("values", values);
        return result;
    }

    private static void requireAllowed(String key) {
        if (key == null || !DEFAULTS.containsKey(key)) {
            throw new IllegalArgumentException("Unknown shared config key");
        }
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[] {"key", "value"});
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            cursor.addRow(new Object[] {
                    entry.getKey(),
                    prefs.getString(entry.getKey(), entry.getValue())
            });
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.starintel.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Use ContentResolver.call");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Use ContentResolver.call");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Use ContentResolver.call");
    }
}
