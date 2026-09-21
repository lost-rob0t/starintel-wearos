package actor.starintel.hackmode;

import actor.starintel.android.config.StarIntelSharedConfig;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

public final class HackmodeActorService extends Service {
    public static final String ACTION = "actor.starintel.action.HACKMODE_OPERATION";
    public static final String EXTRA_OPERATION_JSON = "actor.starintel.extra.HACKMODE_OPERATION_JSON";

    private static final String CHANNEL = "hackmode_actor_service";
    private static final int NOTIFICATION_ID = 4611;
    private static final int MAX_OPERATION_CHARS = 256 * 1024;

    private static final Set<String> ALLOWED_KINDS =
            new HashSet<>(
                    Arrays.asList(
                            "collector.observation",
                            "collector.observation_batch",
                            "collector.rules_evaluate",
                            "staratak.selection",
                            "prolog.mapping",
                            "media.transcription",
                            "media.sound_classification"));

    private StarIntelSharedConfig config;

    @Override
    public void onCreate() {
        super.onCreate();
        config = new StarIntelSharedConfig(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startVisible("Validating typed operation");
        if (intent == null || !ACTION.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String raw = intent.getStringExtra(EXTRA_OPERATION_JSON);
        try {
            String canonical = validate(raw).toString();
            String command = config.get(StarIntelSharedConfig.KEY_HACKMODE_COMMAND, "hm");
            updateNotification("Dispatching to Hackmode");
            TermuxBridge.submitOperation(this, command, canonical);
        } catch (RuntimeException failure) {
            updateNotification(
                    "Operation rejected · "
                            + bounded(failure.getMessage() == null ? "invalid request" : failure.getMessage()));
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private JSONObject validate(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_OPERATION_CHARS) {
            throw new IllegalArgumentException("Operation must be 1..256 KiB");
        }
        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Operation JSON is malformed", error);
        }
        String kind = root.optString("kind").trim();
        if (!ALLOWED_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported operation kind");
        }
        String requestId = root.optString("request_id").trim();
        if (requestId.length() == 0 || requestId.length() > 240) {
            throw new IllegalArgumentException("request_id required");
        }
        Object payload = root.opt("payload");
        if (!(payload instanceof JSONObject)) {
            throw new IllegalArgumentException("payload must be an object");
        }
        String sourcePackage = root.optString("source_package").trim();
        if (sourcePackage.length() == 0 || sourcePackage.length() > 240) {
            throw new IllegalArgumentException("source_package required");
        }
        try {
            root.put("protocol", "HACKMODE-ANDROID/1");
            root.put("bridge_package", getPackageName());
            root.put("sender_authorization", "signature_permission");
        } catch (JSONException error) {
            throw new IllegalStateException("Could not encode validated operation", error);
        }
        return root;
    }

    private void startVisible(String detail) {
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Hackmode actor service")
                        .setContentText(detail)
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .setOngoing(true)
                        .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String detail) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.notify(
                NOTIFICATION_ID,
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Hackmode actor service")
                        .setContentText(detail)
                        .setSmallIcon(android.R.drawable.ic_menu_manage)
                        .build());
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        manager.createNotificationChannel(
                new NotificationChannel(
                        CHANNEL,
                        "Hackmode actor operations",
                        NotificationManager.IMPORTANCE_LOW));
    }

    private static String bounded(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
