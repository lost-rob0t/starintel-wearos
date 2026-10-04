package actor.starintel.collector;

import actor.starintel.design.SiTokens;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Route sparkline for the current mission: recent route points plus live radio state.
 *
 * Deterministic projection of route history; no fabricated motion when history is
 * empty. Colors come from the shared design palette only.
 */
public final class MissionNetworkView extends View {
    private static final int MAX_POINTS = 96;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int background;
    private final int border;
    private final int accent;
    private final int muted;
    private final int textColor;

    private double[] latitudes = new double[0];
    private double[] longitudes = new double[0];
    private String statusLine = "NO ROUTE YET";

    public MissionNetworkView(Context context, SiTokens.Palette palette) {
        super(context);
        this.background = palette.raised;
        this.border = palette.border;
        this.accent = palette.accent;
        this.muted = palette.muted;
        this.textColor = palette.muted;
        track.setColor(background);
        track.setStyle(Paint.Style.FILL);
        line.setColor(accent);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(2));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        dot.setColor(palette.accentAlt);
        text.setColor(textColor);
        text.setTextSize(dp(11));
        text.setFakeBoldText(true);
    }

    public void setSnapshot(CollectorMissionSnapshot snapshot) {
        this.latitudes = snapshot.routeLatitudes();
        this.longitudes = snapshot.routeLongitudes();
        this.statusLine = snapshot.networkSummary();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = dp(12);
        canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(), radius, radius, track);
        android.graphics.Paint stroke = new android.graphics.Paint(track);
        stroke.setStyle(android.graphics.Paint.Style.STROKE);
        stroke.setStrokeWidth(dp(1));
        stroke.setColor(border);
        canvas.drawRoundRect(0f, 0f, getWidth(), getHeight(), radius, radius, stroke);

        if (latitudes.length < 2) {
            canvas.drawText(statusLine, dp(14), getHeight() / 2f - dp(4), text);
            canvas.drawText("Points appear as the mission records movement", dp(14), getHeight() / 2f + dp(12), text);
            return;
        }

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < latitudes.length; index++) {
            minLat = Math.min(minLat, latitudes[index]);
            maxLat = Math.max(maxLat, latitudes[index]);
            minLon = Math.min(minLon, longitudes[index]);
            maxLon = Math.max(maxLon, longitudes[index]);
        }
        double spanLat = Math.max(maxLat - minLat, 1e-5d);
        double spanLon = Math.max(maxLon - minLon, 1e-5d);
        float pad = dp(14);

        Path path = new Path();
        float lastX = 0f;
        float lastY = 0f;
        for (int index = 0; index < latitudes.length; index++) {
            float x = (float) ((longitudes[index] - minLon) / spanLon) * (getWidth() - pad * 2) + pad;
            float y = getHeight() - pad - (float) ((latitudes[index] - minLat) / spanLat) * (getHeight() - pad * 2);
            if (index == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
        canvas.drawPath(path, line);
        canvas.drawCircle(lastX, lastY, dp(3), dot);
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
