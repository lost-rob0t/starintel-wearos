package actor.starintel.collector;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class MissionNetworkView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint node = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CollectorMissionSnapshot snapshot =
            new CollectorMissionSnapshot(false, false, 0, 0, 0, 0, 0);

    MissionNetworkView(Context context) {
        super(context);
        setMinimumHeight(dp(168));
        setContentDescription("Live collection network visualization");
    }

    void setSnapshot(CollectorMissionSnapshot value) {
        snapshot = value;
        setContentDescription(
                "Collection graph with " + value.networks + " networks, "
                        + value.observations + " observations, and " + value.captures + " captures");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float centerX = width * .56f;
        float centerY = height * .50f;
        float radius = Math.min(width, height) * .34f;
        int points = 8;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1));
        line.setColor(Color.rgb(43, 74, 104));
        node.setStyle(Paint.Style.FILL);
        for (int index = 0; index < points; index++) {
            double angle = (Math.PI * 2d * index / points) - Math.PI / 2d;
            float x = centerX + (float) Math.cos(angle) * radius;
            float y = centerY + (float) Math.sin(angle) * radius * .72f;
            canvas.drawLine(centerX, centerY, x, y, line);
            if (index > 0) {
                double previous = (Math.PI * 2d * (index - 1) / points) - Math.PI / 2d;
                canvas.drawLine(
                        centerX + (float) Math.cos(previous) * radius,
                        centerY + (float) Math.sin(previous) * radius * .72f,
                        x,
                        y,
                        line);
            }
            node.setColor(index == points - 1 && snapshot.queued > 0
                    ? Color.rgb(246, 1, 157)
                    : Color.rgb(45, 226, 230));
            canvas.drawCircle(x, y, dp(index % 3 == 0 ? 5 : 3), node);
        }
        node.setColor(snapshot.active ? Color.rgb(98, 255, 0) : Color.rgb(45, 226, 230));
        canvas.drawCircle(centerX, centerY, dp(11), node);
        line.setColor(Color.argb(105, 45, 226, 230));
        canvas.drawCircle(centerX, centerY, radius * .48f, line);
        canvas.drawCircle(centerX, centerY, radius * .78f, line);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
