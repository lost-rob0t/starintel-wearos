package actor.starintel.collector;

import actor.starintel.android.config.OperatorContracts;
import actor.starintel.design.Si;
import actor.starintel.design.SiTokens;
import actor.starintel.design.SiTokens.ThemeStore;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Mission screen: the one place a collection session is started, read, and stopped.
 *
 * Structure is fixed: identity row, mission card (status, primary action, stage rail),
 * evidence card (metrics + pipeline state), capture quick actions, and the app rail
 * that hands off to the Operator hub. Flows never loop back into each other.
 */
public final class CollectorMissionActivity extends Activity {
    private static final int REQUEST_MISSION_PERMISSIONS = 41;

    private Si si;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView primaryLabel;
    private Button primary;
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
        si = new Si(this, new ThemeStore(this).current());
        getWindow().setStatusBarColor(si.background());
        getWindow().setNavigationBarColor(si.background());
        handleCollectIntent(getIntent());

        LinearLayout root = si.vertical();
        root.setPadding(si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_XL),
                si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_XXL));
        root.setBackgroundColor(si.background());

        root.addView(identityRow(), si.match());

        LinearLayout mission = si.accentCard(si.accent());
        status = si.label("READY TO COLLECT", SiTokens.TYPE_LABEL, si.ok(), true);
        status.setLetterSpacing(0.12f);
        status.setAllCaps(true);
        mission.addView(status);
        mission.addView(si.display("Start Mission"), si.match(SiTokens.SPACE_XS));
        mission.addView(si.body(
                "One visible session captures wireless, location, audio, and photo evidence, then pins it as StarIntel documents."), si.match(SiTokens.SPACE_S));
        networkView = new MissionNetworkView(this, si.palette());
        mission.addView(networkView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, si.dp(176)));
        primary = si.primaryButton("START MISSION", this::toggleMission);
        primary.setContentDescription("Start or stop the collection mission");
        primaryLabel = (TextView) primary;
        mission.addView(primary, si.match(SiTokens.SPACE_L));
        mission.addView(stageRail(), si.match(SiTokens.SPACE_XL));
        root.addView(mission, si.match(SiTokens.SPACE_XXL));

        root.addView(evidenceCard(), si.match(SiTokens.SPACE_L));
        root.addView(captureRow(), si.match(SiTokens.SPACE_XL));
        root.addView(appRail(), si.match(SiTokens.SPACE_L));

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

    /** Operator handoff: COLLECT opens the mission; an optional kind jumps straight into a tool. */
    private void handleCollectIntent(Intent intent) {
        if (intent == null || !OperatorContracts.ACTION_COLLECT.equals(intent.getAction())) return;
        String kind = intent.getStringExtra(OperatorContracts.EXTRA_COLLECT_KIND);
        if (OperatorContracts.KIND_AUDIO.equals(kind)) openTools(MainActivity.ACTION_AUDIO);
        else if (OperatorContracts.KIND_PHOTO.equals(kind)) openTools(MainActivity.ACTION_PHOTO);
    }

    private LinearLayout identityRow() {
        LinearLayout brand = si.row();
        LinearLayout titles = si.vertical();
        titles.addView(si.eyebrow("StarIntel // Star Wireless"));
        titles.addView(si.title("Collector"), si.match(SiTokens.SPACE_XS));
        brand.addView(titles, si.weight());
        Button tools = si.secondaryButton("Tools", () -> openTools(null));
        tools.setContentDescription("Open capture tools");
        brand.addView(tools, new LinearLayout.LayoutParams(si.dp(96), si.dp(SiTokens.TOUCH_MIN_DP)));
        return brand;
    }

    private LinearLayout evidenceCard() {
        LinearLayout evidence = si.card();
        LinearLayout header = si.row();
        header.addView(si.sectionHeader("Recent evidence"), si.weight());
        TextView documents = textLink("Documents in Quasar", this::openQuasarDocuments);
        documents.setContentDescription("Open collected documents in Quasar");
        header.addView(documents);
        evidence.addView(header);

        LinearLayout metrics = si.row();
        LinearLayout networksColumn = si.metric("Networks", "0", si.accent());
        LinearLayout observationsColumn = si.metric("Observations", "0", si.accent());
        LinearLayout capturesColumn = si.metric("Captures", "0", si.accentAlt());
        metrics.addView(networksColumn, si.weight());
        metrics.addView(observationsColumn, si.weight());
        metrics.addView(capturesColumn, si.weight(SiTokens.SPACE_S));
        evidence.addView(metrics, si.match(SiTokens.SPACE_L));
        networks = firstText(networksColumn);
        observations = firstText(observationsColumn);
        captures = firstText(capturesColumn);

        process = pipelineLine("PROCESS");
        sync = pipelineLine("SYNC");
        evidence.addView(process, si.match(SiTokens.SPACE_L));
        evidence.addView(sync, si.match(SiTokens.SPACE_S));
        return evidence;
    }

    private LinearLayout captureRow() {
        LinearLayout column = si.vertical();
        column.addView(si.sectionHeader("Capture"), si.match());
        LinearLayout quick = si.row();
        quick.addView(quickAction("Audio", "Record a voice segment", si.accentAlt(),
                () -> openTools(MainActivity.ACTION_AUDIO)), si.weight());
        quick.addView(quickAction("Photo", "Capture an analyzed frame", si.warn(),
                () -> openTools(MainActivity.ACTION_PHOTO)), si.weight(SiTokens.SPACE_S));
        quick.addView(quickAction("WiGLE", "Import a WiGLE database", si.ok(),
                () -> openTools(MainActivity.ACTION_WIGLE)), si.weight(SiTokens.SPACE_S));
        column.addView(quick, si.match(SiTokens.SPACE_S));
        return column;
    }

    /** Hands data or control to the app that owns it; never re-implements it here. */
    private LinearLayout appRail() {
        LinearLayout column = si.vertical();
        column.addView(si.sectionHeader("Open in"), si.match());
        LinearLayout rail = si.row();
        rail.addView(railButton("Quasar", "Browse corpus, maps, graphs", this::openQuasarDocuments), si.weight());
        rail.addView(railButton("Operator", "Mission control hub", this::openOperator), si.weight(SiTokens.SPACE_S));
        rail.addView(railButton("Settings", "Server and engines", () -> openTools(null)), si.weight(SiTokens.SPACE_S));
        column.addView(rail, si.match(SiTokens.SPACE_S));
        return column;
    }

    private Button quickAction(String title, String description, int accent, Runnable click) {
        Button view = si.secondaryButton(title, click);
        view.setContentDescription(description);
        view.setMinHeight(si.dp(68));
        view.setBackground(si.ripple(si.palette().raised, accent, SiTokens.RADIUS_CONTROL));
        return view;
    }

    private Button railButton(String title, String description, Runnable click) {
        Button view = si.secondaryButton(title, click);
        view.setContentDescription(description);
        return view;
    }

    private TextView textLink(String value, Runnable click) {
        TextView view = si.label(value, SiTokens.TYPE_LABEL, si.accent(), true);
        view.setPadding(si.dp(SiTokens.SPACE_M), si.dp(SiTokens.SPACE_S), 0, si.dp(SiTokens.SPACE_S));
        view.setMinHeight(si.dp(SiTokens.TOUCH_MIN_DP));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private TextView pipelineLine(String title) {
        TextView view = si.label(title, SiTokens.TYPE_LABEL, si.muted(), false);
        view.setPadding(si.dp(SiTokens.SPACE_M), si.dp(SiTokens.SPACE_M - 1), si.dp(SiTokens.SPACE_M), si.dp(SiTokens.SPACE_M - 1));
        view.setBackground(si.rounded(si.palette().raised, si.palette().border, SiTokens.RADIUS_PILL - 87));
        return view;
    }

    private LinearLayout stageRail() {
        LinearLayout rail = si.row();
        rail.addView(stage("1", "COLLECT", "Wi-Fi · GPS\nAudio · Photos", si.accent()), si.weight());
        rail.addView(stage("2", "PROCESS", "Transcribe · Extract\nDocuments", si.warn()), si.weight(SiTokens.SPACE_S));
        rail.addView(stage("3", "SYNC", "Queue · Upload\nto Star", si.ok()), si.weight(SiTokens.SPACE_S));
        return rail;
    }

    private LinearLayout stage(String number, String heading, String detail, int accent) {
        LinearLayout column = si.vertical();
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView marker = si.label(number, 13, si.onColor(accent), true);
        marker.setGravity(Gravity.CENTER);
        marker.setBackground(si.rounded(accent, accent, SiTokens.RADIUS_PILL));
        column.addView(marker, new LinearLayout.LayoutParams(si.dp(38), si.dp(38)));
        TextView title = si.label(heading, 11, si.text(), true);
        title.setLetterSpacing(0.12f);
        column.addView(title, si.match(SiTokens.SPACE_S));
        TextView body = si.label(detail, 10, si.muted(), false);
        body.setGravity(Gravity.CENTER);
        column.addView(body, si.match(SiTokens.SPACE_XS));
        return column;
    }

    private TextView firstText(LinearLayout metricColumn) {
        return (TextView) metricColumn.getChildAt(0);
    }

    // ---- mission control ----

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
            snapshot = CollectorMissionSnapshot.fromStore(active, audio, store);
        } finally {
            store.close();
        }
        status.setText(snapshot.headline());
        status.setTextColor(active ? si.ok() : si.accent());
        primaryLabel.setText(snapshot.primaryAction());
        int fill = active ? si.danger() : si.accent();
        primary.setBackground(si.ripple(fill, fill, SiTokens.RADIUS_CONTROL));
        primary.setTextColor(si.onColor(fill));
        networks.setText(compact(snapshot.networks));
        observations.setText(compact(snapshot.observations));
        captures.setText(compact(snapshot.captures));
        process.setText("PROCESS   " + snapshot.captures + " captures · " + snapshot.queued + " queued docs");
        sync.setText("SYNC   " + snapshot.accepted + " accepted · " + snapshot.queued + " awaiting upload");
        networkView.setSnapshot(snapshot);
        if (resumed) refreshHandler.postDelayed(this::refresh, 1_500L);
    }

    // ---- handoff ----

    private void openTools(String action) {
        Intent intent = new Intent(this, MainActivity.class);
        if (action != null) intent.putExtra(MainActivity.EXTRA_ACTION, action);
        startActivity(intent);
    }

    private void openQuasarDocuments() {
        Intent launch = new Intent(OperatorContracts.ACTION_OPEN_DOCUMENT)
                .setPackage(OperatorContracts.PACKAGE_QUASAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (resolve(launch) != null) {
            startActivity(launch);
            return;
        }
        Intent fallback = getPackageManager().getLaunchIntentForPackage(OperatorContracts.PACKAGE_QUASAR);
        if (fallback != null) {
            startActivity(fallback);
            return;
        }
        Toast.makeText(this, "Install the Quasar package to browse documents", Toast.LENGTH_SHORT).show();
    }

    private void openOperator() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(OperatorContracts.PACKAGE_OPERATOR);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        Toast.makeText(this, "Install the Operator hub for mission control", Toast.LENGTH_SHORT).show();
    }

    private android.content.pm.ResolveInfo resolve(Intent intent) {
        return getPackageManager().resolveActivity(intent, 0);
    }

    private static String compact(long value) {
        if (value >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", value / 1_000_000d);
        if (value >= 1_000) return String.format(java.util.Locale.US, "%.1fK", value / 1_000d);
        return Long.toString(value);
    }
}
