package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarIntelSearchClientTest {
    @Test
    fun parsesCouchSearchRows() {
        val result = parseSearchPayload(
            """
            {
              "total_rows": 2,
              "bookmark": "next-page",
              "rows": [
                {
                  "id": "target:alice",
                  "doc": {
                    "_id": "target:alice",
                    "dtype": "target",
                    "dataset": "public",
                    "name": "Alice"
                  }
                },
                {
                  "id": "url:example",
                  "doc": {
                    "dtype": "document",
                    "title": "Example result",
                    "source": "web"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        assertNull(result.error)
        assertEquals(2L, result.totalRows)
        assertEquals("next-page", result.bookmark)
        assertEquals(2, result.hits.size)
        assertEquals("target:alice", result.hits[0].id)
        assertEquals("Alice", result.hits[0].title)
        assertEquals("target · public", result.hits[0].secondary)
        assertEquals("Example result", result.hits[1].title)
        assertEquals("document · web", result.hits[1].secondary)
    }

    @Test
    fun missingRowsIsSafeEmptyResult() {
        val result = parseSearchPayload("{\"bookmark\":\"done\"}")

        assertTrue(result.hits.isEmpty())
        assertEquals("done", result.bookmark)
        assertNull(result.totalRows)
        assertNull(result.error)
    }

    @Test
    fun requestedLimitBoundsRenderedHits() {
        val result = parseSearchPayload(
            """
            {
              "rows": [
                {"id":"1","doc":{"title":"one"}},
                {"id":"2","doc":{"title":"two"}},
                {"id":"3","doc":{"title":"three"}}
              ]
            }
            """.trimIndent(),
            resultLimit = 2,
        )

        assertEquals(listOf("1", "2"), result.hits.map { it.id })
    }

    @Test
    fun titleFallsBackToStableResultId() {
        val result = parseSearchPayload("{\"rows\":[{\"id\":\"opaque-id\",\"doc\":{\"dtype\":\"target\"}}]}")

        assertEquals("opaque-id", result.hits.single().title)
    }
}
