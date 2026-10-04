package actor.starintel.collector;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanSettings;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

final class StarWirelessScanner {
    private static final long SCAN_INTERVAL_MS = 10_000L;
    private static final long LOCATION_INTERVAL_MS = 5_000L;
    private static final float LOCATION_DISTANCE_M = 3.0f;

    private final Context context;
    private final StarWirelessStore store;
    private final WifiManager wifi;
    private final LocationManager locations;
    private final BluetoothLeScanner bluetooth;
    private final String runId;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HandlerThread workerThread;
    private Handler worker;
    private volatile Location lastLocation;

    StarWirelessScanner(Context context, StarWirelessStore store, String runId) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.wifi = this.context.getSystemService(WifiManager.class);
        this.locations = this.context.getSystemService(LocationManager.class);
        BluetoothManager bluetoothManager = this.context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        this.bluetooth = adapter == null ? null : adapter.getBluetoothLeScanner();
        this.runId = runId == null ? "" : runId;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        workerThread = new HandlerThread("star-wireless-scan");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        registerScanReceiver();
        startLocationUpdates();
        startBluetoothScan();
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
        stopBluetoothScan();
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
                        WirelessObservation observation = WirelessObservation.wifi(
                                result.BSSID,
                                result.SSID,
                                result.frequency,
                                result.capabilities,
                                result.level,
                                result.timestamp,
                                android.os.SystemClock.elapsedRealtimeNanos(),
                                wallNow);
                        store.recordLiveObservation(
                                observation.eventKey,
                                observation.address,
                                observation.label,
                                observation.frequencyMhz,
                                observation.capabilities,
                                observation.signalDbm,
                                snapshot == null ? null : snapshot.getLatitude(),
                                snapshot == null ? null : snapshot.getLongitude(),
                                snapshot == null || !snapshot.hasAltitude() ? null : snapshot.getAltitude(),
                                snapshot == null || !snapshot.hasAccuracy() ? null : snapshot.getAccuracy(),
                                observation.observedAtMs);
                        String dataset = DocumentProjection.datasetForCapture(observation.observedAtMs);
                        DocumentProjection.projectWifiObservation(
                                store,
                                dataset,
                                observation.eventKey,
                                observation.address,
                                observation.label,
                                observation.frequencyMhz,
                                observation.capabilities,
                                observation.signalDbm,
                                snapshot == null ? null : snapshot.getLatitude(),
                                snapshot == null ? null : snapshot.getLongitude(),
                                snapshot == null || !snapshot.hasAltitude() ? null : snapshot.getAltitude(),
                                snapshot == null || !snapshot.hasAccuracy() ? null : snapshot.getAccuracy(),
                                observation.observedAtMs,
                                runId);
                        store.markObservationProjected(observation.eventKey);
                    }
                }
            };

    private final ScanCallback bluetoothCallback =
            new ScanCallback() {
                @Override
                public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result) {
                    if (!running.get() || result == null || result.getDevice() == null) return;
                    String address;
                    try {
                        address = result.getDevice().getAddress();
                    } catch (SecurityException denied) {
                        return;
                    }
                    if (address == null || address.trim().isEmpty()) return;
                    ScanRecord record = result.getScanRecord();
                    String name = record == null ? "" : record.getDeviceName();
                    Integer txPower = record == null || record.getTxPowerLevel() == Integer.MIN_VALUE
                            ? null : record.getTxPowerLevel();
                    JSONArray serviceUuids = new JSONArray();
                    if (record != null && record.getServiceUuids() != null) {
                        for (android.os.ParcelUuid uuid : record.getServiceUuids()) {
                            if (uuid != null) serviceUuids.put(uuid.toString());
                        }
                    }
                    WirelessObservation observation = WirelessObservation.bluetooth(
                            address,
                            name,
                            result.getRssi(),
                            result.getTimestampNanos(),
                            android.os.SystemClock.elapsedRealtimeNanos(),
                            System.currentTimeMillis());
                    Location snapshot = lastLocation;
                    store.recordBluetoothObservation(
                            observation.eventKey,
                            observation.address,
                            observation.label,
                            serviceUuids.toString(),
                            observation.signalDbm,
                            snapshot == null ? null : snapshot.getLatitude(),
                            snapshot == null ? null : snapshot.getLongitude(),
                            snapshot == null || !snapshot.hasAltitude() ? null : snapshot.getAltitude(),
                            snapshot == null || !snapshot.hasAccuracy() ? null : snapshot.getAccuracy(),
                            observation.observedAtMs);
                    DocumentProjection.projectBluetoothObservation(
                            store,
                            DocumentProjection.datasetForCapture(observation.observedAtMs),
                            observation.eventKey,
                            observation.address,
                            observation.label,
                            observation.signalDbm,
                            txPower,
                            serviceUuids,
                            snapshot == null ? null : snapshot.getLatitude(),
                            snapshot == null ? null : snapshot.getLongitude(),
                            snapshot == null || !snapshot.hasAltitude() ? null : snapshot.getAltitude(),
                            snapshot == null || !snapshot.hasAccuracy() ? null : snapshot.getAccuracy(),
                            observation.observedAtMs,
                            runId);
                    store.markObservationProjected(observation.eventKey);
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

    private void startBluetoothScan() {
        if (bluetooth == null || !hasBluetoothPermission()) return;
        try {
            bluetooth.startScan(
                    null,
                    new ScanSettings.Builder()
                            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                            .setReportDelay(0L)
                            .build(),
                    bluetoothCallback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
    }

    private void stopBluetoothScan() {
        if (bluetooth == null || !hasBluetoothPermission()) return;
        try {
            bluetooth.stopScan(bluetoothCallback);
        } catch (SecurityException | IllegalStateException ignored) {
        }
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ? hasFineLocation()
                : context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED;
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

}
