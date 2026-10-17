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

    /** Registry projection is authorized by the server; private implementations stay there. */
    fun actors(): JSONArray {
        val data = request("GET", "/v1/actors").getJSONObject("data")
        val actors = data.getJSONArray("actors")
        require(actors.length() <= 1_024) { "Actor registry exceeded its bound" }
        return actors
    }

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

    /** Compatibility snapshot API. Use searchPage to preserve pagination metadata. */
    fun search(query: String, limit: Int = 40): JSONArray = searchPage(query, limit).rows

    fun searchPage(query: String, limit: Int = 40, cursor: String? = null): SearchPage {
        require(query.isNotBlank()) { "Search query required" }
        require(query.length <= 512) { "Search query too long" }
        SearchPage.validateCursor(cursor)
        val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
        val bounded = limit.coerceIn(1, 100)
        val bookmark = cursor?.let { "&bookmark=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }.orEmpty()
        val root = runCatching {
            request("GET", "/api/v1/documents/search?q=$encoded&limit=$bounded$bookmark")
        }.recoverCatching { failure ->
            if (failure !is StarHttpFailure || failure.status !in listOf(404, 405)) throw failure
            request("GET", "/api/v1/search?q=$encoded&limit=$bounded$bookmark")
        }.getOrThrow()
        return SearchPage.fromJson(root, bounded, cursor)
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

    /** Freeze credentials/origin and verify the principal before an explicitly started import. */
    fun openBulkImportSession(): BulkImportSession {
        val session = sessionProvider() ?: error("Configure a Star server first")
        val server = normalizeServer(session.serverUrl)
        val token = session.apiKey.also(::requireApiKey)
        val context = requestAt(server, token, "GET", "/auth/context")
        val principal = context.opt("principal_id")
        val type = context.opt("principal_type")
        require(principal is String && principal.isNotBlank() && principal.length <= 512 &&
            type is String && type.isNotBlank() && type.length <= 128) { "Import requires a verified principal" }
        val identity = JSONArray().put(server).put(type).put(principal).toString()
        return BulkImportSession(identity) { body ->
            require(body.size in 2..BulkImportSession.MAX_REQUEST_BYTES) { "Bulk request exceeds UTF-8 byte bound" }
            val documents = JSONArray(body.toString(Charsets.UTF_8))
            require(documents.length() in 1..10) { "Import batches require 1–10 records" }
            for (index in 0 until documents.length()) documents.getJSONObject(index)
            val connection = URL("$server/api/v1/documents/bulk").openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("User-Agent", "starintel-android/$clientVersion")
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true
                // Streaming mode prevents HttpURLConnection from buffering/replaying a failed POST.
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                if (status !in 200..299) throw StarHttpFailure(status, "Import stopped; no automatic retry")
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = input.read(buffer, 0, minOf(buffer.size, BulkImportSession.MAX_RECEIPT_BYTES + 1 - output.size()))
                        if (count < 0) break
                        if (output.size() + count > BulkImportSession.MAX_RECEIPT_BYTES) error("Bulk receipt exceeds byte bound")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    }

    fun createTarget(actor: String, target: String, dataset: String): JSONObject {
        val cleanActor = actor.trim()
        val cleanTarget = target.trim()
        val cleanDataset = dataset.trim()
        require(cleanActor.isNotBlank()) { "Actor required" }
        require(cleanTarget.isNotBlank()) { "Target required" }
        require(cleanDataset.isNotBlank()) { "Dataset required" }
        val body = JSONObject()
                .put("actor", cleanActor)
                .put("target", cleanTarget)
                .put("dataset", cleanDataset)
                .put("delay", 1)
                .put("recurring", false)
                .put("options", JSONObject())
                .put("idempotency_key", "android-${UUID.randomUUID()}")
        return runCatching { request("POST", "/api/v1/targets", body) }
            .recoverCatching { failure ->
                if (failure !is StarHttpFailure || failure.status !in listOf(404, 405)) throw failure
                val encoded = URLEncoder.encode(cleanActor, StandardCharsets.UTF_8.name()).replace("+", "%20")
                request("POST", "/new/target/$encoded", body)
            }.getOrThrow()
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
            connection.instanceFollowRedirects = false
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
        val origin = java.net.URI(server)
        require(origin.host != null && origin.rawUserInfo == null && origin.rawQuery == null &&
            origin.rawFragment == null && (origin.rawPath.isNullOrEmpty() || origin.rawPath == "/")) {
            "Use a server origin without credentials, path, query, or fragment"
        }
        require(origin.scheme == "https" || (allowCleartext && origin.scheme == "http")) {
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
