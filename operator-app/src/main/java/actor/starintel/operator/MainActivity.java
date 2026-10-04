package actor.starintel.operator;

import actor.starintel.android.api.StarIntelClient;
import actor.starintel.android.api.StarHttpFailure;
import actor.starintel.android.model.StarSession;
import actor.starintel.android.model.RemoteActor;
import actor.starintel.android.config.OperatorContracts;
import actor.starintel.android.config.StarIntelSharedConfig;
import actor.starintel.design.Si;
import actor.starintel.design.SiTokens;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native fleet hub. Every durable operation is authorized by Star server. */
public final class MainActivity extends Activity {
    private Si si;
    private FrameLayout viewport;
    private LinearLayout navigation;
    private String selected = "Overview";
    private int generation;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        si = new Si(this, new SiTokens.ThemeStore(this).current());
        getWindow().setStatusBarColor(si.background());
        getWindow().setNavigationBarColor(si.background());
        LinearLayout shell = si.vertical();
        shell.setBackgroundColor(si.background());
        viewport = new FrameLayout(this);
        shell.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1f));
        navigation = si.row();
        navigation.setPadding(si.dp(8), si.dp(8), si.dp(8), si.dp(8));
        navigation.setBackgroundColor(si.surface());
        for (String tab : new String[]{"Overview", "Fleet", "Actors", "Settings"}) {
            navigation.addView(si.secondaryButton(tab, () -> show(tab)), si.weight(4));
        }
        shell.addView(navigation, si.match());
        Si.install(this, shell);
        if (state != null) selected = state.getString("tab", "Overview");
    }

    @Override protected void onResume() { super.onResume(); show(selected); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", selected); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { generation++; io.shutdownNow(); super.onDestroy(); }

    private void show(String tab) {
        selected = tab; generation++;
        for (int index = 0; index < navigation.getChildCount(); index++) {
            TextView button = (TextView) navigation.getChildAt(index);
            button.setTextColor(button.getText().toString().equals(tab) ? si.accent() : si.muted());
            button.setSelected(button.getText().toString().equals(tab));
        }
        LinearLayout body = si.vertical();
        body.setPadding(si.dp(20), si.dp(24), si.dp(20), si.dp(32));
        String title = tab.equals("Overview") ? "Mission control" : tab;
        body.addView(si.header("StarIntel // Operator", title, description(tab)), si.match());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); scroll.addView(body);
        viewport.removeAllViews(); viewport.addView(scroll);
        switch (tab) {
            case "Fleet": for (OperatorCatalog.AppSection section : OperatorCatalog.sections())
                body.addView(sectionCard(section), si.match(16)); break;
            case "Actors": actors(body); break;
            case "Settings": settings(body); break;
            default: overview(body);
        }
    }

    private String description(String tab) {
        switch (tab) {
            case "Fleet": return "Open the surface that owns each mission step.";
            case "Actors": return "Live server deployments. Private implementations and credentials stay on the server.";
            case "Settings": return "Connect this app with your own account. Credentials stay encrypted on this device.";
            default: return "Corpus state, field tools, and the private actor fleet.";
        }
    }

    private void overview(LinearLayout body) {
        LinearLayout summary = si.accentCard(si.accent());
        TextView corpus = si.statusPill("CORPUS", configured() ? "loading" : "connect server", si.accent());
        summary.addView(corpus, si.match());
        LinearLayout metrics = si.row();
        int installed = 0;
        for (OperatorCatalog.AppSection section : OperatorCatalog.sections()) if (launchIntent(section.packageName) != null) installed++;
        metrics.addView(si.metric("Surfaces ready", Integer.toString(installed), si.accent()), si.weight());
        metrics.addView(si.metric("Connection", configured() ? "Saved" : "None", si.warn()), si.weight());
        summary.addView(metrics, si.match(16));
        summary.addView(si.primaryButton(configured() ? "Refresh corpus" : "Connect server", () -> show(configured() ? "Overview" : "Settings")), si.match(16));
        body.addView(summary, si.match(20));
        body.addView(si.sectionHeader("Mission shortcuts"), si.match(24));
        body.addView(si.secondaryButton("Browse field tools", () -> show("Fleet")), si.match(12));
        body.addView(si.secondaryButton("Inspect server actors", () -> show("Actors")), si.match(8));
        body.addView(si.secondaryButton("Install or update surfaces", () -> handOff(launchIntent(OperatorContracts.PACKAGE_COMPANION), "Companion")), si.match(8));
        if (configured()) request(() -> client().stats(), response -> {
            JSONObject data = response.optJSONObject("data");
            JSONObject documents = data == null ? null : data.optJSONObject("documents");
            long total = documents == null ? -1 : documents.optLong("total", -1);
            corpus.setText(total >= 0 ? "CORPUS · " + total + " documents" : "CORPUS · no count returned");
        }, corpus);
    }

    private void actors(LinearLayout body) {
        TextView status = si.body(configured() ? "Loading authorized registry…" : "Connect a server in Settings to inspect actors.");
        body.addView(status, si.match(16));
        body.addView(si.secondaryButton("Refresh actors", () -> show("Actors")), si.match(8));
        if (!configured()) { body.addView(si.primaryButton("Connect server", () -> show("Settings")), si.match(8)); return; }
        request(() -> new JSONObject().put("actors", client().actors()), response -> {
            JSONArray rows = response.getJSONArray("actors"); int count = 0;
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.optJSONObject(index);
                RemoteActor actor = row == null ? null : RemoteActor.fromJson(row);
                if (actor == null) continue;
                count++;
                LinearLayout card = si.card();
                card.addView(si.title(actor.getName()));
                card.addView(si.statusPill(actor.getStatus(), actor.getReady() ? "ready" : "unavailable", actor.getReady() ? si.ok() : si.warn()), si.match(8));
                card.addView(si.body(actor.getUri()), si.match(8));
                card.addView(si.body("Targets · " + String.join(", ", actor.getAccepts())), si.match(8));
                body.addView(card, si.match(12));
            }
            status.setText(count == 0 ? "No operator-visible deployments returned." : count + " visible deployments. Dispatch authority is checked by the server.");
            body.addView(si.secondaryButton("Dispatch in Quasar", () -> handOff(launchIntent(OperatorContracts.PACKAGE_QUASAR), "Quasar")), si.match(12));
        }, status);
    }

    private void settings(LinearLayout body) {
        LinearLayout card = si.card();
        EditText server = si.field("https://your-star-server", false); server.setText(serverUrl());
        server.setContentDescription("Star server URL");
        EditText username = si.field("Username", false);
        EditText password = si.field("Password", true);
        EditText key = si.field("star_sk_v1_…", true);
        key.setContentDescription("API key");
        TextView status = si.body(configured() ? "Encrypted credential saved. Connect to validate it." : "No credential saved.");
        card.addView(si.sectionHeader("Server origin")); card.addView(server, si.match(8));
        card.addView(si.sectionHeader("Account login"), si.match(20));
        card.addView(username, si.match(8)); card.addView(password, si.match(8));
        card.addView(si.primaryButton("Sign in", () -> {
            String origin = text(server), name = text(username), presented = password.getText().toString();
            request(() -> {
                StarIntelClient api = client();
                String minted = api.login(origin, name, presented).getApiKey();
                api.authContext(origin, minted);
                return new JSONObject().put("origin", origin).put("key", minted);
            }, response -> { save(response.getString("origin"), response.getString("key")); password.getText().clear(); status.setText("Connected · account validated"); }, status);
        }), si.match(12));
        card.addView(si.sectionHeader("Or use an API key"), si.match(24)); card.addView(key, si.match(8));
        card.addView(si.secondaryButton("Authenticate key", () -> {
            String origin = text(server), presented = text(key);
            request(() -> { client().authContext(origin, presented); return new JSONObject(); }, response -> {
                save(origin, presented); key.getText().clear(); status.setText("Connected · key validated");
            }, status);
        }), si.match(8));
        card.addView(status, si.match(16)); body.addView(card, si.match(20));
    }

    private void save(String origin, String key) {
        // A Keystore failure must not switch the origin beneath the previous credential.
        String normalized = origin.trim().replaceAll("/+$", "");
        new OperatorSecretStore(this).saveConnection(normalized, key);
        getSharedPreferences("operator_config", MODE_PRIVATE).edit().putString("server_url", normalized).apply();
        new StarIntelSharedConfig(this).put(StarIntelSharedConfig.KEY_SERVER_URL, normalized);
    }
    private String serverUrl() {
        String boundOrigin = new OperatorSecretStore(this).connectionOrigin();
        if (boundOrigin != null) return boundOrigin;
        return getSharedPreferences("operator_config", MODE_PRIVATE).getString("server_url",
                new StarIntelSharedConfig(this).get(StarIntelSharedConfig.KEY_SERVER_URL, ""));
    }
    private boolean configured() { return !serverUrl().isEmpty() && new OperatorSecretStore(this).read("star_api_key") != null; }
    private StarIntelClient client() {
        return new StarIntelClient(() -> new StarSession(serverUrl(), orEmpty(new OperatorSecretStore(this).read("star_api_key"))), BuildConfig.VERSION_NAME, BuildConfig.DEBUG);
    }
    private static String orEmpty(String value) { return value == null ? "" : value; }
    private static String text(EditText field) { return field.getText().toString().trim(); }

    private LinearLayout sectionCard(OperatorCatalog.AppSection section) {
        boolean installed = launchIntent(section.packageName) != null;
        LinearLayout card = si.card();
        card.addView(si.title(section.name)); card.addView(si.body(section.role), si.match(4));
        card.addView(si.statusPill(installed ? "READY" : "MISSING", "", installed ? si.ok() : si.warn()), si.match(8));
        if (!installed) {
            card.addView(si.secondaryButton("Install with Companion", () -> handOff(launchIntent(OperatorContracts.PACKAGE_COMPANION), "Companion")), si.match(12));
        } else for (OperatorCatalog.Feature feature : section.features) {
            card.addView(si.secondaryButton(feature.label, () -> fire(section, feature)), si.match(8));
        }
        return card;
    }
    private void fire(OperatorCatalog.AppSection section, OperatorCatalog.Feature feature) {
        if (feature.action == null) { handOff(launchIntent(section.packageName), section.name); return; }
        Intent intent = new Intent(feature.action).setPackage(feature.targetPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (feature.action.equals(OperatorContracts.ACTION_OPEN_DOCUMENT)) {
            String id = OperatorContracts.normalizeDocumentId(new StarIntelSharedConfig(this).get(StarIntelSharedConfig.KEY_COLLECTOR_LATEST_DOC, ""));
            if (id.isEmpty()) { Toast.makeText(this, "Capture a document first", Toast.LENGTH_SHORT).show(); return; }
            intent.putExtra(OperatorContracts.EXTRA_DOCUMENT_ID, id);
        }
        if (feature.collectKind != null) intent.putExtra(OperatorContracts.EXTRA_COLLECT_KIND, feature.collectKind);
        handOff(intent, feature.label);
    }
    private Intent launchIntent(String name) { return getPackageManager().getLaunchIntentForPackage(name); }
    private void handOff(Intent intent, String label) {
        if (intent == null || getPackageManager().resolveActivity(intent, 0) == null) {
            Toast.makeText(this, label + " is unavailable", Toast.LENGTH_LONG).show(); return;
        }
        try { startActivity(intent); } catch (RuntimeException failure) { Toast.makeText(this, label + " could not open", Toast.LENGTH_LONG).show(); }
    }
    interface Operation { JSONObject run() throws Exception; }
    interface Result { void accept(JSONObject response) throws Exception; }
    private void request(Operation operation, Result result, TextView status) {
        int requestedGeneration = generation;
        status.setText("Connecting…");
        io.execute(() -> {
            JSONObject response = null; String error = null;
            try { response = operation.run(); }
            catch (StarHttpFailure failure) { error = "Server returned HTTP " + failure.getStatus() + ". Check account permissions and deployment capabilities."; }
            catch (Exception failure) { error = "Connection failed. Check the server origin and credentials, then retry."; }
            JSONObject settled = response; String failure = error;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing() || requestedGeneration != generation) return;
                if (failure != null) { status.setText(failure); return; }
                try { result.accept(settled); } catch (Exception invalid) { status.setText("Could not apply the response. Retry without changing your saved connection."); }
            });
        });
    }
}
