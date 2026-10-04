package actor.starintel.collector;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

/** Immutable best-effort location attached at the instant a capture begins. */
final class LocationSnapshot {
    final Double latitude;
    final Double longitude;
    final Double altitude;
    final Float accuracy;
    final long observedAtMs;

    LocationSnapshot(
            Double latitude, Double longitude, Double altitude, Float accuracy, long observedAtMs) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.accuracy = accuracy;
        this.observedAtMs = observedAtMs;
    }

    boolean available() {
        return latitude != null && longitude != null;
    }

    static LocationSnapshot bestEffort(Context context) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return new LocationSnapshot(null, null, null, null, System.currentTimeMillis());
        }
        LocationManager manager = context.getSystemService(LocationManager.class);
        if (manager == null) {
            return new LocationSnapshot(null, null, null, null, System.currentTimeMillis());
        }
        Location best = null;
        try {
            Location gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null) best = gps;
            if (network != null && (best == null || network.getTime() > best.getTime())) best = network;
        } catch (SecurityException ignored) {
            // Permission can be revoked between the explicit check and provider call.
        }
        if (best == null) {
            return new LocationSnapshot(null, null, null, null, System.currentTimeMillis());
        }
        return new LocationSnapshot(
                best.getLatitude(),
                best.getLongitude(),
                best.hasAltitude() ? best.getAltitude() : null,
                best.hasAccuracy() ? best.getAccuracy() : null,
                best.getTime() > 0L ? best.getTime() : System.currentTimeMillis());
    }
}
