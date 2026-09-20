package actor.starintel.collector;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcelable;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

final class ObservationInbox {
    private static final String PREFS = "collector_inbox_v1";
    private static final String KEY_ROWS = "rows";
    private static final int MAX_ROWS = 100;
    private static final int MAX_URIS = 32;
    private static final int MAX_TEXT = 4096;

    private ObservationInbox() {}

    static int capture(Context context, Intent intent) {
        JSONObject row = new JSONObject();
        JSONArray uris = new JSONArray();
        try {
            row.put("captured_at_ms", System.currentTimeMillis());
            row.put("action", safe(intent.getAction(), 128));
            row.put("mime_type", safe(intent.getType(), 256));

            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) row.put("text", safe(text.toString(), MAX_TEXT));

            Uri data = intent.getData();
            if (data != null) addUri(uris, data);

            if (intent.getClipData() != null) {
                for (int i = 0; i < intent.getClipData().getItemCount() && uris.length() < MAX_URIS; i++) {
                    Uri uri = intent.getClipData().getItemAt(i).getUri();
                    if (uri != null) addUri(uris, uri);
                }
            }

            collectStreamExtras(intent, uris);
            row.put("uris", uris);
        } catch (Exception ignored) {
            return count(context);
        }

        JSONArray rows = load(context);
        JSONArray next = new JSONArray();
        int start = Math.max(0, rows.length() - (MAX_ROWS - 1));
        for (int i = start; i < rows.length(); i++) next.put(rows.opt(i));
        next.put(row);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROWS, next.toString())
                .commit();
        return next.length();
    }

    @SuppressWarnings("deprecation")
    private static void collectStreamExtras(Intent intent, JSONArray uris) {
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Parcelable> values = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (values != null) {
                for (Parcelable value : values) {
                    if (uris.length() >= MAX_URIS) break;
                    if (value instanceof Uri) addUri(uris, (Uri) value);
                }
            }
            return;
        }

        Parcelable value = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (value instanceof Uri && uris.length() < MAX_URIS) addUri(uris, (Uri) value);
    }

    static int count(Context context) {
        return load(context).length();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ROWS)
                .apply();
    }

    private static JSONArray load(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ROWS, "[]");
        try {
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void addUri(JSONArray target, Uri uri) {
        if (target.length() >= MAX_URIS) return;
        target.put(safe(uri.toString(), 2048));
    }

    private static String safe(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
