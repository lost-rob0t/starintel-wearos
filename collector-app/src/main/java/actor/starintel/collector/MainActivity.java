package actor.starintel.collector;

import actor.starintel.android.config.StarIntelSharedConfig;
import actor.starintel.design.Si;
import actor.starintel.design.SiTokens;
import actor.starintel.design.SiTokens.ThemeStore;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capture tools: grouped, single-purpose sections behind the Mission screen.
 *
 * Sections own exactly one concern (session, capture, pipeline, server, import) and
 * expose one primary action each. Entered via Mission quick actions; the EXTRA_ACTION
 * extra scrolls the relevant section to the top.
 */
public final class MainActivity extends Activity {
    static final String EXTRA_ACTION = "actor.starintel.collector.extra.ACTION";
    static final String ACTION_AUDIO = "audio";
    static final String ACTION_PHOTO = "photo";
    static final String ACTION_WIGLE = "wigle";

    private static final int REQUEST_PERMISSIONS = 7;
    private static final int REQUEST_WIGLE_DB = 9;
    private static final int REQUEST_AUDIO_PERMISSIONS = 11;
    private static final int REQUEST_CAPTURE_IMAGE = 13;

    private Si si;
    private LinearLayout pipelineSection;
    private LinearLayout serverSection;
    private LinearLayout importSection;
    private TextView pipeline;
    private TextView asrStatus;
    private TranscriptionManager transcription;
    private StarIntelSharedConfig sharedConfig;
    private final AtomicReference<File> pendingPhoto = new AtomicReference<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        si = new Si(this, new ThemeStore(this).current());
        sharedConfig = new StarIntelSharedConfig(this);
        transcription = new TranscriptionManager(this);
        getWindow().setStatusBarColor(si.background());
        getWindow().setNavigationBarColor(si.background());

