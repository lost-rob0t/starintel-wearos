package actor.starintel.testing

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/** HTTP fixture exercising native transport, auth rejection, and dispatch bodies. */
class ContractServer : AutoCloseable {
    private val server = ServerSocket(0)
    val origin = "http://127.0.0.1:${server.localPort}"
    val targets = AtomicInteger()
    private val worker = Thread {
        while (!server.isClosed) {
            try { server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val first = reader.readLine() ?: return@use
                val path = first.split(" ")[1]
                var authorized = false
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.equals("Authorization: Bearer star_sk_v1_fixture", true)) authorized = true
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                require(length in 0..65536)
                val body = CharArray(length)
                var read = 0
                while (read < length) { val count = reader.read(body, read, length-read); if (count < 0) break; read += count }
                val status = if (authorized || path == "/auth/login") 200 else 403
                val payload = when {
                    status == 403 -> "{}"
                    path == "/auth/login" -> """{"api_key":"star_sk_v1_fixture","user":{"username":"fixture"}}"""
                    path == "/auth/context" -> """{"status":"ok","scopes":["actors:read","targets:dispatch"]}"""
                    path == "/v1/actors" -> """{"status":"ok","data":{"schema":"starintel-actor-registry-v1","actors":[{"resourceUri":"star://biz/actor/fixture","resourceKind":"actor","operatorVisible":true,"semantic":{"name":"fixture","version":"1","digest":"fixture"},"accepts":{"targets":["target"],"documents":[],"messages":[]},"produces":{"targets":[],"documents":["document"],"messages":[]},"capabilities":["targets:dispatch"],"provenance":{"sourcePackage":"fixture"},"status":"online","ready":true}],"count":1}}"""
                    path == "/api/v1/stats" -> """{"status":"ok","data":{"documents":{"total":142},"targets":{"total":1}}}"""
                    path == "/api/v1/targets" -> {
                        val target = JSONObject(String(body))
                        require(target.getString("actor") == "fixture" && target.getString("target") == "sample")
                        require(target.getString("dataset") == "test" && target.getString("idempotency_key").isNotBlank())
                        targets.incrementAndGet()
                        """{"status":"ok","id":"target-fixture"}"""
                    }
                    else -> "{}"
                }
                val bytes = payload.toByteArray()
                socket.getOutputStream().write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                socket.getOutputStream().write(bytes)
            } } catch (failure: Exception) { if (!server.isClosed) throw failure }
        }
    }.apply { start() }
    override fun close() { server.close(); worker.join(3000) }
}
