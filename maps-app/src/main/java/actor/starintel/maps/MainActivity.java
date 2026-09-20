package actor.starintel.maps;

import actor.starintel.android.StarIntelAndroidContract;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private TacticalMapView map;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(3, 6, 8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), dp(12));
        header.setBackgroundColor(Color.rgb(8, 14, 17));

        TextView title = new TextView(this);
        title.setText("STARINTEL // MAPS");
        title.setTextSize(18);
        title.setTextColor(Color.rgb(85, 235, 255));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(.1f);
        header.addView(title, matchWrap());

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(Color.rgb(150, 166, 172));
        header.addView(status, matchWrap(4));

        map = new TacticalMapView(this);
        root.addView(header, matchWrap());
        root.addView(map, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f));

        setContentView(root);
        render(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    private void render(Intent intent) {
        List<TacticalMapView.GeoPoint> points = parsePoints(intent);
        map.setPoints(points);
        status.setText(points.isEmpty()
                ? "NO GEO DOCUMENTS · waiting for StarIntel geo payload"
                : points.size() + " GEO DOCUMENT" + (points.size() == 1 ? "" : "S") + " · equirectangular bootstrap projection");
    }

    static List<TacticalMapView.GeoPoint> parsePoints(Intent intent) {
        ArrayList<TacticalMapView.GeoPoint> points = new ArrayList<>();
        if (intent == null) return points;

        if (intent.hasExtra(StarIntelAndroidContract.EXTRA_LATITUDE)
                && intent.hasExtra(StarIntelAndroidContract.EXTRA_LONGITUDE)) {
            double lat = intent.getDoubleExtra(StarIntelAndroidContract.EXTRA_LATITUDE, Double.NaN);
            double lon = intent.getDoubleExtra(StarIntelAndroidContract.EXTRA_LONGITUDE, Double.NaN);
            addPoint(
                    points,
                    lat,
                    lon,
                    intent.getStringExtra(StarIntelAndroidContract.EXTRA_LABEL),
                    intent.getStringExtra(StarIntelAndroidContract.EXTRA_DOCUMENT_ID));
        }

        String payload = intent.getStringExtra(StarIntelAndroidContract.EXTRA_GEO_JSON);
        if (payload == null || payload.trim().isEmpty()) return points;

        try {
            JSONArray rows = new JSONArray(payload);
            int count = Math.min(rows.length(), 500);
            for (int i = 0; i < count; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                addPoint(
                        points,
                        row.optDouble("lat", Double.NaN),
                        row.optDouble("lon", Double.NaN),
                        row.optString("label", ""),
                        row.optString("document_id", ""));
            }
        } catch (Exception ignored) {
            // Malformed optional payload does not erase valid direct coordinates.
        }
        return points;
    }

    private static void addPoint(
            List<TacticalMapView.GeoPoint> points,
            double lat,
            double lon,
            String label,
            String documentId) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return;
        points.add(new TacticalMapView.GeoPoint(
                lat,
                lon,
                label == null ? "" : label,
                documentId == null ? "" : documentId));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return matchWrap(0);
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
