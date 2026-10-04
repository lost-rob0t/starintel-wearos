package actor.starintel.collector;

import java.util.Locale;

/** Platform-neutral wireless scan event used by the Android adapters and deterministic tests. */
final class WirelessObservation {
    private static final long MAX_PLATFORM_TIMESTAMP_AGE_MS = 24L * 60L * 60L * 1000L;

    enum Transport {
        WIFI("live"),
        BLUETOOTH_LE("ble");

        final String eventPrefix;

        Transport(String eventPrefix) {
            this.eventPrefix = eventPrefix;
        }
    }

    final Transport transport;
    final String address;
    final String label;
    final int frequencyMhz;
    final String capabilities;
    final int signalDbm;
    final long observedAtMs;
    final String eventKey;

    private WirelessObservation(
            Transport transport,
            String address,
            String label,
            int frequencyMhz,
            String capabilities,
            int signalDbm,
            long observedAtMs) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("wireless address required");
        }
        this.transport = transport;
        this.address = address.trim();
        this.label = label == null ? "" : label;
        this.frequencyMhz = Math.max(0, frequencyMhz);
        this.capabilities = capabilities == null ? "" : capabilities;
        this.signalDbm = signalDbm;
        this.observedAtMs = Math.max(0L, observedAtMs);
        this.eventKey = transport.eventPrefix
                + ":" + this.observedAtMs
                + ":" + this.address.toLowerCase(Locale.ROOT)
                + (transport == Transport.WIFI ? ":" + this.frequencyMhz : "")
                + ":" + signalDbm;
    }

    static WirelessObservation wifi(
            String bssid,
            String ssid,
            int frequencyMhz,
            String capabilities,
            int signalDbm,
            long scanTimestampMicros,
            long elapsedRealtimeNanos,
            long wallNowMs) {
        return new WirelessObservation(
                Transport.WIFI,
                bssid,
                ssid,
                frequencyMhz,
                capabilities,
                signalDbm,
                observedAtFromMicros(scanTimestampMicros, elapsedRealtimeNanos, wallNowMs));
    }

    static WirelessObservation bluetooth(
            String address,
            String name,
            int signalDbm,
            long scanTimestampNanos,
            long elapsedRealtimeNanos,
            long wallNowMs) {
        return new WirelessObservation(
                Transport.BLUETOOTH_LE,
                address,
                name,
                0,
                "",
                signalDbm,
                observedAtFromNanos(scanTimestampNanos, elapsedRealtimeNanos, wallNowMs));
    }

    private static long observedAtFromMicros(
            long scanTimestampMicros, long elapsedRealtimeNanos, long wallNowMs) {
        if (scanTimestampMicros <= 0L) return wallNowMs;
        long elapsedMicros = elapsedRealtimeNanos / 1_000L;
        long ageMs = Math.max(0L, elapsedMicros - scanTimestampMicros) / 1_000L;
        return ageMs < MAX_PLATFORM_TIMESTAMP_AGE_MS ? wallNowMs - ageMs : wallNowMs;
    }

    private static long observedAtFromNanos(
            long scanTimestampNanos, long elapsedRealtimeNanos, long wallNowMs) {
        if (scanTimestampNanos <= 0L) return wallNowMs;
        long ageMs = Math.max(0L, elapsedRealtimeNanos - scanTimestampNanos) / 1_000_000L;
        return ageMs < MAX_PLATFORM_TIMESTAMP_AGE_MS ? wallNowMs - ageMs : wallNowMs;
    }
}