        LinearLayout root = si.vertical();
        root.setPadding(si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_L),
                si.dp(SiTokens.SPACE_XL), si.dp(SiTokens.SPACE_XXL));
        root.setBackgroundColor(si.background());

        LinearLayout brand = si.row();
        LinearLayout titles = si.vertical();
        titles.addView(si.eyebrow("Star Wireless // Tools"));
        titles.addView(si.title("Capture Tools"), si.match(SiTokens.SPACE_XS));
        brand.addView(titles, si.weight());
        Button back = si.secondaryButton("Mission", this::finish);
        back.setContentDescription("Back to the mission screen");
        brand.addView(back, new LinearLayout.LayoutParams(si.dp(110), si.dp(SiTokens.TOUCH_MIN_DP)));
        root.addView(brand, si.match());

        pipelineSection = pipelineCard();
        root.addView(pipelineSection, si.match(SiTokens.SPACE_XL));
        serverSection = serverCard();
        root.addView(serverSection, si.match(SiTokens.SPACE_L));
        importSection = importCard();
        root.addView(importSection, si.match(SiTokens.SPACE_L));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        focusRequestedSection();
    }

    private void focusRequestedSection() {
        String action = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ACTION);
        LinearLayout target;
        if (ACTION_AUDIO.equals(action)) {
            target = pipelineSection;
            startAudioCapture();
        } else if (ACTION_PHOTO.equals(action)) {
            target = pipelineSection;
            startCapturePhoto();
        } else if (ACTION_WIGLE.equals(action)) {
            target = importSection;
            chooseWigleDatabase();
        } else {
            target = serverSection;
        }
        target.requestFocusFromTouch();
        target.scrollTo(0, 0);
    }

    // ---- pipeline: transcription, entities, upload ----

    private LinearLayout pipelineCard() {
        LinearLayout card = si.accentCard(si.accent());
        card.addView(si.sectionHeader("Pipeline"));
        card.addView(si.bodyStrong("Turn captures into pinned StarIntel documents."), si.match(SiTokens.SPACE_XS));

        asrStatus = si.statusPill(asrState(), "", si.accent());
        card.addView(asrStatus, si.match(SiTokens.SPACE_M));

        if (!transcription.deviceModelReady()) {
            Button model = si.primaryButton("Download voice model · 57 MB", this::downloadModel);
            model.setContentDescription("Download the on-device whisper model, SHA-256 pinned");
            card.addView(model, si.match(SiTokens.SPACE_M));
        }

        card.addView(row("Transcribe segments", this::runTranscription,
                "Transcribe pending audio with the on-device model or configured remote"), si.match(SiTokens.SPACE_S));
        card.addView(row("Extract entities", this::runHeuristicExtraction,
                "Queue candidate people, orgs, and relations from transcripts"), si.match(SiTokens.SPACE_S));
        card.addView(row("Interpret via agent", this::runAgentInterpretation,
                "Ask the server prolog-rlm agent to refine the latest transcript"), si.match(SiTokens.SPACE_S));
        card.addView(row("Sync to Star", this::runSync,
                "Upload queued documents to the StarIntel server"), si.match(SiTokens.SPACE_S));

        pipeline = si.label("", SiTokens.TYPE_LABEL, si.muted(), false);
        card.addView(pipeline, si.match(SiTokens.SPACE_M));
        return card;
    }

    private LinearLayout serverCard() {
        LinearLayout card = si.card();
        card.addView(si.sectionHeader("Server"));
        card.addView(si.bodyStrong("Star server and transcription endpoints live in the phone Keystore."), si.match(SiTokens.SPACE_XS));
        card.addView(row("Endpoints and keys", this::showSettings,
                "Server URL, API key, remote ASR endpoint, default dataset"), si.match(SiTokens.SPACE_M));
        return card;
    }

    private LinearLayout importCard() {
        LinearLayout card = si.card();
        card.addView(si.sectionHeader("Import"));
        card.addView(si.bodyStrong("WiGLE databases merge without flattening observation history."), si.match(SiTokens.SPACE_XS));
        card.addView(row("Import WiGLE database", this::chooseWigleDatabase,
                "Pick a WiGLE SQLite export from storage"), si.match(SiTokens.SPACE_M));
        card.addView(row("Wi-Fi scan throttling", this::openDeveloperOptions,
                "Developer options → Networking → turn throttling off for dense scans"), si.match(SiTokens.SPACE_S));
        return card;
    }

    private LinearLayout row(String label, Runnable action, String description) {
        LinearLayout rowLayout = si.row();
        Button button = si.secondaryButton(label, action);
        button.setContentDescription(description);
        rowLayout.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, si.dp(SiTokens.TOUCH_MIN_DP)));
        return rowLayout;
    }

    // ---- pipeline actions ----

    private String asrState() {
        if (transcription.deviceModelReady()) return "VOICE MODEL · READY";
        if (transcription.remoteConfigured()) return "TRANSCRIPTION · REMOTE";
        return "TRANSCRIPTION · NO ENGINE";
    }

    private void downloadModel() {
        setPipelineStatus("VOICE MODEL · DOWNLOADING");
        transcription.submit(() -> {
            String status;
            try {
                transcription.modelStore().download(
                        WhisperModelStore.DEFAULT_TAG,
                        WhisperModelStore.DEFAULT_URL,
                        WhisperModelStore.DEFAULT_SHA256,
                        WhisperModelStore.DEFAULT_SIZE_BYTES);
                status = "VOICE MODEL · READY";
            } catch (Exception failure) {
                status = "VOICE MODEL · FAILED · " + safe(failure.getMessage());
            }
            final String settled = status;
            runOnUiThread(() -> {
                asrStatus.setText(settled);
                refreshPipeline();
            });
        });
    }

    private void runTranscription() {
        setPipelineStatus("TRANSCRIBING · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                String status = transcription.transcribePending(store);
                runOnUiThread(() -> {
                    asrStatus.setText(status);
                    refreshPipeline();
                });
            } finally {
                store.close();
            }
        });
    }

    private void runHeuristicExtraction() {
        setPipelineStatus("ENTITY EXTRACTION · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                EntityExtraction.HeuristicResult result = EntityExtraction.extractHeuristic(store);
                if (!result.latestAnalysisDocId.isEmpty()) {
                    // Non-secret control-plane pointer so the Operator hub can hand the
                    // newest document to Quasar without owning the corpus itself.
                    new StarIntelSharedConfig(getApplicationContext()).put(
                            StarIntelSharedConfig.KEY_COLLECTOR_LATEST_DOC, result.latestAnalysisDocId);
                }
                String status = result.documents + " candidate docs from " + result.captures + " transcripts";
                runOnUiThread(() -> {
                    pipeline.setText(status);
                    refreshPipeline();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> setPipelineStatus("EXTRACTION FAILED · " + safe(failure.getMessage())));
            } finally {
                store.close();
            }
        });
    }

    private void runAgentInterpretation() {
        String serverUrl = serverUrl();
        String apiKey = new CollectorSecretStore(this).read(StarDocumentSync.SLOT_API_KEY);
        if (serverUrl.isEmpty() || apiKey == null) {
            Toast.makeText(this, "Configure the Star server first", Toast.LENGTH_SHORT).show();
            return;
        }
        setPipelineStatus("AGENT INTERPRETATION · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                List<StarWirelessStore.CaptureRow> transcribed = store.capturesInState("transcribed", 1);
                if (transcribed.isEmpty()) {
                    runOnUiThread(() -> setPipelineStatus("NO TRANSCRIBED CAPTURES"));
                    return;
                }
                String status = EntityExtraction.interpretWithAgent(store, transcribed.get(0), serverUrl, apiKey);
                runOnUiThread(() -> setPipelineStatus(status));
            } catch (RuntimeException failure) {
                runOnUiThread(() -> setPipelineStatus("AGENT UNAVAILABLE · " + safe(failure.getMessage())));
            } finally {
                store.close();
            }
        });
    }

    private void runSync() {
        String serverUrl = serverUrl();
        String apiKey = new CollectorSecretStore(this).read(StarDocumentSync.SLOT_API_KEY);
        if (serverUrl.isEmpty() || apiKey == null) {
            Toast.makeText(this, "Configure the Star server first", Toast.LENGTH_SHORT).show();
            return;
        }
        setPipelineStatus("STAR SYNC · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                String status = StarDocumentSync.sync(store, serverUrl, apiKey);
                runOnUiThread(() -> setPipelineStatus(status));
            } catch (RuntimeException failure) {
                runOnUiThread(() -> setPipelineStatus("SYNC FAILED · " + safe(failure.getMessage())));
            } finally {
                store.close();
            }
        });
    }

    private void setPipelineStatus(String status) {
        runOnUiThread(() -> {
            asrStatus.setText(status);
            refreshPipeline();
        });
    }

    private void refreshPipeline() {
        StarWirelessStore store = new StarWirelessStore(this);
        try {
            pipeline.setText("CAPTURES " + store.captureCount()
                    + " · QUEUED " + store.queuedCount()
                    + " · ACCEPTED " + store.acceptedCount());
        } finally {
            store.close();
        }
    }

    // ---- server settings ----

    private void showSettings() {
        LinearLayout panel = si.vertical();
        panel.setPadding(si.dp(SiTokens.SPACE_L), si.dp(SiTokens.SPACE_S), si.dp(SiTokens.SPACE_L), 0);
        CollectorSecretStore secrets = new CollectorSecretStore(this);

        server = field(serverUrl(), "Star server URL (https://…)");
        panel.addView(server);
        key = secretField(secrets.read(StarDocumentSync.SLOT_API_KEY), "Star API key (star_sk_v1_…)");
        panel.addView(key);
        asrUrl = field(remoteUrl(), "Remote ASR endpoint (https://host/v1/audio/transcriptions)");
        panel.addView(asrUrl);
        asrModel = field(remoteModel(), "Remote ASR model (e.g. whisper-1)");
        panel.addView(asrModel);
        asrKey = secretField(secrets.read(TranscriptionManager.SLOT_ASR_KEY), "Remote ASR API key");
        panel.addView(asrKey);
        dataset = field(sharedDataset(), "Default dataset for captures");
        panel.addView(dataset);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);

        new AlertDialog.Builder(this)
                .setTitle("Endpoints and keys")
                .setView(scroll)
                .setPositiveButton("Save", (dialog, which) -> {
                    getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(StarDocumentSync.KEY_SERVER_URL, text(server).replaceAll("/+$", ""))
                            .putString(TranscriptionManager.KEY_REMOTE_ASR_URL, text(asrUrl))
                            .putString(TranscriptionManager.KEY_REMOTE_ASR_MODEL, text(asrModel))
                            .putString(StarDocumentSync.KEY_DATASET, text(dataset))
                            .apply();
                    secrets.save(StarDocumentSync.SLOT_API_KEY, text(key));
                    secrets.save(TranscriptionManager.SLOT_ASR_KEY, text(asrKey));
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                    refreshPipeline();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText server, key, asrUrl, asrModel, asrKey, dataset;

    private EditText field(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setSingleLine(true);
        panel_last(edit);
        return edit;
    }

    private EditText secretField(String value, String hint) {
        EditText edit = field(value, hint);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return edit;
    }

    private void panel_last(EditText edit) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = si.dp(SiTokens.SPACE_S);
        edit.setLayoutParams(params);
    }

    private String serverUrl() {
        return getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                .getString(StarDocumentSync.KEY_SERVER_URL, "");
    }

    private String remoteUrl() {
        return getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                .getString(TranscriptionManager.KEY_REMOTE_ASR_URL, "");
    }

    private String remoteModel() {
        return getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                .getString(TranscriptionManager.KEY_REMOTE_ASR_MODEL, "");
    }

    private String sharedDataset() {
        String fromShared = sharedConfig.get(
                StarIntelSharedConfig.KEY_COLLECTOR_DEFAULT_DATASET, "");
        if (!fromShared.isEmpty()) return fromShared;
        return getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                .getString(StarDocumentSync.KEY_DATASET, "star-wireless-field");
    }

    private static String text(EditText field) {
        return field.getText() == null ? "" : field.getText().toString().trim();
    }

    // ---- import + camera ----

    private void chooseWigleDatabase() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_WIGLE_DB);
    }

    private void openDeveloperOptions() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_SETTINGS);
        }
        startActivity(intent);
    }

    void startCapturePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAPTURE_IMAGE);
            return;
        }
        File directory = new File(getFilesDir(), "photo-captures");
        File raw = new File(directory, "photo-" + System.currentTimeMillis() + "-raw.jpg");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Toast.makeText(this, "Could not create photo directory", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingPhoto.set(raw);
        Uri uri = FileProvider.getUriForFile(this, "actor.starintel.collector.files", raw);
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "No camera app available", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(intent, REQUEST_CAPTURE_IMAGE);
    }

    void startAudioCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSIONS);
            return;
        }
        Intent base = new Intent(this, CollectorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(base);
        else startService(base);
        startService(new Intent(this, CollectorService.class)
                .setAction(CollectorService.ACTION_START_AUDIO));
        Toast.makeText(this, "Audio capture active", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAPTURE_IMAGE) {
            File raw = pendingPhoto.getAndSet(null);
            if (resultCode != RESULT_OK || raw == null || !raw.isFile()) {
                if (raw != null) //noinspection ResultOfMethodCallIgnored
                    raw.delete();
                return;
            }
            ingestPhoto(raw);
            return;
        }
        if (requestCode != REQUEST_WIGLE_DB || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        setPipelineStatus("WIGLE IMPORT · RUNNING");
        new Thread(() -> {
            try {
                StarWirelessStore store = new StarWirelessStore(getApplicationContext());
                WigleSqliteImporter.ImportStats stats =
                        WigleSqliteImporter.importDatabase(getApplicationContext(), uri, store);
                store.close();
                runOnUiThread(() -> {
                    setPipelineStatus("IMPORTED " + stats.networks + " networks · "
                            + stats.observations + " observations · " + stats.routes + " route points");
                });
            } catch (Exception error) {
                runOnUiThread(() -> setPipelineStatus("WIGLE IMPORT FAILED · " + safe(error.getMessage())));
            }
        }, "star-wireless-wigle-import").start();
    }

    private void ingestPhoto(File raw) {
        setPipelineStatus("PHOTO · ANALYZING");
        transcription.submit(() -> {
            try {
                File normalized = new File(
                        new File(getFilesDir(), "photo-captures"),
                        raw.getName().replace("-raw.jpg", ".jpg"));
                ImageFeatures.Stats stats = OpenCvImageAnalyzer.normalizeAndAnalyze(raw, normalized);
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
                StarWirelessStore store = new StarWirelessStore(getApplicationContext());
                try {
                    store.insertCapture(
                            "photo-" + normalized.getName(),
                            "image",
                            normalized.getAbsolutePath(),
                            "image/jpeg",
                            normalized.length(),
                            AudioSegmentRecorder.sha256Hex(java.nio.file.Files.readAllBytes(normalized.toPath())),
                            System.currentTimeMillis(),
                            0L,
                            "captured");
                } finally {
                    store.close();
                }
                String status = String.format(java.util.Locale.US,
                        "PHOTO · %dx%d · luma %.2f · sharp %.3f · edges %.3f",
                        stats.width, stats.height, stats.meanLuma, stats.sharpness, stats.edgeDensity);
                runOnUiThread(() -> setPipelineStatus(status));
            } catch (Exception failure) {
                runOnUiThread(() -> setPipelineStatus("PHOTO FAILED · " + safe(failure.getMessage())));
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        if (requestCode == REQUEST_AUDIO_PERMISSIONS) startAudioCapture();
        if (requestCode == REQUEST_CAPTURE_IMAGE) startCapturePhoto();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPipeline();
        asrStatus.setText(asrState());
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown error";
        String trimmed = value.trim();
        return trimmed.length() <= 160 ? trimmed : trimmed.substring(0, 160);
    }
}
