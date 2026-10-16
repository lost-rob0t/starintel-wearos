package actor.starintel.android.api

import actor.starintel.android.model.StarSession
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class BulkImportSessionTest {
    private fun fixture(block: (HttpServer, StarIntelClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/auth/context") { exchange ->
            val response = """{"principal_id":"user-1","principal_type":"user"}""".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try { block(server, StarIntelClient({ StarSession("http://127.0.0.1:${server.address.port}", "star_sk_v1_before") }, "test", true)) }
        finally { server.stop(0) }
    }

    @Test fun exactUtf8BytesAndFrozenSession() = fixture { server, _ ->
        var current = StarSession("http://127.0.0.1:${server.address.port}", "star_sk_v1_before")
        val client = StarIntelClient({ current }, "test", true)
        val session = client.openBulkImportSession()
        assertFalse(session.destinationIdentity.contains("star_sk"))
        assertTrue(session.destinationIdentity.contains("user-1"))
        current = StarSession("http://127.0.0.1:1", "star_sk_v1_after")
        val body = """[{"name":"é😀"}]""".toByteArray()
        val calls = AtomicInteger()
        server.createContext("/api/v1/documents/bulk") { exchange ->
            calls.incrementAndGet()
            assertEquals("Bearer star_sk_v1_before", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals(body.size.toString(), exchange.requestHeaders.getFirst("Content-Length"))
            assertArrayEquals(body, exchange.requestBody.readBytes())
            val receipt = """{"total":1,"succeeded":1,"failed":0,"correlation_id":"r1"}""".toByteArray()
            exchange.sendResponseHeaders(200, receipt.size.toLong())
            exchange.responseBody.use { it.write(receipt) }
        }
        assertTrue(session.sendBatch(body).toString(Charsets.UTF_8).contains("r1"))
        assertEquals(1, calls.get())
    }

    @Test fun redirectsErrorsAndDroppedResponsesNeverRetry() {
        for (status in listOf(303, 401, 403, 404, 405, 500, -1)) fixture { server, client ->
            val calls = AtomicInteger()
            server.createContext("/api/v1/documents/bulk") { exchange ->
                calls.incrementAndGet(); exchange.requestBody.readBytes()
                if (status == 303) exchange.responseHeaders.add("Location", "/api/v1/documents/bulk")
                if (status != -1) exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
            val session = client.openBulkImportSession()
            assertThrows(Exception::class.java) { session.sendBatch("[{}]".toByteArray()) }
            assertEquals("status $status", 1, calls.get())
        }
    }

    @Test fun receiptBoundIsEncodedBytesAndRequestBoundsPrecedeMutation() = fixture { server, client ->
        val calls = AtomicInteger()
        server.createContext("/api/v1/documents/bulk") { exchange ->
            calls.incrementAndGet(); exchange.requestBody.readBytes()
            val response = "é".repeat(4097).toByteArray()
            exchange.sendResponseHeaders(200, 0) // Chunked response: cannot trust Content-Length.
            exchange.responseBody.use { it.write(response) }
        }
        val session = client.openBulkImportSession()
        assertThrows(IllegalArgumentException::class.java) { session.sendBatch(ByteArray(256 * 1024 + 1)) }
        assertThrows(IllegalArgumentException::class.java) { session.sendBatch("[]".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { session.sendBatch(("[" + List(11) { "{}" }.joinToString(",") + "]").toByteArray()) }
        assertEquals(0, calls.get())
        assertThrows(IllegalStateException::class.java) { session.sendBatch("[{}]".toByteArray()) }
        assertEquals(1, calls.get())
    }

    @Test fun missingPrincipalCannotCreateImportSession() = fixture { server, client ->
        server.removeContext("/auth/context")
        server.createContext("/auth/context") { exchange ->
            exchange.sendResponseHeaders(200, 2); exchange.responseBody.use { it.write("{}".toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { client.openBulkImportSession() }
    }
}
