package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.SocketTimeoutException

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
    fun parsesCanonicalV09NestedData() {
        val result = parseSearchPayload(
            """
            {
              "rows": [{
                "id": "target:github:alice",
                "doc": {
                  "_id": "target:github:alice",
                  "dtype": "target",
                  "dataset": "investigation-a",
                  "data": {
                    "target": "alice",
                    "actor": "user-hunt",
                    "platform": "github"
                  }
                }
              }]
            }
            """.trimIndent(),
        )

        assertEquals("alice", result.hits.single().title)
        assertEquals("target · investigation-a · github · user-hunt", result.hits.single().secondary)
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

    @Test
    fun oversizedSearchRetriesProgressivelySmallerPages() {
        assertEquals(listOf(50, 16, 8, 4), StarIntelSearchClient.retryLimits(50))
        assertEquals(listOf(12, 8, 4), StarIntelSearchClient.retryLimits(12))
        assertEquals(listOf(4), StarIntelSearchClient.retryLimits(4))
    }

    @Test
    fun searchRetriesTimeoutAndServerFailureButNotAuthorizationOrBadQuery() {
        assertTrue(StarIntelSearchClient.shouldRetrySearch(SocketTimeoutException()))
        assertTrue(StarIntelSearchClient.shouldRetrySearch(IllegalStateException("HTTP 500")))
        assertTrue(StarIntelSearchClient.shouldRetrySearch(IllegalStateException("Search response too large")))
        assertFalse(StarIntelSearchClient.shouldRetrySearch(IllegalStateException("HTTP 401 unauthorized")))
        assertFalse(StarIntelSearchClient.shouldRetrySearch(IllegalStateException("HTTP 400 invalid search")))
    }
}
