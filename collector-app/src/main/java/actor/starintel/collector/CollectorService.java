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

public final class CollectorService extends Service {
    static final String PREFS = "collector_runtime_v2";
    static final String KEY_ACTIVE = "active";
    static final String KEY_STARTED_AT = "started_at_ms";

    private static final String CHANNEL = "star_wireless_collector";
    private static final int NOTIFICATION_ID = 4107;

    private StarWirelessStore store;
    private StarWirelessScanner scanner;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startVisible();

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
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (scanner != null) scanner.stop();
        if (store != null) store.close();

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

    private void startVisible() {
        Notification notification =
                new Notification.Builder(this, CHANNEL)
                        .setContentTitle("Star Wireless active")
                        .setContentText("Collecting user-authorized Wi-Fi and location observations")
                        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                        .setOngoing(true)
                        .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
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
