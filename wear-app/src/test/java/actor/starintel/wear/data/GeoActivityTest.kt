package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoActivityTest {
    @Test
    fun parsesNestedAndTopLevelCoordinates() {
        val raw = """
            {
              "rows": [
                {"doc": {"dtype":"geo", "data":{"lat":39.96,"long":-83.0}}},
                {"doc": {"dtype":"address", "latitude":"40.1","longitude":"-82.9"}}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                GeoCoordinate(39.96, -83.0),
                GeoCoordinate(40.1, -82.9),
            ),
            GeoActivityModel.parseSearchDocuments(raw),
        )
    }

    @Test
    fun malformedAndOutOfRangeCoordinatesAreIgnored() {
        val raw = """
            {
              "rows": [
                {"doc":{"data":{"lat":"nope","long":1}}},
                {"doc":{"data":{"lat":91,"long":1}}},
                {"doc":{"data":{"lat":1,"long":181}}},
                {"doc":{"data":{"lat":0,"long":0}}}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(GeoCoordinate(0.0, 0.0)),
            GeoActivityModel.parseSearchDocuments(raw),
        )
    }

    @Test
    fun emptyInputProducesNoBuckets() {
        assertTrue(GeoActivityModel.aggregate(emptyList()).isEmpty())
    }

    @Test
    fun aggregationCountsCoordinatesInTheSameCoarseCell() {
        val buckets = GeoActivityModel.aggregate(
            listOf(
                GeoCoordinate(39.9, -83.0),
                GeoCoordinate(40.1, -82.9),
                GeoCoordinate(-33.8, 151.2),
            ),
        )

        assertEquals(2, buckets.size)
        assertEquals(2, buckets.first().count)
        assertEquals(37.5, buckets.first().latitude, 0.0)
        assertEquals(-82.5, buckets.first().longitude, 0.0)
    }

    @Test
    fun positiveAndNegativeDatelineShareOneDeterministicCell() {
        val raw = """
            {"rows":[
              {"doc":{"lat":0,"long":180}},
              {"doc":{"lat":0,"long":-180}}
            ]}
        """.trimIndent()
        val coordinates = GeoActivityModel.parseSearchDocuments(raw)
        val buckets = GeoActivityModel.aggregate(coordinates)

        assertEquals(1, buckets.size)
        assertEquals(2, buckets.single().count)
        assertEquals(-172.5, buckets.single().longitude, 0.0)
    }

    @Test
    fun polesStayInsideValidCellCenters() {
        val buckets = GeoActivityModel.aggregate(
            listOf(
                GeoCoordinate(90.0, 0.0),
                GeoCoordinate(-90.0, 0.0),
            ),
        )

        assertEquals(2, buckets.size)
        assertTrue(buckets.all { it.latitude in -90.0..90.0 })
        assertTrue(buckets.any { it.latitude == 82.5 })
        assertTrue(buckets.any { it.latitude == -82.5 })
    }

    @Test
    fun denseInputIsBoundedToRequestedBucketCount() {
        val coordinates = buildList {
            for (lat in -82..82 step 15) {
                for (lon in -172..172 step 15) {
                    add(GeoCoordinate(lat.toDouble(), lon.toDouble()))
                }
            }
        }

        assertEquals(7, GeoActivityModel.aggregate(coordinates, maxBuckets = 7).size)
        assertEquals(GEO_MAX_BUCKETS, GeoActivityModel.aggregate(coordinates).size)
    }

    @Test
    fun zeroMaxBucketsReturnsEmpty() {
        val result = GeoActivityModel.aggregate(
            listOf(GeoCoordinate(0.0, 0.0)),
            maxBuckets = 0,
        )
        assertTrue(result.isEmpty())
    }
}
