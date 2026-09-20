package actor.starintel.maps;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public final class TacticalMapView extends View {
    public static final class GeoPoint {
        public final double latitude;
        public final double longitude;
        public final String label;
        public final String documentId;

        public GeoPoint(double latitude, double longitude, String label, String documentId) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.label = label;
            this.documentId = documentId;
        }
    }

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<GeoPoint> points = new ArrayList<>();

    public TacticalMapView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(2, 5, 7));

        grid.setColor(Color.rgb(25, 45, 50));
        grid.setStrokeWidth(dp(1));

        axis.setColor(Color.rgb(47, 91, 99));
        axis.setStrokeWidth(dp(1.5f));

        marker.setColor(Color.rgb(85, 235, 255));
        marker.setStyle(Paint.Style.STROKE);
        marker.setStrokeWidth(dp(2));

        text.setColor(Color.rgb(196, 218, 223));
        text.setTextSize(dp(11));
        text.setTypeface(Typeface.MONOSPACE);
    }

    public void setPoints(List<GeoPoint> value) {
        points.clear();
        if (value != null) points.addAll(value);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        for (int lon = -150; lon <= 150; lon += 30) {
            float x = projectX(lon, width);
            canvas.drawLine(x, 0, x, height, lon == 0 ? axis : grid);
        }
        for (int lat = -60; lat <= 60; lat += 30) {
            float y = projectY(lat, height);
            canvas.drawLine(0, y, width, y, lat == 0 ? axis : grid);
        }

        text.setTextSize(dp(10));
        canvas.drawText("90N", dp(8), dp(16), text);
        canvas.drawText("90S", dp(8), height - dp(8), text);
        canvas.drawText("180W", dp(8), height / 2f - dp(8), text);
        canvas.drawText("180E", width - dp(40), height / 2f - dp(8), text);

        if (points.isEmpty()) {
            text.setTextSize(dp(14));
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("NO GEO DOCUMENTS", width / 2f, height / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }

        for (GeoPoint point : points) {
            float x = projectX(point.longitude, width);
            float y = projectY(point.latitude, height);
            float r = dp(7);

            canvas.drawCircle(x, y, r, marker);
            canvas.drawLine(x - r * 1.7f, y, x + r * 1.7f, y, marker);
            canvas.drawLine(x, y - r * 1.7f, x, y + r * 1.7f, marker);

            String label = point.label == null || point.label.isBlank()
                    ? compact(point.documentId)
                    : point.label;
            if (!label.isBlank()) {
                text.setTextSize(dp(10));
                canvas.drawText(label, x + dp(10), y - dp(9), text);
            }
        }
    }

    private static float projectX(double longitude, float width) {
        return (float) ((longitude + 180.0) / 360.0 * width);
    }

    private static float projectY(double latitude, float height) {
        return (float) ((90.0 - latitude) / 180.0 * height);
    }

    private static String compact(String value) {
        if (value == null) return "";
        if (value.length() <= 24) return value;
        return value.substring(0, 21) + "...";
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
