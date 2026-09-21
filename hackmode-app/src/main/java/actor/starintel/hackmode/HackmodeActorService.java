package actor.starintel.hackmode;

import actor.starintel.android.config.StarIntelSharedConfig;
import actor.starintel.android.hackmode.HackmodeAndroidProtocol;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import org.json.JSONObject;

public final class HackmodeActorService extends Service {
    private static final String CHANNEL = "hackmode_actor_service";
    private static final int NOTIFICATION_ID = 4611;
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
        if (intent == null
                || !HackmodeAndroidProtocol.ACTION_OPERATION.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String raw =
                intent.getStringExtra(HackmodeAndroidProtocol.EXTRA_OPERATION_JSON);
        try {
            String canonical =
                    HackmodeAndroidProtocol.validateForBridge(
                                    raw,
                                    getPackageName())
                            .toString();
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
