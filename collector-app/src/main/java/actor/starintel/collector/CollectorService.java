package actor.starintel.collector;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CollectorService extends Service {
    static final String PREFS = "collector_runtime_v2";
    static final String KEY_ACTIVE = "active";
    static final String KEY_STARTED_AT = "started_at_ms";
    static final String KEY_AUDIO_ACTIVE = "audio_active";
    static final String KEY_RUN_ID = "run_id";
    static final String ACTION_START_AUDIO = "actor.starintel.collector.START_AUDIO";
    static final String ACTION_STOP_AUDIO = "actor.starintel.collector.STOP_AUDIO";

    private static final String CHANNEL = "star_wireless_collector";
    private static final int NOTIFICATION_ID = 4107;

    private StarWirelessStore store;
    private StarWirelessScanner scanner;
    private AudioSegmentRecorder audioRecorder;
    private final AudioRecordingState audioState = new AudioRecordingState();
    private String runId;
    private final Handler syncHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startVisible(false);

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean restoreAudio = prefs.getBoolean(KEY_ACTIVE, false)
                && prefs.getBoolean(KEY_AUDIO_ACTIVE, false);
        runId = prefs.getString(KEY_RUN_ID, "");
        if (runId == null || runId.isEmpty() || !prefs.getBoolean(KEY_ACTIVE, false)) {
            runId = UUID.randomUUID().toString();
        }
        store = new StarWirelessStore(getApplicationContext());
        scanner = new StarWirelessScanner(getApplicationContext(), store, runId);
        scanner.start();

        android.content.SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putString(KEY_RUN_ID, runId);
        if (!prefs.getBoolean(KEY_ACTIVE, false)) {
            editor.putLong(KEY_STARTED_AT, System.currentTimeMillis());
        }
        editor.apply();
        syncHandler.post(syncLoop);
        if (restoreAudio) startAudio();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START_AUDIO.equals(action)) {
            startAudio();
        } else if (ACTION_STOP_AUDIO.equals(action)) {
            stopAudio();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAudio();
        if (scanner != null) scanner.stop();
        if (store != null) store.close();
        syncHandler.removeCallbacksAndMessages(null);

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .putBoolean(KEY_AUDIO_ACTIVE, false)
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAudio() {
        boolean permission = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!audioState.requestStart(permission)) {
            if (!permission) notifyStatus("Audio needs the microphone permission");
            return;
        }
        if (!permission) {
            notifyStatus("Audio needs the microphone permission");
            return;
        }

        StarWirelessStore captureStore = store != null ? store : new StarWirelessStore(getApplicationContext());
        audioRecorder = new AudioSegmentRecorder(getFilesDir());
        audioRecorder.setListener(new AudioSegmentRecorder.Listener() {
            @Override
            public void onSegment(File wavFile, long startedAtMs, long durationMs, String sha256Hex) {
                LocationSnapshot location = LocationSnapshot.bestEffort(getApplicationContext());
                String eventKey = "audio-" + wavFile.getName();
                captureStore.insertCapture(
                        eventKey,
                        "audio",
                        wavFile.getAbsolutePath(),
                        "audio/wav",
                        wavFile.length(),
                        sha256Hex,
                        startedAtMs,
                        durationMs,
                        "pending_transcript",
                        0,
                        0,
                        0,
                        location.latitude,
                        location.longitude,
                        location.altitude,
                        location.accuracy,
                        "pcm-s16le",
                        runId);
                StarWirelessStore.CaptureRow capture = captureStore.captureByEventKey(eventKey);
                if (capture != null) {
                    try {
                        DocumentProjection.projectMediaCapture(captureStore, capture);
                    } catch (RuntimeException failure) {
                        notifyStatus("Audio saved; document queue delayed");
                    }
                }
            }

            @Override
            public void onError(String message) {
                audioState.failed();
                notifyStatus("Audio error · " + message);
            }
        });
        // Mic foreground-service type must be active before capture starts.
        startVisible(true);
        audioRecorder.start();
        if (!audioRecorder.isRunning()) {
            audioState.failed();
            return;
        }
        audioState.started();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUDIO_ACTIVE, true).apply();
        notifyStatus("Audio collection active");
    }

    private final Runnable syncLoop =
            new Runnable() {
                @Override
                public void run() {
                    if (store != null && store.queuedCount() > 0 && syncRunning.compareAndSet(false, true)) {
                        new Thread(() -> {
                            try {
                                String serverUrl = getSharedPreferences(
                                        TranscriptionManager.CONFIG_PREFS, MODE_PRIVATE)
                                        .getString(StarDocumentSync.KEY_SERVER_URL, "");
                                String apiKey = new CollectorSecretStore(getApplicationContext())
                                        .read(StarDocumentSync.SLOT_API_KEY);
                                if (serverUrl != null && !serverUrl.trim().isEmpty() && apiKey != null) {
                                    StarDocumentSync.sync(store, serverUrl, apiKey);
                                }
                            } catch (RuntimeException ignored) {
                                // The durable queue remains retryable while offline.
                            } finally {
                                syncRunning.set(false);
                            }
                        }, "starintel-live-sync").start();
                    }
                    syncHandler.postDelayed(this, 30_000L);
                }
            };

    private void stopAudio() {
        audioState.requestStop();
        if (audioRecorder != null) {
            audioRecorder.stop();
            audioRecorder = null;
        }
        audioState.stopped();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUDIO_ACTIVE, false).apply();
        if (store != null) startVisible(false);
    }

    private void startVisible(boolean includeMic) {
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Star Wireless active")
                        .setContentText(includeMic
                                ? "Collecting user-authorized audio, Wi-Fi, and location observations"
                                : "Collecting user-authorized Wi-Fi and location observations")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setOngoing(true)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            if (includeMic
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyStatus(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Star Wireless")
                        .setContentText(message)
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .build();
        manager.notify(NOTIFICATION_ID + 1, notification);
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL,
                        "Star Wireless Collector",
                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible status for an active Star Wireless collection session");
        manager.createNotificationChannel(channel);
    }
}
