package actor.starintel.collector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class CollectorMissionActivity extends Activity {
    private static final int REQUEST_MISSION_PERMISSIONS = 41;
    private static final int BACKGROUND = Color.rgb(7, 7, 12);
    private static final int PANEL = Color.rgb(18, 18, 31);
    private static final int RAISED = Color.rgb(25, 24, 42);
    private static final int BORDER = Color.rgb(48, 46, 76);
    private static final int CYAN = Color.rgb(45, 226, 230);
    private static final int PINK = Color.rgb(246, 1, 157);
    private static final int AMBER = Color.rgb(251, 169, 34);
    private static final int LIME = Color.rgb(98, 255, 0);
    private static final int TEXT = Color.rgb(243, 244, 245);
    private static final int MUTED = Color.rgb(164, 166, 184);

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView primary;
    private TextView networks;
    private TextView observations;
    private TextView captures;
    private TextView process;
    private TextView sync;
    private MissionNetworkView networkView;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        root.setBackgroundColor(BACKGROUND);

        LinearLayout brand = row();
        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.addView(eyebrow("QUASAR // STARINTEL"));
        brandText.addView(label("STAR WIRELESS", 19, TEXT, true), match(3));
        brand.addView(brandText, weight());
        TextView more = action("TOOLS", false, () -> openTools(null));
        more.setContentDescription("collector.tools");
        brand.addView(more, new LinearLayout.LayoutParams(dp(88), dp(46)));
        root.addView(brand, match());

        LinearLayout mission = card(CYAN);
        status = label("READY TO COLLECT", 12, LIME, true);
        status.setLetterSpacing(.12f);
        mission.addView(status);
        mission.addView(label("Start Mission", 31, TEXT, true), match(15));
        mission.addView(label(
                "Collect wireless, location, audio and photo evidence. Process it into pinned StarIntel documents for Quasar.",
                14,
                MUTED,
                false), match(7));
        networkView = new MissionNetworkView(this);
        mission.addView(networkView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(176)));
        primary = action("START MISSION", true, this::toggleMission);
        primary.setContentDescription("collector.primary");
        mission.addView(primary, match(5));
        mission.addView(stageRail(), match(17));
        root.addView(mission, match(22));

        LinearLayout evidence = card(null);
        LinearLayout evidenceHeader = row();
        evidenceHeader.addView(eyebrow("RECENT EVIDENCE"), weight());
        TextView documents = link("DOCUMENTS", () -> openTools(null));
        documents.setContentDescription("collector.documents");
        evidenceHeader.addView(documents);
        evidence.addView(evidenceHeader);
        LinearLayout metrics = row();
        networks = metric(metrics, "NETWORKS", CYAN);
        observations = metric(metrics, "OBSERVATIONS", CYAN);
        captures = metric(metrics, "CAPTURES", PINK);
        evidence.addView(metrics, match(17));
        process = pipelineLine("PROCESS", AMBER);
        sync = pipelineLine("SYNC", LIME);
        evidence.addView(process, match(14));
        evidence.addView(sync, match(7));
        root.addView(evidence, match(12));

        root.addView(eyebrow("CAPTURE"), match(23));
        LinearLayout quick = row();
        quick.addView(quickAction("AUDIO", "collector.audio", PINK,
                () -> openTools(MainActivity.ACTION_AUDIO)), weight());
        quick.addView(quickAction("PHOTO", "collector.photo", AMBER,
                () -> openTools(MainActivity.ACTION_PHOTO)), weight(8));
        quick.addView(quickAction("WIGLE", "collector.wigle", LIME,
                () -> openTools(MainActivity.ACTION_WIGLE)), weight(8));
        root.addView(quick, match(10));

        LinearLayout navigation = row();
        navigation.setPadding(0, dp(16), 0, 0);
        navigation.addView(nav("COLLECT", CYAN, () -> {}), weight());
        navigation.addView(nav("EVIDENCE", MUTED, () -> openTools(null)), weight());
        navigation.addView(nav("QUASAR", MUTED, this::openQuasar), weight());
        navigation.addView(nav("SETTINGS", MUTED, () -> openTools(null)), weight());
        root.addView(navigation, match(9));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        refreshHandler.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void toggleMission() {
        boolean active = getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                .getBoolean(CollectorService.KEY_ACTIVE, false);
        if (active) {
            stopService(new Intent(this, CollectorService.class));
            refresh();
            return;
        }
        if (!hasMissionPermissions()) {
            requestPermissions(requiredPermissions(), REQUEST_MISSION_PERMISSIONS);
            return;
        }
        startFullMission();
    }

    private void startFullMission() {
        Intent start = new Intent(this, CollectorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start);
        else startService(start);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startService(new Intent(this, CollectorService.class)
                    .setAction(CollectorService.ACTION_START_AUDIO));
        }
        refresh();
    }

    private boolean hasMissionPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        return new String[] {
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.RECORD_AUDIO
        };
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_MISSION_PERMISSIONS
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startFullMission();
        }
    }

    private void refresh() {
        boolean active = getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                .getBoolean(CollectorService.KEY_ACTIVE, false);
        boolean audio = getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                .getBoolean(CollectorService.KEY_AUDIO_ACTIVE, false);
        StarWirelessStore store = new StarWirelessStore(this);
        CollectorMissionSnapshot snapshot;
        try {
            snapshot = new CollectorMissionSnapshot(
                    active,
                    audio,
                    store.networkCount(),
                    store.observationCount(),
                    store.captureCount(),
                    store.queuedCount(),
                    store.acceptedCount());
        } finally {
            store.close();
        }
        status.setText(snapshot.headline());
        status.setTextColor(active ? LIME : CYAN);
        primary.setText(snapshot.primaryAction());
        primary.setBackground(ripple(active ? PINK : CYAN, active ? PINK : CYAN, 16));
        primary.setTextColor(active ? TEXT : BACKGROUND);
        networks.setText(compact(snapshot.networks));
        observations.setText(compact(snapshot.observations));
        captures.setText(compact(snapshot.captures));
        process.setText("PROCESS   " + snapshot.captures + " captures · " + snapshot.queued + " queued documents");
        sync.setText("SYNC   " + snapshot.accepted + " accepted · " + snapshot.queued + " awaiting upload");
        networkView.setSnapshot(snapshot);
        if (resumed) refreshHandler.postDelayed(this::refresh, 1_500L);
    }

    private void openTools(String action) {
        Intent intent = new Intent(this, MainActivity.class);
        if (action != null) intent.putExtra(MainActivity.EXTRA_ACTION, action);
        startActivity(intent);
    }

    private void openQuasar() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("actor.starintel.quasar");
        if (launch == null) {
            Toast.makeText(this, "Install the Quasar Android package first", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(launch);
    }

    private LinearLayout stageRail() {
        LinearLayout rail = row();
        rail.addView(stage("1", "COLLECT", "Wi-Fi · GPS\nAudio · Photos", CYAN), weight());
        rail.addView(stage("2", "PROCESS", "Transcribe · Extract\nCreate documents", AMBER), weight(6));
        rail.addView(stage("3", "SYNC", "Queue · Upload\nto StarIntel", LIME), weight(6));
        return rail;
    }

    private LinearLayout stage(String number, String title, String detail, int accent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView marker = label(number, 13, accent, true);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(rounded(RAISED, accent, 99));
        column.addView(marker, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView heading = label(title, 11, TEXT, true);
        heading.setLetterSpacing(.12f);
        column.addView(heading, match(8));
        TextView body = label(detail, 10, MUTED, false);
        body.setGravity(Gravity.CENTER);
        column.addView(body, match(5));
        return column;
    }

    private TextView metric(LinearLayout parent, String name, int color) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView value = label("0", 21, TEXT, true);
        column.addView(value);
        TextView caption = label(name, 9, color, true);
        caption.setLetterSpacing(.08f);
        column.addView(caption, match(4));
        parent.addView(column, weight());
        return value;
    }

    private TextView pipelineLine(String title, int accent) {
        TextView view = label(title, 12, MUTED, false);
        view.setPadding(dp(12), dp(11), dp(12), dp(11));
        view.setBackground(rounded(RAISED, BORDER, 12));
        view.setCompoundDrawableTintList(ColorStateList.valueOf(accent));
        return view;
    }

    private TextView quickAction(String title, String description, int accent, Runnable click) {
        TextView view = label(title, 12, TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(68));
        view.setContentDescription(description);
        view.setBackground(ripple(RAISED, accent, 15));
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private TextView nav(String title, int color, Runnable click) {
        TextView view = label(title, 10, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(52));
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private TextView action(String title, boolean primaryAction, Runnable click) {
        TextView view = label(title, 13, primaryAction ? BACKGROUND : TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(52));
        view.setPadding(dp(15), dp(13), dp(15), dp(13));
        view.setBackground(ripple(primaryAction ? CYAN : RAISED, primaryAction ? CYAN : BORDER, 15));
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private TextView link(String title, Runnable click) {
        TextView view = label(title, 10, CYAN, true);
        view.setPadding(dp(12), dp(8), 0, dp(8));
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private LinearLayout card(Integer accent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(PANEL, accent == null ? BORDER : accent, 20));
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView eyebrow(String value) {
        TextView view = label(value, 10, CYAN, true);
        view.setLetterSpacing(.16f);
        return view;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        view.setLineSpacing(0f, 1.12f);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radiusDp));
        background.setStroke(dp(1), stroke);
        return background;
    }

    private RippleDrawable ripple(int fill, int stroke, int radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(55, 255, 255, 255)),
                rounded(fill, stroke, radiusDp),
                null);
    }

    private LinearLayout.LayoutParams match() { return match(0); }

    private LinearLayout.LayoutParams match(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private LinearLayout.LayoutParams weight() { return weight(0); }

    private LinearLayout.LayoutParams weight(int start) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(start);
        return params;
    }

    private static String compact(long value) {
        if (value >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000d);
        if (value >= 1_000) return String.format(java.util.Locale.US, "%.1fK", value / 1_000d);
        return Long.toString(value);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
