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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    static final String EXTRA_ACTION = "actor.starintel.collector.extra.ACTION";
    static final String ACTION_AUDIO = "audio";
    static final String ACTION_PHOTO = "photo";
    static final String ACTION_WIGLE = "wigle";
    private static final int REQUEST_PERMISSIONS = 7;
    private static final int REQUEST_WIGLE_DB = 9;
    private static final int REQUEST_AUDIO_PERMISSIONS = 11;
    private static final int REQUEST_CAPTURE_IMAGE = 13;

    private TextView runtime;
    private TextView history;
    private TextView pipeline;
    private TextView asrStatus;
    private StarIntelSharedConfig sharedConfig;
    private TranscriptionManager transcription;
    private final AtomicReference<File> pendingPhoto = new AtomicReference<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sharedConfig = new StarIntelSharedConfig(this);
        transcription = new TranscriptionManager(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(30));
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        root.setBackgroundColor(Color.rgb(4, 7, 9));

        TextView eyebrow = text("STARINTEL // STAR WIRELESS", 12, Color.rgb(85, 235, 255), true);
        eyebrow.setLetterSpacing(.12f);
        root.addView(eyebrow, matchWrap());

        root.addView(text("Collector", 32, Color.WHITE, true), matchWrap(3));
        root.addView(
                text(
                        "Visible collection sessions for Wi-Fi, location, audio, and photo captures. Audio transcribes on-device with whisper.cpp (pinned model) or the optional remote endpoint. Entities are heuristic candidates until confirmed.",
                        14,
                        Color.rgb(150, 166, 172),
                        false),
                matchWrap(8));

        runtime = text("", 15, Color.WHITE, true);
        root.addView(runtime, matchWrap(24));

        history = text("", 13, Color.rgb(150, 166, 172), false);
        root.addView(history, matchWrap(4));

        pipeline = text("", 13, Color.rgb(150, 166, 172), false);
        root.addView(pipeline, matchWrap(4));

        asrStatus = text("", 13, Color.rgb(85, 235, 255), false);
        root.addView(asrStatus, matchWrap(4));

        Button start = button("START STAR WIRELESS");
        start.setOnClickListener(v -> startCollector());
        root.addView(start, matchWrap(18));

        Button stop = button("STOP COLLECTION");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, CollectorService.class));
            refresh();
        });
        root.addView(stop, matchWrap(8));

        Button audio = button("START AUDIO CAPTURE");
        audio.setOnClickListener(v -> startAudio());
        root.addView(audio, matchWrap(18));

        Button audioStop = button("STOP AUDIO CAPTURE");
        audioStop.setOnClickListener(v -> {
            startService(new Intent(this, CollectorService.class)
                    .setAction(CollectorService.ACTION_STOP_AUDIO));
            refresh();
        });
        root.addView(audioStop, matchWrap(8));

        Button photo = button("CAPTURE PHOTO");
        photo.setOnClickListener(v -> capturePhoto());
        root.addView(photo, matchWrap(18));

        Button model = button("DOWNLOAD VOICE MODEL · 57 MB SHA-PINNED");
        model.setOnClickListener(v -> downloadModel());
        root.addView(model, matchWrap(8));

        Button transcribe = button("TRANSCRIBE PENDING SEGMENTS");
        transcribe.setOnClickListener(v -> runTranscription());
        root.addView(transcribe, matchWrap(8));

        Button extract = button("EXTRACT ENTITIES (ON-DEVICE HEURISTICS)");
        extract.setOnClickListener(v -> runHeuristicExtraction());
        root.addView(extract, matchWrap(8));

        Button interpret = button("INTERPRET LATEST TRANSCRIPT VIA AGENT");
        interpret.setOnClickListener(v -> runAgentInterpretation());
        root.addView(interpret, matchWrap(8));

        Button sync = button("SYNC DOCUMENTS TO STAR");
        sync.setOnClickListener(v -> runSync());
        root.addView(sync, matchWrap(8));

        Button configure = button("SERVER / TRANSCRIPTION SETTINGS");
        configure.setOnClickListener(v -> showSettings());
        root.addView(configure, matchWrap(8));

        Button dev = button("WI-FI SCAN THROTTLING SETTINGS");
        dev.setOnClickListener(v -> openDeveloperOptions());
        root.addView(dev, matchWrap(8));

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

        setContentView(scroll);
        root.post(this::runRequestedAction);
    }

    private void runRequestedAction() {
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        getIntent().removeExtra(EXTRA_ACTION);
        if (ACTION_AUDIO.equals(action)) startAudio();
        else if (ACTION_PHOTO.equals(action)) capturePhoto();
        else if (ACTION_WIGLE.equals(action)) chooseWigleDatabase();
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

    private void startAudio() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSIONS);
            return;
        }
        Intent base = new Intent(this, CollectorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(base);
        } else {
            startService(base);
        }
        startService(new Intent(this, CollectorService.class)
                .setAction(CollectorService.ACTION_START_AUDIO));
        refresh();
    }

    private void capturePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQUEST_CAPTURE_IMAGE);
            return;
        }
        File directory = new File(getFilesDir(), "photo-captures");
        File raw = new File(directory, "photo-" + System.currentTimeMillis() + "-raw.jpg");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            toast("Could not create photo directory");
            return;
        }
        pendingPhoto.set(raw);
        Uri uri = FileProvider.getUriForFile(this, "actor.starintel.collector.files", raw);
        Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (intent.resolveActivity(getPackageManager()) == null) {
            toast("No camera app available");
            return;
        }
        startActivityForResult(intent, REQUEST_CAPTURE_IMAGE);
    }

    private void downloadModel() {
        asrStatus.setText("VOICE MODEL · DOWNLOADING");
        transcription.submit(() -> {
            String result;
            try {
                WhisperModelStore store = transcription.modelStore();
                store.download(
                        WhisperModelStore.DEFAULT_TAG,
                        WhisperModelStore.DEFAULT_URL,
                        WhisperModelStore.DEFAULT_SHA256,
                        WhisperModelStore.DEFAULT_SIZE_BYTES);
                result = "VOICE MODEL · READY (on-device transcription)";
            } catch (Exception failure) {
                result = "VOICE MODEL · FAILED · " + safe(failure.getMessage());
            }
            final String status = result;
            runOnUiThread(() -> {
                asrStatus.setText(status);
                refresh();
            });
        });
    }

    private void runTranscription() {
        asrStatus.setText("TRANSCRIBING · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                String status = transcription.transcribePending(store);
                runOnUiThread(() -> {
                    asrStatus.setText(status);
                    refresh();
                });
            } finally {
                store.close();
            }
        });
    }

    private void runHeuristicExtraction() {
        asrStatus.setText("ENTITY EXTRACTION · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                EntityExtraction.HeuristicResult result = EntityExtraction.extractHeuristic(store);
                String status = "Extracted " + result.documents + " candidate documents from "
                        + result.captures + " transcripts";
                runOnUiThread(() -> {
                    asrStatus.setText(status);
                    refresh();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    asrStatus.setText("Entity extraction failed · " + safe(failure.getMessage()));
                    refresh();
                });
            } finally {
                store.close();
            }
        });
    }

    private void runAgentInterpretation() {
        String serverUrl = serverUrl();
        String apiKey = new CollectorSecretStore(this).read(StarDocumentSync.SLOT_API_KEY);
        if (serverUrl.isEmpty() || apiKey == null) {
            toast("Configure the Star server URL and API key first");
            return;
        }
        asrStatus.setText("AGENT INTERPRETATION · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                List<StarWirelessStore.CaptureRow> transcribed =
                        store.capturesInState("transcribed", 1);
                if (transcribed.isEmpty()) {
                    runOnUiThread(() -> asrStatus.setText("No transcribed captures to interpret"));
                    return;
                }
                String status = EntityExtraction.interpretWithAgent(
                        store, transcribed.get(0), serverUrl, apiKey);
                runOnUiThread(() -> {
                    asrStatus.setText(status);
                    refresh();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    asrStatus.setText("Agent interpretation unavailable · " + safe(failure.getMessage()));
                    refresh();
                });
            } finally {
                store.close();
            }
        });
    }

    private void runSync() {
        String serverUrl = serverUrl();
        String apiKey = new CollectorSecretStore(this).read(StarDocumentSync.SLOT_API_KEY);
        if (serverUrl.isEmpty() || apiKey == null) {
            toast("Configure the Star server URL and API key first");
            return;
        }
        asrStatus.setText("STAR SYNC · RUNNING");
        transcription.submit(() -> {
            StarWirelessStore store = new StarWirelessStore(getApplicationContext());
            try {
                String status = StarDocumentSync.sync(store, serverUrl, apiKey);
                runOnUiThread(() -> {
                    asrStatus.setText(status);
                    refresh();
                });
            } catch (RuntimeException failure) {
                runOnUiThread(() -> {
                    asrStatus.setText("Sync failed · " + safe(failure.getMessage()));
                    refresh();
                });
            } finally {
                store.close();
            }
        });
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), 0);

        CollectorSecretStore secrets = new CollectorSecretStore(this);

        final EditText server = field(serverUrl(), "Star server URL (https://...)");
        panel.addView(server);
        final EditText key = field(orNull(secrets.read(StarDocumentSync.SLOT_API_KEY)), "Star API key (star_sk_v1_...)");
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(key);
        final EditText asrUrl = field(remoteUrl(), "Remote ASR endpoint (https://host/v1/audio/transcriptions)");
        panel.addView(asrUrl);
        final EditText asrModel = field(remoteModel(), "Remote ASR model (e.g. whisper-1)");
        panel.addView(asrModel);
        final EditText asrKey = field(orNull(secrets.read(TranscriptionManager.SLOT_ASR_KEY)), "Remote ASR API key");
        asrKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        panel.addView(asrKey);
        final EditText dataset = field(sharedDataset(), "Default dataset for captures");
        panel.addView(dataset);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Star settings")
                .setView(panel)
                .setPositiveButton("Save", (dialog, which) -> {
                    String normalizedServer = text(server).replaceAll("/+$", "");
                    getSharedPreferences(TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(StarDocumentSync.KEY_SERVER_URL, normalizedServer)
                            .putString(TranscriptionManager.KEY_REMOTE_ASR_URL, text(asrUrl))
                            .putString(TranscriptionManager.KEY_REMOTE_ASR_MODEL, text(asrModel))
                            .putString(StarDocumentSync.KEY_DATASET, text(dataset))
                            .apply();
                    secrets.save(StarDocumentSync.SLOT_API_KEY, text(key));
                    secrets.save(TranscriptionManager.SLOT_ASR_KEY, text(asrKey));
                    toast("Settings saved");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    private static String orNull(String value) {
        return value == null ? "" : value;
    }

    private EditText field(String value, String hint) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setHint(hint);
        edit.setSingleLine(true);
        return edit;
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

    private void ingestPhoto(File raw) {
        pipeline.setText("PHOTO · ANALYZING");
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
                runOnUiThread(() -> {
                    pipeline.setText(status);
                    refresh();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    pipeline.setText("PHOTO · FAILED · " + safe(failure.getMessage()));
                    refresh();
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) return;
        if (requestCode == REQUEST_AUDIO_PERMISSIONS) startAudio();
        if (requestCode == REQUEST_CAPTURE_IMAGE) capturePhoto();
    }

    private void refresh() {
        boolean active =
                getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                        .getBoolean(CollectorService.KEY_ACTIVE, false);
        boolean audioActive =
                getSharedPreferences(CollectorService.PREFS, MODE_PRIVATE)
                        .getBoolean(CollectorService.KEY_AUDIO_ACTIVE, false);
        runtime.setText(active
                ? (audioActive ? "SESSION · ACTIVE + AUDIO" : "SESSION · ACTIVE")
                : "SESSION · STOPPED");

        StarWirelessStore store = new StarWirelessStore(this);
        try {
            history.setText(
                    "NETWORKS · "
                            + store.networkCount()
                            + "    OBSERVATIONS · "
                            + store.observationCount()
                            + "    ROUTE POINTS · "
                            + store.routeCount());
            pipeline.setText(
                    "CAPTURES · "
                            + store.captureCount()
                            + "    QUEUED DOCS · "
                            + store.queuedCount()
                            + "    ACCEPTED · "
                            + store.acceptedCount());
        } finally {
            store.close();
        }

        boolean device = transcription.deviceModelReady();
        boolean remote = transcription.remoteConfigured();
        asrStatus.setText(device
                ? "VOICE MODEL · READY (on-device)"
                : remote
                        ? "TRANSCRIPTION · remote endpoint configured"
                        : "TRANSCRIPTION · download the device model or configure a remote endpoint");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
