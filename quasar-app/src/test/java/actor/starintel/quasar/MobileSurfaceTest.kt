package actor.starintel.quasar

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileSurfaceTest {
    @Test
    fun preservesDesktopTopLevelFeatureParity() {
        assertEquals(
            listOf(
                "Stats",
                "Graphs",
                "Datasets",
                "Documents",
                "Add document",
                "Agents",
                "Actors",
                "Import",
                "Targets",
                "Settings",
            ),
            MobileSurface.entries.map(MobileSurface::label),
        )
    }

    @Test
    fun usesSingleColumnOnPhonesAndTwoOnLargeScreens() {
        assertEquals(1, routeColumnCount(320))
        assertEquals(1, routeColumnCount(599))
        assertEquals(2, routeColumnCount(600))
        assertEquals(2, routeColumnCount(840))
    }
}
