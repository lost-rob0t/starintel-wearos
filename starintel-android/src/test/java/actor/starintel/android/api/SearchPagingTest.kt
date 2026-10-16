package actor.starintel.android.api

import actor.starintel.android.model.StarSession
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SearchPagingTest {
    private fun page(cursor: String?, count: Int = 40) = SearchPage.fromJson(JSONObject()
        .put("rows", JSONArray().apply { repeat(count) { put(JSONObject().put("id", "row-$it")) } })
        .put("bookmark", cursor ?: JSONObject.NULL), 40)

    @Test fun opaqueBookmarkUnknownMoreAndTotal() {
        val parsed = SearchPage.fromJson(JSONObject("""{"rows":[{"id":"a"}],"bookmark":"a+/= &?","total_rows":1000000}"""), 40)
        assertEquals("a+/= &?", parsed.nextCursor)
        assertNull(parsed.hasMore)
        assertEquals(1000000L, parsed.total)
        assertNull(page("next").total)
        assertFalse(page(null, 0).hasMore!!)
        assertNull(page("last", 0).nextCursor)
        assertNull(page(null).nextCursor)
    }

    @Test fun malformedResponsesFailClosed() {
        listOf("""{"rows":[],"bookmark":4}""", """{"rows":[],"bookmark":" "}""",
            """{"rows":[],"total_rows":-1}""", """{"rows":[],"total_rows":"7"}""",
            """{"rows":[],"total_rows":1.5}""", """{"status":"ok"}""").forEach { json ->
            assertThrows(Exception::class.java) { SearchPage.fromJson(JSONObject(json), 40) }
        }
        assertThrows(Exception::class.java) { page("x".repeat(8193)) }
        assertThrows(Exception::class.java) { page("x", 41) }
        assertThrows(Exception::class.java) { SearchPage.fromJson(JSONObject("""{"rows":[{}],"bookmark":"same"}"""), 40, "same") }
        assertFalse(SearchPage.fromJson(JSONObject("""{"rows":[],"bookmark":"same"}"""), 40, "same").hasMore!!)
    }

    @Test fun duplicateRowsAreNotAppendedAndRetryIsIdempotent() {
        val parsed = SearchPage.fromJson(JSONObject("""{"rows":[{"id":"a"},{"id":"a"},{"doc":{"_id":"b"}}],"bookmark":"next"}"""), 40)
        assertEquals(2, parsed.rows.length())
        val pager = SearchPager()
        val request = pager.start("*")
        assertTrue(pager.complete(request, parsed))
        assertFalse(pager.complete(request, parsed))
        val failed = pager.next()!!
        assertTrue(pager.fail(failed))
        assertEquals(1L, pager.current!!.number)
        val retry = pager.next()!!
        assertEquals(failed.cursor, retry.cursor)
        assertTrue(pager.complete(retry, page("end", 1)))
        assertEquals(2, pager.cachedPages)
    }

    @Test fun staleQueryAndCycleCannotReplaceLastGoodPage() {
        val pager = SearchPager()
        val stale = pager.start("old")
        val fresh = pager.start("new")
        assertFalse(pager.complete(stale, page("old-next")))
        assertTrue(pager.complete(fresh, page("a")))
        assertTrue(pager.complete(pager.next()!!, page("b")))
        val cycle = pager.next()!!
        assertThrows(Exception::class.java) { pager.complete(cycle, page("a")) }
        pager.fail(cycle)
        assertEquals(2L, pager.current!!.number)
        assertEquals("new", pager.current!!.query)
    }

    @Test fun proceduralMillionRowsUsesFixedPageWindow() {
        val pager = SearchPager()
        var request = pager.start("*")
        repeat(25000) { index ->
            val rows = JSONArray().apply { repeat(40) { put(JSONObject().put("id", "${index * 40 + it}")) } }
            val next = "bookmark-${index + 1}"
            assertTrue(pager.complete(request, SearchPage(rows, next, null, 1000000)))
            assertTrue(pager.cachedPages <= 3)
            request = pager.next()!!
        }
        assertTrue(pager.complete(request, page(null, 0)))
        assertFalse(pager.canNext)
        assertEquals(25001L, pager.current!!.number)
        assertEquals(25000L, pager.previous()!!.number)
        assertEquals(25001L, pager.forwardCached()!!.number)
        pager.previous(); pager.previous()
        assertFalse(pager.canPrevious)
    }

    @Test fun bookmarkCheckpointRestoresPageWithoutReplayingCorpus() {
        val pager = SearchPager()
        pager.complete(pager.start("query"), page("a"))
        pager.complete(pager.next()!!, page("b"))
        val checkpoint = pager.current!!
        val restored = SearchPager()
        val request = restored.start(checkpoint.query, checkpoint.cursor, checkpoint.number)
        assertEquals("a", request.cursor)
        restored.complete(request, page("b"))
        assertEquals(2L, restored.current!!.number)
        assertFalse(restored.canPrevious)
        assertEquals("b", restored.next()!!.cursor)
    }

    @Test fun httpContractPreservesBookmarkAndOnlyFallsBackOn404Or405() {
        for (status in listOf(200, 404, 405, 401, 403, 500)) {
            val paths = mutableListOf<String>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                paths.add(exchange.requestURI.toASCIIString())
                val code = if (paths.size == 1) status else 200
                val bytes = """{"rows":[{"id":"a"}],"bookmark":"next","total_rows":3}""".toByteArray()
                exchange.sendResponseHeaders(code, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val client = StarIntelClient({ StarSession("http://127.0.0.1:${server.address.port}", "star_sk_v1_fixture") }, "test", true)
                if (status in listOf(200, 404, 405)) {
                    val result = client.searchPage("a & b", 1000, "opaque+/= &?")
                    assertEquals(3L, result.total)
                    assertEquals("next", result.nextCursor)
                } else assertThrows(StarHttpFailure::class.java) { client.searchPage("a & b", 1000, "opaque+/= &?") }
                assertEquals(if (status in listOf(404, 405)) 2 else 1, paths.size)
                assertEquals("/api/v1/documents/search?q=a+%26+b&limit=100&bookmark=opaque%2B%2F%3D+%26%3F", paths.first())
                if (paths.size == 2) assertEquals(paths.first().replace("/documents/search", "/search"), paths.last())
            } finally { server.stop(0) }
        }
    }
}
