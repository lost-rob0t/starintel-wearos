package actor.starintel.android.api

import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StarWebSocketDocumentStreamTest {
    @Test
    fun upgradesWithBearerAuthAndExchangesOneMaskedDocumentEnvelope() {
        ServerSocket(0).use { server ->
            val observed = CompletableFuture<JSONObject>()
            val worker = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val headers = readHeaders(input)
                    assertTrue(headers.startsWith("GET /base/api/v1/documents/stream HTTP/1.1\r\n"))
                    assertTrue(headers.contains("Authorization: Bearer star_sk_v1_test-token\r\n"))
                    val key = Regex("Sec-WebSocket-Key: ([^\r]+)").find(headers)!!.groupValues[1]
                    val accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII),
                        ),
                    )
                    output.write(
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII),
                    )
                    output.flush()

                    val envelope = JSONObject(readClientTextFrame(input))
                    observed.complete(envelope)
                    val acknowledgement = JSONObject()
                        .put("correlation_id", envelope.getString("correlation_id"))
                        .put("outcome", "accepted")
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                    output.write(0x81)
                    output.write(acknowledgement.size)
                    output.write(acknowledgement)
                    output.flush()
                }
            }.apply { start() }

            val stream = StarWebSocketDocumentStream(
                "http://127.0.0.1:${server.localPort}/base",
                "star_sk_v1_test-token",
                "test",
                allowCleartext = true,
            )
            val acknowledgement = stream.emit("flow:sink:1", JSONObject().put("_id", "geo:1"))
            stream.close()

            assertEquals("accepted", acknowledgement.getString("outcome"))
            val envelope = observed.get(2, TimeUnit.SECONDS)
            assertEquals("flow:sink:1", envelope.getString("correlation_id"))
            assertEquals("geo:1", envelope.getJSONObject("document").getString("_id"))
            worker.join(2_000)
        }
    }

    private fun readHeaders(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < terminator.size) {
            val value = input.read()
            require(value >= 0)
            output.write(value)
            matched = if (value.toByte() == terminator[matched]) matched + 1 else 0
        }
        return output.toString(Charsets.ISO_8859_1.name())
    }

    private fun readClientTextFrame(input: java.io.InputStream): String {
        assertEquals(0x81, input.read())
        val sizeCode = input.read()
        assertTrue(sizeCode and 0x80 != 0)
        val length = when (sizeCode and 0x7F) {
            126 -> (input.read() shl 8) or input.read()
            else -> sizeCode and 0x7F
        }
        val mask = ByteArray(4).also { input.readFully(it) }
        val payload = ByteArray(length).also { input.readFully(it) }
        payload.indices.forEach { payload[it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        return payload.toString(Charsets.UTF_8)
    }

    private fun java.io.InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = read(target, offset, target.size - offset)
            require(count >= 0)
            offset += count
        }
    }
}
