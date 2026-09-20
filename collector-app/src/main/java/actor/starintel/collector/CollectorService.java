package actor.starintel.collector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class CollectorService extends Service {
    static final String PREFS = "collector_runtime_v1";
    static final String KEY_ACTIVE = "active";
    static final String KEY_STARTED_AT = "started_at_ms";
    private static final String CHANNEL = "starintel_collector";
    private static final int NOTIFICATION_ID = 4107;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("StarIntel Collector active")
                .setContentText("Foreground collection session is running")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_STARTED_AT, System.currentTimeMillis())
                .apply();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVE, false)
                .apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "StarIntel Collector",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Visible status for an active StarIntel collection session");
        manager.createNotificationChannel(channel);
    }
}
