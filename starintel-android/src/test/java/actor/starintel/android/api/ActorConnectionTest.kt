package actor.starintel.android.api

import actor.starintel.android.model.RemoteActor
import actor.starintel.android.model.StarSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class ActorConnectionTest {
    @Test fun privateAndMalformedRegistryEntriesAreRejected() {
        val row = JSONObject("""{"resourceUri":"star://biz/actor/example","semantic":{"name":"example"},"operatorVisible":false,"ready":true}""")
        assertNull(RemoteActor.fromJson(row))
        row.put("operatorVisible", true)
        assertEquals("example", RemoteActor.fromJson(row)?.name)
        row.put("resourceUri", "https://untrusted.example")
        assertNull(RemoteActor.fromJson(row))
    }

    @Test fun originsCannotCarryCredentialsPathsQueriesOrFragments() {
        val client = StarIntelClient({ null }, "test", true)
        for (origin in listOf("http://user:password@localhost", "https://example.com/path", "https://example.com?token=x", "https://example.com#x")) {
            try { client.authContext(origin, "star_sk_v1_test"); fail(origin) } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun targetUsesCanonicalJsonMapOptionsAndNonemptyIdempotencyKey() {
        ServerSocket(0).use { server ->
            val received = CompletableFuture<JSONObject>()
            val worker = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    input.readLine()
                    var length = 0
                    while (true) {
                        val header = input.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length)
                    var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                    received.complete(JSONObject(String(body)))
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                }
            }.apply { start() }
            val client = StarIntelClient({ StarSession("http://127.0.0.1:${server.localPort}", "star_sk_v1_test") }, "test", true)
            client.createTarget("example", "sample", "test")
            val body = received.get(5, TimeUnit.SECONDS)
            assertTrue(body.get("options") is JSONObject)
            assertEquals("example", body.getString("actor"))
            assertTrue(body.getString("idempotency_key").isNotBlank())
            worker.join(5000)
        }
    }

    @Test fun authorizationFailureNeverFallsBackToLegacyDispatch() {
        ServerSocket(0).use { server ->
            val request = CompletableFuture<String>()
            val worker = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    val first = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                    request.complete(first)
                }
            }.apply { start() }
            val client = StarIntelClient({ StarSession("http://127.0.0.1:${server.localPort}", "star_sk_v1_test") }, "test", true)
            try { client.createTarget("example", "sample", "test"); fail("403 must remain denied") }
            catch (failure: StarHttpFailure) { assertEquals(403, failure.status) }
            assertEquals("POST /api/v1/targets HTTP/1.1", request.get(5, TimeUnit.SECONDS))
            worker.join(5_000)
        }
    }
}
