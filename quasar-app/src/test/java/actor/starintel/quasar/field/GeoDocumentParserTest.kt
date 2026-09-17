package actor.starintel.quasar.field

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoDocumentParserTest {
    @Test
    fun parsesGeoJsonAndJsonLdDocumentsWithoutInventingMissingLocations() {
        val rows = JSONArray(
            """[
              {"doc":{"_id":"place:alpha","dtype":"place","name":"Alpha relay","geometry":{"type":"Point","coordinates":[-83.01,39.96]},"links":[{"id":"event:one"}]}},
              {"doc":{"_id":"event:one","@type":"Event","title":"Observed transfer","geo":{"latitude":39.97,"longitude":-83.02},"relations":[{"target":"place:alpha"}]}},
              {"doc":{"_id":"person:no-geo","dtype":"person","name":"No position"}}
            ]""",
        )

        val features = GeoDocumentParser.parseSearchRows(rows)

        assertEquals(2, features.size)
        assertEquals(GeoLayer.PLACES, features[0].layer)
        assertEquals(GeoPoint(39.96, -83.01), features[0].point)
        assertEquals(listOf("event:one"), features[0].links)
        assertEquals(GeoLayer.EVENTS, features[1].layer)
        assertEquals(listOf("place:alpha"), features[1].links)
    }

    @Test
    fun localFilterMatchesIdentityTypeAndTextAndHonorsVisibleLayers() {
        val alpha = GeoFeature("place:alpha", "Alpha Relay", "place", GeoPoint(1.0, 2.0), GeoLayer.PLACES)
        val bravo = GeoFeature("signal:bravo", "Bravo Wi-Fi", "wireless-signal", GeoPoint(3.0, 4.0), GeoLayer.SIGNALS)

        assertEquals(listOf(bravo), GeoFeatureFilter.apply(listOf(alpha, bravo), "bravo signal", setOf(GeoLayer.SIGNALS)))
        assertEquals(listOf(alpha), GeoFeatureFilter.apply(listOf(alpha, bravo), "", setOf(GeoLayer.PLACES)))
        assertFalse(GeoFeatureFilter.matches(alpha, "missing"))
        assertTrue(GeoFeatureFilter.matches(alpha, "place alpha"))
    }
}
