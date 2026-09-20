package actor.starintel.collector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView runtime;
    private TextView inbox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(4, 7, 9));

        TextView eyebrow = text("STARINTEL // COLLECTOR", 12, Color.rgb(85, 235, 255), true);
        eyebrow.setLetterSpacing(.12f);
        root.addView(eyebrow, matchWrap());
        root.addView(text("Collector", 32, Color.WHITE, true), matchWrap(3));
        root.addView(
                text(
                        "Bootstrap runtime: explicit foreground session plus Android share-target intake. Sensor adapters and server upload attach in later slices.",
                        14,
                        Color.rgb(150, 166, 172),
                        false),
                matchWrap(8));

        runtime = text("", 15, Color.WHITE, true);
        root.addView(runtime, matchWrap(24));
        inbox = text("", 13, Color.rgb(150, 166, 172), false);
        root.addView(inbox, matchWrap(4));

        Button start = new Button(this);
        start.setText("START FOREGROUND SESSION");
        start.setOnClickListener(v -> startCollector());
        root.addView(start, matchWrap(18));

        Button stop = new Button(this);
        stop.setText("STOP SESSION");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CollectorService.class));
            refresh();
        });
        root.addView(stop, matchWrap(8));

        Button clear = new Button(this);
        clear.setText("CLEAR LOCAL INBOX");
        clear.setOnClickListener(v -> {
            ObservationInbox.clear(this);
            refresh();
        });
        root.addView(clear, matchWrap(8));

        root.addView(
                text(
                        "Use Android's Share sheet and choose StarIntel Collector to queue text/URI observations. This slice stores bounded metadata only; it does not silently read shared content.",
                        12,
                        Color.rgb(128, 145, 151),
                        false),
                matchWrap(20));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startCollector() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7);
        }

        Intent intent = new Intent(this, CollectorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refresh();
    }

    private void refresh() {
        boolean active = getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                .getBoolean(CollectorService.KEY_ACTIVE, false);
        runtime.setText(active ? "SESSION · ACTIVE" : "SESSION · STOPPED");
        inbox.setText("LOCAL OBSERVATION INBOX · " + ObservationInbox.count(this) + " / 100");
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
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
