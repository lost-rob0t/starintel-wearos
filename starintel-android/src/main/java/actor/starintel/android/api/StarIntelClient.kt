package actor.starintel.android.api

import actor.starintel.android.model.AgentTurnRequest
import actor.starintel.android.model.AgentTurnResult
import actor.starintel.android.model.Endpoint
import actor.starintel.android.model.LoginResult
import actor.starintel.android.model.StarSession
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class StarIntelClient(
    private val sessionProvider: () -> StarSession?,
    private val clientVersion: String,
    private val allowCleartext: Boolean = false,
) {
    fun health(): JSONObject = request("GET", "/health")

    fun stats(): JSONObject = request("GET", "/api/v1/stats")

    fun login(serverUrl: String, username: String, password: String): LoginResult {
        require(username.trim().isNotBlank()) { "Username required" }
        require(password.isNotEmpty()) { "Password required" }
        return parseLoginResponse(
            requestAt(
                server = normalizeServer(serverUrl),
                token = null,
                method = "POST",
                path = "/auth/login",
                body = JSONObject().put("username", username.trim()).put("password", password),
            ),
        )
    }

    fun authContext(serverUrl: String, apiKey: String): JSONObject {
        requireApiKey(apiKey)
        return requestAt(normalizeServer(serverUrl), apiKey, "GET", "/auth/context")
    }

    fun capabilities(): List<Endpoint> {
        val root = request("GET", "/api/v1/capabilities")
        val values = root.optJSONObject("data")?.optJSONArray("endpoints") ?: JSONArray()
        return buildList {
            for (index in 0 until values.length().coerceAtMost(1_024)) {
                val item = values.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val method = item.optString("method")
                val path = item.optString("path")
                if (id.isNotBlank() && method.isNotBlank() && path.startsWith('/')) {
                    add(Endpoint(id, method, path, item.optBoolean("legacy", false)))
                }
            }
        }
    }

    fun search(query: String, limit: Int = 40): JSONArray {
        require(query.isNotBlank()) { "Search query required" }
        require(query.length <= 512) { "Search query too long" }
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val bounded = limit.coerceIn(1, 100)
        val root = runCatching {
            request("GET", "/api/v1/documents/search?q=$encoded&limit=$bounded")
        }.recoverCatching { failure ->
            if (failure !is StarHttpFailure || failure.status !in listOf(404, 405)) throw failure
            request("GET", "/api/v1/search?q=$encoded&limit=$bounded")
        }.getOrThrow()
        return root.optJSONArray("rows")
            ?: root.optJSONObject("data")?.optJSONArray("rows")
            ?: root.optJSONArray("documents")
            ?: JSONArray()
    }

    fun document(id: String): JSONObject {
        val clean = id.trim()
        require(clean.isNotBlank() && clean.length <= 512) { "Invalid document id" }
        val encoded = URLEncoder.encode(clean, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return runCatching { request("GET", "/api/v1/documents/$encoded") }
            .recoverCatching { failure ->
                if (failure !is StarHttpFailure || failure.status !in listOf(404, 405)) throw failure
                request("GET", "/document/$encoded")
            }.getOrThrow()
    }

    fun createDocument(document: JSONObject): JSONObject = request("POST", "/api/v1/documents", document)

    fun bulkCreate(documents: JSONArray): JSONObject = request("POST", "/api/v1/documents/bulk", documents)

    fun createTarget(actor: String, target: String, dataset: String): JSONObject {
        val cleanActor = actor.trim()
        val cleanTarget = target.trim()
        val cleanDataset = dataset.trim()
        require(cleanActor.isNotBlank()) { "Actor required" }
        require(cleanTarget.isNotBlank()) { "Target required" }
        require(cleanDataset.isNotBlank()) { "Dataset required" }
        return request(
            "POST",
            "/api/v1/targets",
            JSONObject()
                .put("actor", cleanActor)
                .put("target", cleanTarget)
                .put("dataset", cleanDataset)
                .put("delay", 1)
                .put("recurring", false)
                .put("options", JSONArray())
                .put("idempotency_key", "quasar-android-${UUID.randomUUID()}"),
        )
    }

    fun prologRlmTurn(request: AgentTurnRequest): AgentTurnResult = AgentTurnResult.fromJson(
        request("POST", "/api/v1/agents/prolog-rlm/turn", request.toJson()),
    )

    private fun request(method: String, path: String, body: Any? = null): JSONObject {
        val session = sessionProvider() ?: error("Configure a Star server first")
        return requestAt(
            normalizeServer(session.serverUrl),
            session.apiKey.also(::requireApiKey),
            method,
            path,
            body,
        )
    }

    private fun requestAt(
        server: String,
        token: String?,
        method: String,
        path: String,
        body: Any? = null,
    ): JSONObject {
        require(path.startsWith('/') && !path.startsWith("//")) { "Invalid API path" }
        val connection = URL("$server$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 8_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "starintel-android/$clientVersion")
            if (!token.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val reader = (if (status in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()
            val text = readBounded(reader)
            if (status !in 200..299) throw StarHttpFailure(status, safeMessage(text))
            if (text.isBlank()) JSONObject().put("status", "ok") else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(reader: BufferedReader?): String {
        if (reader == null) return ""
        reader.use {
            val output = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.length + count > MAX_RESPONSE_CHARS) error("Server response exceeded 2 MiB")
                output.append(buffer, 0, count)
            }
            return output.toString()
        }
    }

    private fun safeMessage(text: String): String = runCatching {
        val root = JSONObject(text)
        root.optString("msg").ifBlank { root.optString("message") }.take(200)
    }.getOrDefault("")

    private fun normalizeServer(value: String): String {
        val server = value.trim().trimEnd('/')
        require(server.startsWith("https://") || (allowCleartext && server.startsWith("http://"))) {
            if (allowCleartext) "Use an HTTP or HTTPS server URL" else "Release clients require HTTPS"
        }
        return server
    }

    private fun requireApiKey(apiKey: String) {
        require(apiKey.startsWith("star_sk_v1_") && apiKey.length <= 4096) { "Invalid StarIntel API key" }
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}

fun parseLoginResponse(root: JSONObject): LoginResult {
    val apiKey = root.optString("api_key")
    require(apiKey.startsWith("star_sk_v1_") && apiKey.length <= 4096) {
        "Login response did not contain a valid API key"
    }
    val user = root.optJSONObject("user") ?: JSONObject()
    return LoginResult(
        apiKey = apiKey,
        username = user.optString("username"),
        mustChangePassword = user.optBoolean("must_change_password", false),
    )
}

class StarHttpFailure(val status: Int, detail: String) :
    RuntimeException("HTTP $status${detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}")

fun resultDocument(row: JSONObject): JSONObject = row.optJSONObject("doc") ?: row

fun resultId(row: JSONObject): String {
    val document = resultDocument(row)
    return row.optString("id").ifBlank { document.optString("_id") }.ifBlank { "unknown" }
}
