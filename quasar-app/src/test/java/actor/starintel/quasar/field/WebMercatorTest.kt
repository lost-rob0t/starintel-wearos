package actor.starintel.quasar.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebMercatorTest {
    @Test
    fun projectsOriginAndRoundTrips() {
        val world = WebMercator.project(GeoPoint(0.0, 0.0), zoom = 1)
        assertEquals(256.0, world.x, 0.0001)
        assertEquals(256.0, world.y, 0.0001)

        val point = WebMercator.unproject(world, zoom = 1)
        assertEquals(0.0, point.latitude, 0.0001)
        assertEquals(0.0, point.longitude, 0.0001)
    }

    @Test
    fun tilePolicyWrapsLongitudeButRejectsNonexistentRows() {
        assertEquals(
            "https://tile.openstreetmap.org/3/7/2.png",
            OpenMapTilePolicy.tileUrl(zoom = 3, x = -1, y = 2),
        )
        assertNull(OpenMapTilePolicy.tileUrl(zoom = 3, x = 1, y = -1))
        assertNull(OpenMapTilePolicy.tileUrl(zoom = 3, x = 1, y = 8))
        assertEquals("© OpenStreetMap contributors", OpenMapTilePolicy.attribution)
    }
}
