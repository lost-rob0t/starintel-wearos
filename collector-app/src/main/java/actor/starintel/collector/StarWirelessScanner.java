package actor.starintel.collector;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class StarWirelessScanner {
    private static final long SCAN_INTERVAL_MS = 10_000L;
    private static final long LOCATION_INTERVAL_MS = 5_000L;
    private static final float LOCATION_DISTANCE_M = 3.0f;

    private final Context context;
    private final StarWirelessStore store;
    private final WifiManager wifi;
    private final LocationManager locations;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HandlerThread workerThread;
    private Handler worker;
    private volatile Location lastLocation;

    StarWirelessScanner(Context context, StarWirelessStore store) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.wifi = this.context.getSystemService(WifiManager.class);
        this.locations = this.context.getSystemService(LocationManager.class);
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        workerThread = new HandlerThread("star-wireless-scan");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        registerScanReceiver();
        startLocationUpdates();
        worker.post(scanLoop);
    }

    void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            context.unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (locations != null) {
            try {
                locations.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (workerThread != null) workerThread.quitSafely();
    }

    private final Runnable scanLoop =
            new Runnable() {
                @Override
                public void run() {
                    if (!running.get()) return;
                    if (wifi != null && hasFineLocation()) {
                        try {
                            wifi.startScan();
                        } catch (SecurityException ignored) {
                        }
                    }
                    if (worker != null) worker.postDelayed(this, SCAN_INTERVAL_MS);
                }
            };

    private final BroadcastReceiver scanReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!running.get() || wifi == null || !hasFineLocation()) return;
                    List<ScanResult> results;
                    try {
                        results = wifi.getScanResults();
                    } catch (SecurityException denied) {
                        return;
                    }
                    if (results == null || results.isEmpty()) return;
                    Location snapshot = lastLocation;
                    long wallNow = System.currentTimeMillis();
                    for (ScanResult result : results) {
                        if (result == null || result.BSSID == null || result.BSSID.trim().isEmpty()) continue;
                        long observedAt = scanObservedAt(result, wallNow);
                        String eventKey =
                                "live:"
                                        + observedAt
                                        + ":"
                                        + result.BSSID.toLowerCase(Locale.ROOT)
                                        + ":"
                                        + result.frequency
                                        + ":"
                                        + result.level;
                        store.recordLiveObservation(
                                eventKey,
                                result.BSSID,
                                result.SSID,
                                result.frequency,
                                result.capabilities,
                                result.level,
                                snapshot == null ? null : snapshot.getLatitude(),
                                snapshot == null ? null : snapshot.getLongitude(),
                                snapshot == null || !snapshot.hasAltitude() ? null : snapshot.getAltitude(),
                                snapshot == null || !snapshot.hasAccuracy() ? null : snapshot.getAccuracy(),
                                observedAt);
                    }
                }
            };

    private final LocationListener locationListener =
            new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) lastLocation = new Location(location);
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            };

    private void registerScanReceiver() {
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(scanReceiver, filter);
        }
    }

    private void startLocationUpdates() {
        if (locations == null || !hasFineLocation()) return;
        try {
            Location gps = locations.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locations.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            lastLocation = newer(gps, network);
            if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locations.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        LOCATION_DISTANCE_M,
                        locationListener,
                        workerThread == null ? Looper.getMainLooper() : workerThread.getLooper());
            }
            if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locations.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_INTERVAL_MS,
                        LOCATION_DISTANCE_M,
                        locationListener,
                        workerThread == null ? Looper.getMainLooper() : workerThread.getLooper());
            }
        } catch (SecurityException ignored) {
        }
    }

    private boolean hasFineLocation() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static Location newer(Location first, Location second) {
        if (first == null) return second == null ? null : new Location(second);
        if (second == null) return new Location(first);
        return new Location(first.getTime() >= second.getTime() ? first : second);
    }

    private static long scanObservedAt(ScanResult result, long wallNow) {
        if (Build.VERSION.SDK_INT >= 17 && result.timestamp > 0L) {
            long elapsedMicros = android.os.SystemClock.elapsedRealtimeNanos() / 1_000L;
            long ageMicros = Math.max(0L, elapsedMicros - result.timestamp);
            long ageMs = ageMicros / 1_000L;
            if (ageMs < 24L * 60L * 60L * 1000L) return wallNow - ageMs;
        }
        return wallNow;
    }
}
