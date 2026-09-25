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
import android.os.IBinder;

import java.io.File;

public final class CollectorService extends Service {
    static final String PREFS = "collector_runtime_v2";
    static final String KEY_ACTIVE = "active";
    static final String KEY_STARTED_AT = "started_at_ms";
    static final String KEY_AUDIO_ACTIVE = "audio_active";
    static final String ACTION_START_AUDIO = "actor.starintel.collector.START_AUDIO";
    static final String ACTION_STOP_AUDIO = "actor.starintel.collector.STOP_AUDIO";

    private static final String CHANNEL = "star_wireless_collector";
    private static final int NOTIFICATION_ID = 4107;

    private StarWirelessStore store;
    private StarWirelessScanner scanner;
    private AudioSegmentRecorder audioRecorder;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startVisible(false);

        store = new StarWirelessStore(getApplicationContext());
        scanner = new StarWirelessScanner(getApplicationContext(), store);
        scanner.start();

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .apply();
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyStatus("Audio needs the microphone permission");
            return;
        }
        if (audioRecorder != null && audioRecorder.isRunning()) return;

        StarWirelessStore captureStore = store != null ? store : new StarWirelessStore(getApplicationContext());
        audioRecorder = new AudioSegmentRecorder(getFilesDir());
        audioRecorder.setListener(new AudioSegmentRecorder.Listener() {
            @Override
            public void onSegment(File wavFile, long durationMs, String sha256Hex) {
                captureStore.insertCapture(
                        "audio-" + wavFile.getName(),
                        "audio",
                        wavFile.getAbsolutePath(),
                        "audio/wav",
                        wavFile.length(),
                        sha256Hex,
                        System.currentTimeMillis(),
                        durationMs,
                        "pending_transcript");
            }

            @Override
            public void onError(String message) {
                notifyStatus("Audio error · " + message);
            }
        });
        // Mic foreground-service type must be active before capture starts.
        startVisible(true);
        audioRecorder.start();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_AUDIO_ACTIVE, true).apply();
        notifyStatus("Audio collection active");
    }

    private void stopAudio() {
        if (audioRecorder != null) {
            audioRecorder.stop();
            audioRecorder = null;
        }
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
