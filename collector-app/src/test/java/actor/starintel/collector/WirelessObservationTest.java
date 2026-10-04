package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class WirelessObservationTest {
    @Test
    public void mockedWifiScanUsesMonotonicAgeAndStableEventIdentity() {
        long wallNow = 1_800_000_000_000L;
        long elapsedNanos = 900_000_000_000L;
        long scanMicros = elapsedNanos / 1_000L - 2_500_000L;

        WirelessObservation first = WirelessObservation.wifi(
                "AA:BB:CC:DD:EE:FF", "field-net", 2412, "[WPA2-PSK-CCMP][ESS]",
                -51, scanMicros, elapsedNanos, wallNow);
        WirelessObservation replay = WirelessObservation.wifi(
                "AA:BB:CC:DD:EE:FF", "field-net", 2412, "[WPA2-PSK-CCMP][ESS]",
                -51, scanMicros, elapsedNanos, wallNow);

        assertEquals(WirelessObservation.Transport.WIFI, first.transport);
        assertEquals(wallNow - 2_500L, first.observedAtMs);
        assertEquals("live:1799999997500:aa:bb:cc:dd:ee:ff:2412:-51", first.eventKey);
        assertEquals(first.eventKey, replay.eventKey);
    }

    @Test
    public void mockedWifiReadingsRemainEventScoped() {
        WirelessObservation first = WirelessObservation.wifi(
                "AA:BB:CC:DD:EE:FF", "field-net", 2412, "[ESS]", -51,
                0L, 10_000_000L, 100_000L);
        WirelessObservation later = WirelessObservation.wifi(
                "AA:BB:CC:DD:EE:FF", "field-net", 2412, "[ESS]", -60,
                0L, 20_000_000L, 110_000L);

        assertNotEquals(first.eventKey, later.eventKey);
        assertEquals("AA:BB:CC:DD:EE:FF", later.address);
        assertEquals(110_000L, later.observedAtMs);
    }

    @Test
    public void stalePlatformTimestampFallsBackToWallClock() {
        long wallNow = 5_000_000L;
        long elapsedNanos = 30L * 24L * 60L * 60L * 1_000_000_000L;
        WirelessObservation event = WirelessObservation.wifi(
                "00:11:22:33:44:55", "", 5180, "", -80,
                1L, elapsedNanos, wallNow);
        assertEquals(wallNow, event.observedAtMs);
    }

    @Test
    public void mockedBluetoothScanUsesNanosecondTimestamp() {
        long wallNow = 1_000_000L;
        long elapsedNanos = 10_000_000_000L;
        WirelessObservation event = WirelessObservation.bluetooth(
                "12:34:56:78:9A:BC", "Beacon", -62,
                elapsedNanos - 750_000_000L, elapsedNanos, wallNow);
        assertEquals(WirelessObservation.Transport.BLUETOOTH_LE, event.transport);
        assertEquals(wallNow - 750L, event.observedAtMs);
        assertEquals("ble:999250:12:34:56:78:9a:bc:-62", event.eventKey);
    }
}
