package actor.starintel.quasar.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoGraphLayoutTest {
    @Test
    fun graphOnlyIncludesResolvableEdgesAndLayoutIsDeterministic() {
        val features = listOf(
            GeoFeature("a", "Alpha", "place", GeoPoint(0.0, 0.0), GeoLayer.PLACES, links = listOf("b", "missing")),
            GeoFeature("b", "Bravo", "event", GeoPoint(1.0, 1.0), GeoLayer.EVENTS, links = listOf("a")),
            GeoFeature("c", "Charlie", "signal", GeoPoint(2.0, 2.0), GeoLayer.SIGNALS),
        )

        val graph = GeoGraph.from(features)
        val first = GeoGraphLayout.layout(graph, 1_000f, 600f)
        val second = GeoGraphLayout.layout(graph, 1_000f, 600f)

        assertEquals(setOf(GeoEdge("a", "b")), graph.edges)
        assertEquals(first, second)
        assertEquals(3, first.size)
        assertTrue(first.values.all { it.x in 60f..940f && it.y in 60f..540f })
    }
}
