package actor.starintel.quasar.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {
    @Test
    fun computesDistanceAndInitialBearing() {
        val newYork = GeoPoint(40.7128, -74.0060)
        val london = GeoPoint(51.5074, -0.1278)

        assertEquals(5_570.0, GeoMath.distanceMeters(newYork, london) / 1_000.0, 15.0)
        assertEquals(51.2, GeoMath.initialBearingDegrees(newYork, london), 1.0)
    }

    @Test
    fun formatsOperationalReadoutWithoutPretendingToBeMorePrecise() {
        assertEquals("40.71280 N  ·  074.00600 W", GeoMath.coordinateReadout(GeoPoint(40.7128, -74.006)))
        assertEquals("318 m", GeoMath.distanceReadout(318.4))
        assertEquals("2.4 km", GeoMath.distanceReadout(2_420.0))
        assertEquals("051° NE", GeoMath.bearingReadout(51.2))
    }

    @Test
    fun rejectsInvalidCoordinates() {
        assertTrue(runCatching { GeoPoint(91.0, 0.0) }.isFailure)
        assertTrue(runCatching { GeoPoint(0.0, 181.0) }.isFailure)
    }
}
