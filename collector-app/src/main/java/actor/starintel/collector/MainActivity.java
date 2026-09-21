package actor.starintel.collector;

import actor.starintel.android.config.StarIntelSharedConfig;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 7;
    private static final int REQUEST_WIGLE_DB = 9;

    private TextView runtime;
    private TextView history;
    private StarIntelSharedConfig sharedConfig;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sharedConfig = new StarIntelSharedConfig(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        root.setBackgroundColor(Color.rgb(4, 7, 9));

        TextView eyebrow = text("STARINTEL // STAR WIRELESS", 12, Color.rgb(85, 235, 255), true);
        eyebrow.setLetterSpacing(.12f);
        root.addView(eyebrow, matchWrap());

        root.addView(text("Collector", 32, Color.WHITE, true), matchWrap(3));
        root.addView(
                text(
                        "Wi-Fi + location observations are retained locally with full history. WiGLE SQLite databases can be imported without flattening observation rows.",
                        14,
                        Color.rgb(150, 166, 172),
                        false),
                matchWrap(8));

        runtime = text("", 15, Color.WHITE, true);
        root.addView(runtime, matchWrap(24));

        history = text("", 13, Color.rgb(150, 166, 172), false);
        root.addView(history, matchWrap(4));

        Button start = button("START STAR WIRELESS");
        start.setOnClickListener(v -> startCollector());
        root.addView(start, matchWrap(18));

        Button stop = button("STOP COLLECTION");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CollectorService.class));
            refresh();
        });
        root.addView(stop, matchWrap(8));

        Button dev = button("WI-FI SCAN THROTTLING SETTINGS");
        dev.setOnClickListener(v -> openDeveloperOptions());
        root.addView(dev, matchWrap(8));

        root.addView(
                text(
                        "Developer Options → Networking → Wi-Fi scan throttling → Off. Android does not expose a supported direct intent for that individual switch.",
                        12,
                        Color.rgb(128, 145, 151),
                        false),
                matchWrap(4));

        Button hackmode = button("DISPATCH RECENT BATCH TO HACKMODE");
        hackmode.setOnClickListener(v -> dispatchToHackmode());
        root.addView(hackmode, matchWrap(16));

        Button wigle = button("IMPORT WIGLE SQLITE DATABASE");
        wigle.setOnClickListener(v -> chooseWigleDatabase());
        root.addView(wigle, matchWrap(16));

        root.addView(
                text(
                        "Imports WiGLE network summaries, every location observation, and route history when present. Existing Star Wireless rows are preserved and imported observation IDs are de-duplicated.",
                        12,
                        Color.rgb(128, 145, 151),
                        false),
                matchWrap(4));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startCollector() {
        if (!hasLocationPermission()) {
            requestPermissions(
                    new String[] {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_PERMISSIONS);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_PERMISSIONS);
        }

        Intent intent = new Intent(this, CollectorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refresh();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openDeveloperOptions() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        startActivity(intent);
    }

    private void dispatchToHackmode() {
        try {
            StarWirelessStore store = new StarWirelessStore(this);
            try {
                HackmodeOperationClient.submitRecentObservations(
                        this,
                        store,
                        sharedConfig,
                        250);
            } finally {
                store.close();
            }
            Toast.makeText(this, "Dispatched recent observations to Hackmode", Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Toast.makeText(
                            this,
                            "Hackmode dispatch failed · " + safe(error.getMessage()),
                            Toast.LENGTH_LONG)
                    .show();
        }
    }

    private void chooseWigleDatabase() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_WIGLE_DB);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_WIGLE_DB || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        history.setText("IMPORT · RUNNING");
        new Thread(() -> {
            try {
                StarWirelessStore store = new StarWirelessStore(getApplicationContext());
                WigleSqliteImporter.ImportStats stats =
                        WigleSqliteImporter.importDatabase(getApplicationContext(), uri, store);
                store.close();
                runOnUiThread(() -> {
                    Toast.makeText(
                                    this,
                                    "Imported "
                                            + stats.networks
                                            + " networks · "
                                            + stats.observations
                                            + " observations · "
                                            + stats.routes
                                            + " route points",
                                    Toast.LENGTH_LONG)
                            .show();
                    refresh();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    Toast.makeText(
                                    this,
                                    "WiGLE import failed · " + safe(error.getMessage()),
                                    Toast.LENGTH_LONG)
                            .show();
                    refresh();
                });
            }
        }, "star-wireless-wigle-import").start();
    }

    private void refresh() {
        boolean active =
                getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                        .getBoolean(CollectorService.KEY_ACTIVE, false);
        runtime.setText(active ? "SESSION · ACTIVE" : "SESSION · STOPPED");

        StarWirelessStore store = new StarWirelessStore(this);
        history.setText(
                "NETWORKS · "
                        + store.networkCount()
                        + "    OBSERVATIONS · "
                        + store.observationCount()
                        + "    ROUTE POINTS · "
                        + store.routeCount());
        store.close();
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        return button;
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
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown error";
        String trimmed = value.trim();
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 180);
    }
}
