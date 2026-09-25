package actor.starintel.collector;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;

/**
 * Coordinates transcription of captured audio.
 *
 * Engine priority: the on-device whisper.cpp model when installed, otherwise the
 * optional remote OpenAI-compatible endpoint when configured. Without either, the
 * manager reports a precise unavailable status instead of pretending to transcribe.
 */
public final class TranscriptionManager {
    public static final String CONFIG_PREFS = "star_wireless_config";
    public static final String KEY_REMOTE_ASR_URL = "remote_asr_url";
    public static final String KEY_REMOTE_ASR_MODEL = "remote_asr_model";
    public static final String SLOT_ASR_KEY = "remote_asr_key";
    static final long MAX_WAV_BYTES = 32L * 1_024 * 1_024;

    private final Context context;
    private final WhisperModelStore modelStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "star-wireless-transcribe");
        thread.setPriority(Thread.MIN_PRIORITY + 1);
        return thread;
    });

    public TranscriptionManager(Context context) {
        this.context = context.getApplicationContext();
        this.modelStore = new WhisperModelStore(this.context.getFilesDir());
    }

    public WhisperModelStore modelStore() {
        return modelStore;
    }

    public boolean deviceModelReady() {
        return modelStore.isDownloaded(WhisperModelStore.DEFAULT_TAG)
                && WhisperBridge.libraryAvailable();
    }

    public boolean remoteConfigured() {
        return remoteUrl().startsWith("https://")
                && !remoteModel().isEmpty()
                && new CollectorSecretStore(context).read(SLOT_ASR_KEY) != null;
    }

    /** Processes pending captures; returns a short user-facing status line. */
    public String transcribePending(StarWirelessStore store) {
        List<StarWirelessStore.CaptureRow> pending = store.capturesInState("pending_transcript", 64);
        if (pending.isEmpty()) return "No pending audio segments";

        boolean device = deviceModelReady();
        boolean remote = remoteConfigured();
        if (!device && !remote) {
            return "No transcription engine: download the device model or configure a remote endpoint";
        }

        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        for (StarWirelessStore.CaptureRow capture : pending) {
            File wav = new File(capture.filePath);
            if (!wav.isFile() || wav.length() > MAX_WAV_BYTES) {
                store.updateCaptureTranscript(capture.id, "", "unavailable", "transcript_failed");
                failed.incrementAndGet();
                continue;
            }
            try {
                TranscriptModels.Transcript transcript = device
                        ? transcribeOnDevice(store, capture)
                        : transcribeRemote(store, capture);
                store.updateCaptureTranscript(
                        capture.id, TranscriptModels.toJson(transcript).toString(),
                        transcript.engine, "transcribed");
                done.incrementAndGet();
            } catch (Exception failure) {
                store.updateCaptureTranscript(capture.id, "", "unavailable", "transcript_failed");
                failed.incrementAndGet();
            }
        }
        return "Transcribed " + done.get() + " segments"
                + (failed.get() > 0 ? " · " + failed.get() + " failed" : "");
    }

    public java.util.concurrent.Future<?> submit(Runnable work) {
        return executor.submit(work);
    }

    private TranscriptModels.Transcript transcribeOnDevice(StarWirelessStore store, StarWirelessStore.CaptureRow capture) {
        File model = modelStore.modelFile(WhisperModelStore.DEFAULT_TAG);
        String raw = WhisperBridge.transcribe(
                model.getAbsolutePath(), capture.filePath,
                Runtime.getRuntime().availableProcessors() > 1
                        ? Runtime.getRuntime().availableProcessors() / 2 : 1,
                "en");
        JSONObject parsed;
        try {
            parsed = new JSONObject(raw);
        } catch (Exception bad) {
            throw new IllegalStateException("malformed transcript response");
        }
        if (!parsed.optBoolean("ok", false)) {
            throw new IllegalStateException(parsed.optString("error", "on-device transcription failed"));
        }
        TranscriptModels.Transcript transcript;
        try {
            transcript = TranscriptModels.fromJson(raw);
        } catch (org.json.JSONException bad) {
            throw new IllegalStateException("malformed transcript segments");
        }
        // Normalize the engine label to the pinned model tag so provenance stays stable.
        return new TranscriptModels.Transcript(
                "whisper.cpp:" + WhisperModelStore.DEFAULT_TAG,
                transcript.language, transcript.segments);
    }

    private TranscriptModels.Transcript transcribeRemote(StarWirelessStore store, StarWirelessStore.CaptureRow capture)
            throws IOException {
        String key = new CollectorSecretStore(context).read(SLOT_ASR_KEY);
        String body = RemoteTranscriber.transcribe(
                remoteUrl(), key, remoteModel(), new File(capture.filePath),
                "audio/wav", "segment-" + capture.eventKey + ".wav");
        JSONObject parsed;
        try {
            parsed = new JSONObject(body);
        } catch (Exception bad) {
            throw new IOException("Remote transcription returned malformed JSON");
        }
        String text = parsed.optString("text", "").trim();
        if (text.isEmpty()) throw new IOException("Remote transcription returned no text");
        TranscriptModels.Transcript transcript = new TranscriptModels.Transcript(
                "remote:" + remoteModel(),
                "auto",
                java.util.Collections.singletonList(
                        new TranscriptModels.Segment(0.0d, capture.durationMs / 1000.0d, text)));
        return transcript;
    }

    private String remoteUrl() {
        return prefs().getString(KEY_REMOTE_ASR_URL, "").trim();
    }

    private String remoteModel() {
        return prefs().getString(KEY_REMOTE_ASR_MODEL, "").trim();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
    }
}
