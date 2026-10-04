package actor.starintel.android.api

import actor.starintel.android.fbp.DocumentStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.json.JSONObject

/** Minimal RFC 6455 client for the bounded StarIntel document-ingest subprotocol. */
class StarWebSocketDocumentStream(
    serverUrl: String,
    private val apiKey: String,
    private val clientVersion: String,
    allowCleartext: Boolean = false,
) : DocumentStream {
    private val endpoint = streamEndpoint(serverUrl, allowCleartext)
    private val random = SecureRandom()
    private var connection: Connection? = null

    init {
        require(
            apiKey.startsWith("star_sk_v1_") && apiKey.length <= 4_096 &&
                apiKey.none { it == '\r' || it == '\n' },
        ) { "Invalid StarIntel API key" }
        require(clientVersion.length <= 128 && clientVersion.none { it == '\r' || it == '\n' }) { "Invalid client version" }
    }

    @Synchronized
    override fun emit(correlationId: String, document: JSONObject): JSONObject {
        require(correlationId.isNotBlank() && correlationId.length <= MAX_CORRELATION_CHARS) {
            "Invalid document correlation id"
        }
        val envelope = JSONObject()
            .put("correlation_id", correlationId)
            .put("document", JSONObject(document.toString()))
        val payload = envelope.toString().toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_DOCUMENT_FRAME_BYTES) { "Document stream frame exceeds 512 KiB" }
        val active = connection ?: connect().also { connection = it }
        return try {
            active.writeFrame(OP_TEXT, payload)
            JSONObject(active.readTextMessage())
        } catch (failure: Throwable) {
            runCatching { active.close() }
            connection = null
            throw failure
        }
    }

    @Synchronized
    override fun close() {
        connection?.let { active ->
            runCatching { active.writeFrame(OP_CLOSE, byteArrayOf(0x03, 0xE8.toByte())) }
            runCatching { active.close() }
        }
        connection = null
    }

    private fun connect(): Connection {
        val port = if (endpoint.port >= 0) endpoint.port else if (endpoint.scheme == "wss") 443 else 80
        val socket = if (endpoint.scheme == "wss") {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply {
                sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                connect(InetSocketAddress(endpoint.host, port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
                startHandshake()
            }
        } else {
            Socket().apply {
                connect(InetSocketAddress(endpoint.host, port), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
        }
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val keyBytes = ByteArray(16).also(random::nextBytes)
            val key = Base64.getEncoder().encodeToString(keyBytes)
            val path = endpoint.rawPath.ifBlank { "/" } + endpoint.rawQuery?.let { "?$it" }.orEmpty()
            val defaultPort = (endpoint.scheme == "wss" && port == 443) || (endpoint.scheme == "ws" && port == 80)
            val hostName = endpoint.host.let { if (':' in it) "[$it]" else it }
            val host = if (defaultPort) hostName else "$hostName:$port"
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                append("Authorization: Bearer ").append(apiKey).append("\r\n")
                append("User-Agent: starintel-android/").append(clientVersion).append("\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            output.write(request)
            output.flush()
            val headers = readHttpHeaders(input)
            require(headers.statusCode == 101) { "WebSocket upgrade failed with HTTP ${headers.statusCode}" }
            require(headers.values["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                "WebSocket upgrade response is missing Upgrade"
            }
            require(headers.values["connection"]?.split(',')?.any { it.trim().equals("upgrade", true) } == true) {
                "WebSocket upgrade response is missing Connection"
            }
            val expected = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)),
            )
            require(headers.values["sec-websocket-accept"] == expected) { "Invalid WebSocket accept key" }
            return Connection(socket, input, output, random)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    private class Connection(
        private val socket: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
        private val random: SecureRandom,
    ) : Closeable {
        fun writeFrame(opcode: Int, payload: ByteArray) {
            require(payload.size <= MAX_DOCUMENT_FRAME_BYTES)
            output.write(FIN or opcode)
            when {
                payload.size <= 125 -> output.write(MASK or payload.size)
                payload.size <= 65_535 -> {
                    output.write(MASK or 126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
                else -> {
                    output.write(MASK or 127)
                    output.write(ByteArray(4))
                    output.write(payload.size ushr 24)
                    output.write(payload.size ushr 16)
                    output.write(payload.size ushr 8)
                    output.write(payload.size)
                }
            }
            val mask = ByteArray(4).also(random::nextBytes)
            output.write(mask)
            payload.forEachIndexed { index, byte -> output.write(byte.toInt() xor mask[index % 4].toInt()) }
            output.flush()
        }

        fun readTextMessage(): String {
            val message = ArrayList<Byte>()
            var started = false
            while (true) {
                val first = input.read().also { if (it < 0) throw EOFException("WebSocket closed") }
                val second = input.read().also { if (it < 0) throw EOFException("WebSocket closed") }
                val final = first and FIN != 0
                val opcode = first and 0x0F
                require(first and 0x70 == 0) { "WebSocket extensions were not negotiated" }
                require(second and MASK == 0) { "Server WebSocket frames must not be masked" }
                val length = readLength(second and 0x7F)
                require(length <= MAX_ACK_FRAME_BYTES) { "WebSocket acknowledgement exceeded 64 KiB" }
                if (opcode >= OP_CLOSE) require(final && length <= 125) { "Invalid WebSocket control frame" }
                val payload = ByteArray(length).also { readFully(input, it) }
                when (opcode) {
                    OP_PING -> writeFrame(OP_PONG, payload)
                    OP_PONG -> Unit
                    OP_CLOSE -> throw EOFException("Star ingest closed the WebSocket")
                    OP_TEXT -> {
                        require(!started) { "Unexpected text frame" }
                        started = true
                        message.addAll(payload.toList())
                    }
                    OP_CONTINUATION -> {
                        require(started) { "Unexpected continuation frame" }
                        message.addAll(payload.toList())
                    }
                    else -> error("Unsupported WebSocket opcode: $opcode")
                }
                require(message.size <= MAX_ACK_FRAME_BYTES) { "WebSocket acknowledgement exceeded 64 KiB" }
                if (started && final && opcode in listOf(OP_TEXT, OP_CONTINUATION)) {
                    val bytes = message.toByteArray()
                    return Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                }
            }
        }

        private fun readLength(code: Int): Int = when (code) {
            126 -> (requiredByte(input) shl 8) or requiredByte(input)
            127 -> {
                var value = 0L
                repeat(8) { value = (value shl 8) or requiredByte(input).toLong() }
                require(value in 0..MAX_ACK_FRAME_BYTES.toLong()) { "WebSocket frame is too large" }
                value.toInt()
            }
            else -> code
        }

        override fun close() = socket.close()
    }

    private data class HttpHeaders(val statusCode: Int, val values: Map<String, String>)

    companion object {
        private const val STREAM_PATH = "/api/v1/documents/stream"
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1_024
        private const val MAX_DOCUMENT_FRAME_BYTES = 512 * 1_024
        private const val MAX_ACK_FRAME_BYTES = 64 * 1_024
        private const val MAX_CORRELATION_CHARS = 512
        private const val FIN = 0x80
        private const val MASK = 0x80
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        private fun streamEndpoint(serverUrl: String, allowCleartext: Boolean): URI {
            val raw = URI(serverUrl.trim().trimEnd('/'))
            val scheme = when (raw.scheme?.lowercase()) {
                "https" -> "wss"
                "http" -> {
                    require(allowCleartext) { "Release clients require HTTPS" }
                    "ws"
                }
                else -> throw IllegalArgumentException("Use an HTTP or HTTPS server URL")
            }
            require(!raw.host.isNullOrBlank() && raw.userInfo == null && raw.fragment == null && raw.query == null) {
                "Invalid StarIntel server URL"
            }
            val basePath = raw.rawPath.orEmpty().trimEnd('/')
            return URI(scheme, null, raw.host, raw.port, basePath + STREAM_PATH, null, null)
        }

        private fun readHttpHeaders(input: BufferedInputStream): HttpHeaders {
            val bytes = ArrayList<Byte>()
            while (bytes.size < MAX_HTTP_HEADER_BYTES) {
                val next = input.read()
                if (next < 0) throw EOFException("WebSocket upgrade ended early")
                bytes += next.toByte()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == '\r'.code.toByte() && bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() && bytes[size - 1] == '\n'.code.toByte()
                ) break
            }
            require(bytes.size < MAX_HTTP_HEADER_BYTES) { "WebSocket upgrade headers exceeded 16 KiB" }
            val lines = bytes.toByteArray().toString(Charsets.ISO_8859_1).split("\r\n")
            val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull()
                ?: error("Invalid WebSocket upgrade status")
            val headers = linkedMapOf<String, String>()
            lines.drop(1).filter(String::isNotBlank).forEach { line ->
                val separator = line.indexOf(':')
                require(separator > 0) { "Invalid WebSocket upgrade header" }
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = headers[name]?.let { "$it,$value" } ?: value
            }
            return HttpHeaders(status, headers)
        }

        private fun requiredByte(input: BufferedInputStream): Int = input.read().also {
            if (it < 0) throw EOFException("WebSocket frame ended early")
        }

        private fun readFully(input: BufferedInputStream, destination: ByteArray) {
            var offset = 0
            while (offset < destination.size) {
                val count = input.read(destination, offset, destination.size - offset)
                if (count < 0) throw EOFException("WebSocket frame ended early")
                offset += count
            }
        }
    }
}
