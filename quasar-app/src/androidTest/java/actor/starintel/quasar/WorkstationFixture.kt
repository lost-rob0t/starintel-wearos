package actor.starintel.quasar

import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

/** Procedural million-record corpus: only requested rows are generated or retained. */
internal class WorkstationFixture : AutoCloseable {
    private val server = ServerSocket(0)
    private val workers = Executors.newFixedThreadPool(4)
    val origin = "http://127.0.0.1:${server.localPort}"
    val maxRequested = AtomicInteger()
    val slowRequested = java.util.concurrent.CountDownLatch(1)
    @Volatile var searchStatus = 200
    @Volatile var delayMillis = 0L
    private val acceptor = Thread {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: Exception) { break }
            workers.submit {
                runCatching { socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(" ")?.get(1) ?: return@use
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val search = path.contains("search?")
                    val params = path.substringAfter('?', "").split('&').associate { pair -> pair.substringBefore('=') to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }
                    val requestedLimit = params["limit"]?.toIntOrNull() ?: 40
                    val limit = requestedLimit.coerceIn(1, 100)
                    if (search && params["q"] == "slow") { slowRequested.countDown(); Thread.sleep(700) }
                    if (search) { maxRequested.updateAndGet { current -> maxOf(current, requestedLimit) }; Thread.sleep(delayMillis) }
                    val status = if (search) searchStatus else 200
                    val payload = when {
                        search -> JSONObject().put("rows", JSONArray().apply {
                            if (params["q"] != "empty") repeat(limit) { index -> put(JSONObject().put("id", "fixture-$index").put("doc", document(index))) }
                        }).put("fixture_corpus_size", 1_000_000)
                        path.startsWith("/api/v1/documents/") -> JSONObject().put("document", document(path.substringAfterLast('-').toIntOrNull() ?: 0))
                        path == "/api/v1/stats" -> JSONObject("""{"data":{"documents":{"total":1000000},"targets":{"total":1}}}""")
                        path == "/v1/actors" -> JSONObject("""{"data":{"actors":[{"resourceUri":"star://fixture/actor/reconnaissance","operatorVisible":true,"semantic":{"name":"fixture-reconnaissance"},"status":"online","ready":true,"accepts":{"targets":["target"]}}]}}""")
                        else -> JSONObject().put("status", "ok")
                    }
                    val bytes = payload.toString().toByteArray()
                    it.getOutputStream().write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().write(bytes)
                } }
            }
        }
    }.apply { name = "quasar-workstation-fixture"; start() }
    private fun document(index: Int) = JSONObject().put("_id", "fixture-$index").put("dtype", "location")
        .put("name", "Fixture record $index").put("source", "Synthetic field exercise")
        .put("latitude", 51.5 + index * .001).put("longitude", -.12 + index * .001)
        .put("summary", "Synthetic observation for response planning. Confirm provenance before acting.")
        .put("data", JSONObject().put("name", "Fixture record $index")
            .put("summary", "Synthetic observation for response planning. Confirm provenance before acting.")
            .put("latitude", 51.5 + index * .001).put("longitude", -.12 + index * .001))
    override fun close() { server.close(); workers.shutdownNow(); acceptor.join(2000) }
}
