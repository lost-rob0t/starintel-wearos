package actor.starintel.collector;

import actor.starintel.android.config.OperatorContracts;
import actor.starintel.android.lisp.EdgeRuntimeSnapshot;
import actor.starintel.android.lisp.ManagedEdgeRuntime;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private TextView localCoreStatus;
    private TextView localCoreDetail;
    private MissionNetworkView networkView;
    private ManagedEdgeRuntime edgeRuntime;
    private final ExecutorService runtimeIo = Executors.newSingleThreadExecutor();
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
                "One visible session captures Wi-Fi, Bluetooth, location, audio, pictures, and video, then queues canonical StarIntel documents."), si.match(SiTokens.SPACE_S));
        networkView = new MissionNetworkView(this, si.palette());
        mission.addView(networkView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, si.dp(164)));
        primary = si.primaryButton("START MISSION", this::toggleMission);
        primary.setContentDescription("Start or stop the collection mission");
        primaryLabel = (TextView) primary;
        mission.addView(primary, si.match(SiTokens.SPACE_L));
        mission.addView(stageRail(), si.match(SiTokens.SPACE_XL));
        root.addView(mission, si.match(SiTokens.SPACE_XXL));

        root.addView(captureRow(), si.match(SiTokens.SPACE_XL));
        root.addView(localCoreCard(), si.match(SiTokens.SPACE_L));
        root.addView(evidenceCard(), si.match(SiTokens.SPACE_L));
        root.addView(appRail(), si.match(SiTokens.SPACE_L));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        Si.install(this, scroll);
        probeLocalCore();
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

    @Override
    protected void onDestroy() {
        runtimeIo.shutdownNow();
        if (edgeRuntime != null) edgeRuntime.close();
        super.onDestroy();
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

    private LinearLayout localCoreCard() {
        LinearLayout card = si.card(si.accentAlt());
        LinearLayout header = si.row();
        header.addView(si.sectionHeader("Local core"), si.weight());
        localCoreStatus = si.label("BOOTING", SiTokens.TYPE_LABEL, si.warn(), true);
        localCoreStatus.setLetterSpacing(0.1f);
        header.addView(localCoreStatus);
        card.addView(header);
        card.addView(si.title("Common Lisp actor runtime"), si.match(SiTokens.SPACE_S));
        localCoreDetail = si.body("Loading the pinned StarIntel Edge image and trusted Sento actors.");
        card.addView(localCoreDetail, si.match(SiTokens.SPACE_XS));
        return card;
    }

    private LinearLayout captureRow() {
        LinearLayout column = si.vertical();
        column.addView(si.sectionHeader("Capture"), si.match());
        LinearLayout first = si.row();
        first.addView(quickAction("Audio", "Voice + transcript", si.accentAlt(),
                () -> openTools(MainActivity.ACTION_AUDIO)), si.weight());
        first.addView(quickAction("Photo", "Frame + features", si.warn(),
                () -> openTools(MainActivity.ACTION_PHOTO)), si.weight(SiTokens.SPACE_S));
        column.addView(first, si.match(SiTokens.SPACE_S));
        LinearLayout second = si.row();
        second.addView(quickAction("Video", "Media + metadata", si.accent(),
                () -> openTools(MainActivity.ACTION_VIDEO)), si.weight());
        second.addView(quickAction("WiGLE", "Bulk observations", si.ok(),
                () -> openTools(MainActivity.ACTION_WIGLE)), si.weight(SiTokens.SPACE_S));
        column.addView(second, si.match(SiTokens.SPACE_S));
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

    private View quickAction(String title, String description, int accent, Runnable click) {
        LinearLayout tile = si.vertical();
        tile.setPadding(si.dp(SiTokens.SPACE_L), si.dp(SiTokens.SPACE_L),
                si.dp(SiTokens.SPACE_L), si.dp(SiTokens.SPACE_L));
        tile.setMinimumHeight(si.dp(92));
        tile.setBackground(si.ripple(si.palette().raised, accent, SiTokens.RADIUS_CONTROL));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(title + ". " + description);
        tile.setOnClickListener(ignored -> click.run());
        tile.addView(si.label(title, SiTokens.TYPE_HEADLINE, si.text(), true));
        tile.addView(si.label(description, SiTokens.TYPE_LABEL, si.muted(), false),
                si.match(SiTokens.SPACE_XS));
        return tile;
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

    private void probeLocalCore() {
        runtimeIo.execute(() -> {
            EdgeRuntimeSnapshot snapshot;
            try {
                edgeRuntime = new ManagedEdgeRuntime(getApplicationContext());
                snapshot = edgeRuntime.snapshot();
            } catch (RuntimeException failure) {
                snapshot = new EdgeRuntimeSnapshot(false, "ECL", safe(failure.getMessage()), java.util.Collections.emptyList());
            }
            EdgeRuntimeSnapshot settled = snapshot;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (settled.getAvailable()) {
                    int count = settled.getActors().size();
                    localCoreStatus.setText("ECL + SENTO · READY");
                    localCoreStatus.setTextColor(si.ok());
                    localCoreDetail.setText(count + (count == 1 ? " trusted actor" : " trusted actors")
                            + " available on-device. Requests stay inside the closed Edge API.");
                } else {
                    localCoreStatus.setText("EDGE RUNTIME · UNAVAILABLE");
                    localCoreStatus.setTextColor(si.warn());
                    localCoreDetail.setText(settled.getDetail());
                }
            });
        });
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
                && (Build.VERSION.SDK_INT < 31
                    || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                && (Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
            };
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
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

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "Runtime unavailable";
        return value.length() <= 220 ? value : value.substring(0, 220);
    }
}
