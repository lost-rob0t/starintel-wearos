package actor.starintel.operator;

import actor.starintel.android.api.StarIntelClient;
import actor.starintel.android.model.StarSession;
import actor.starintel.android.config.OperatorContracts;
import actor.starintel.android.config.StarIntelSharedConfig;
import actor.starintel.design.Si;
import actor.starintel.design.SiTokens;
import actor.starintel.design.SiTokens.ThemeStore;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Operator: the mission-control hub.
 *
 * One screen answers "what is running, what do I have, where does this data open".
 * Every feature row hands the data to the app that owns it through the typed
 * OperatorContracts actions; the Operator never re-implements another app's feature.
 */
public final class MainActivity extends Activity {
    private Si si;
    private TextView stats;
    private final java.util.concurrent.ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        si = new Si(this, new ThemeStore(this).current());
        getWindow().setStatusBarColor(si.background());
        getWindow().setNavigationBarColor(si.background());

        LinearLayout root = si.vertical();
        root.setPadding(si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_L),
                si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_XXL));
        root.setBackgroundColor(si.background());

        root.addView(header(), si.match());

        for (OperatorCatalog.AppSection section : OperatorCatalog.sections()) {
            root.addView(sectionCard(section), si.match(SiTokens.SPACE_L));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStats();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private LinearLayout header() {
        LinearLayout header = si.accentCard(si.accent());
        header.addView(si.eyebrow("StarIntel // Operator"));
        header.addView(si.display("Mission Control"), si.match(SiTokens.SPACE_XS));
        header.addView(si.body(
                "Every surface in the fleet, with live corpus state. Rows hand data to the app that owns it."), si.match(SiTokens.SPACE_S));
        stats = si.statusPill("CORPUS", "loading", si.muted());
        header.addView(stats, si.match(SiTokens.SPACE_M));
        header.addView(si.secondaryButton("Server settings", this::showSettings), si.match(SiTokens.SPACE_S));
        return header;
    }

    private void showSettings() {
        LinearLayout panel = si.vertical();
        panel.setPadding(si.dp(SiTokens.SPACE_L), si.dp(SiTokens.SPACE_S), si.dp(SiTokens.SPACE_L), 0);
        StarIntelSharedConfig shared = new StarIntelSharedConfig(this);
        android.content.SharedPreferences prefs =
                getSharedPreferences("operator_config", MODE_PRIVATE);
        OperatorSecretStore secrets = new OperatorSecretStore(this);

        EditText server = new EditText(this);
        server.setHint("Star server URL (https://…)");
        server.setSingleLine(true);
        server.setText(prefs.getString("server_url", shared.get(StarIntelSharedConfig.KEY_SERVER_URL, "")));
        panel.addView(server);
        EditText key = new EditText(this);
        key.setHint("Star API key (star_sk_v1_…)");
        key.setSingleLine(true);
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(orEmpty(secrets.read("star_api_key")));
        panel.addView(key);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Star server")
                .setView(panel)
                .setPositiveButton("Save", (dialog, which) -> {
                    prefs.edit()
                            .putString("server_url", text(server).replaceAll("/+$", ""))
                            .apply();
                    secrets.save("star_api_key", text(key));
                    refreshStats();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    private LinearLayout sectionCard(OperatorCatalog.AppSection section) {
        boolean installed = launchIntent(section.packageName) != null;
        LinearLayout card = si.card();
        card.setContentDescription(section.name + (installed ? " installed" : " not installed"));

        LinearLayout headerRow = si.row();
        LinearLayout titles = si.vertical();
        titles.addView(si.title(section.name));
        titles.addView(si.body(section.role), si.match(SiTokens.SPACE_XS));
        headerRow.addView(titles, si.weight());
        TextView pill = si.statusPill(installed ? "READY" : "MISSING", "", installed ? si.ok() : si.danger());
        headerRow.addView(pill);
        card.addView(headerRow);

        if (!installed) {
            card.addView(si.emptyState(
                    section.name + " is not installed",
                    "Install it from the Companion package catalog; this row stays inert until then."),
                    si.match(SiTokens.SPACE_M));
            return card;
        }

        for (OperatorCatalog.Feature feature : section.features) {
            card.addView(featureRow(section, feature), si.match(SiTokens.SPACE_S));
        }
        return card;
    }

    private LinearLayout featureRow(OperatorCatalog.AppSection section, OperatorCatalog.Feature feature) {
        Button row = si.secondaryButton(feature.label, () -> fire(section, feature));
        row.setContentDescription(feature.description);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, si.dp(SiTokens.TOUCH_MIN_DP));
        params.topMargin = si.dp(SiTokens.SPACE_XS);
        LinearLayout rowLayout = si.row();
        rowLayout.addView(row, params);
        return rowLayout;
    }

    private void fire(OperatorCatalog.AppSection section, OperatorCatalog.Feature feature) {
        if (feature.needsGeoPayload) {
            handOff(withGeoPayload(feature), "Last known position");
            return;
        }
        if (feature.action == null) {
            handOff(launchIntent(section.packageName), section.name);
            return;
        }
        Intent intent = new Intent(feature.action)
                .setPackage(section.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (feature.collectKind != null) {
            intent.putExtra(OperatorContracts.EXTRA_COLLECT_KIND, feature.collectKind);
        }
        handOff(intent, feature.label);
    }

    private Intent withGeoPayload(OperatorCatalog.Feature feature) {
        Intent intent = new Intent(feature.action)
                .setPackage(feature.targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (feature.action.equals(OperatorContracts.ACTION_OPEN_DOCUMENT)) {
            String docId = new StarIntelSharedConfig(this)
                    .get(StarIntelSharedConfig.KEY_COLLECTOR_LATEST_DOC, "");
            intent.putExtra(OperatorContracts.EXTRA_DOCUMENT_ID, OperatorContracts.normalizeDocumentId(docId));
        }
        return intent;
    }

    private void handOff(Intent intent, String what) {
        if (intent == null) {
            Toast.makeText(this, what + " is not installed", Toast.LENGTH_SHORT).show();
            return;
        }
        if (resolveActivity(intent) == null) {
            Toast.makeText(this, what + " did not answer its contract action", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(intent);
    }

    private android.content.pm.ResolveInfo resolveActivity(Intent intent) {
        return getPackageManager().resolveActivity(intent, 0);
    }

    private Intent launchIntent(String packageName) {
        return getPackageManager().getLaunchIntentForPackage(packageName);
    }

    // ---- live corpus state ----

    private void refreshStats() {
        StarIntelSharedConfig shared = new StarIntelSharedConfig(this);
        String serverUrl = getSharedPreferences("operator_config", MODE_PRIVATE)
                .getString("server_url", shared.get(StarIntelSharedConfig.KEY_SERVER_URL, ""));
        String apiKey = new OperatorSecretStore(this).read("star_api_key");
        if (serverUrl.isEmpty() || apiKey == null) {
            stats.setText("CORPUS  ·  configure the Star server");
            return;
        }
        io.execute(() -> {
            String status;
            try {
                JSONObject response = new StarIntelClient(
                        () -> new StarSession(serverUrl, apiKey), "operator/0.3", false)
                        .stats();
                JSONObject data = response.optJSONObject("data");
                JSONObject documents = data == null ? null : data.optJSONObject("documents");
                long total = documents == null ? -1L : documents.optLong("total", -1L);
                status = total >= 0L
                        ? "CORPUS  ·  " + total + " documents"
                        : "CORPUS  ·  unreachable";
            } catch (RuntimeException failure) {
                status = "CORPUS  ·  " + safe(failure.getMessage());
            }
            String settled = status;
            runOnUiThread(() -> stats.setText(settled));
        });
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unreachable";
        return value.length() <= 40 ? value : value.substring(0, 40);
    }
}
