package actor.starintel.wear.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CapabilityEndpoint(
    val id: String,
    val method: String,
    val path: String,
    val legacy: Boolean,
)

data class StarIntelCapabilities(
    val endpoints: List<CapabilityEndpoint>,
    val legacyRoutes: Boolean,
) {
    fun endpoint(method: String, path: String): CapabilityEndpoint? =
        endpoints.firstOrNull { it.method.equals(method, true) && it.path == path }

    fun endpointById(id: String): CapabilityEndpoint? = endpoints.firstOrNull { it.id == id }
}

data class DocumentResult(
    val id: String? = null,
    val json: JSONObject? = null,
    val error: String? = null,
)

data class TargetCreateResult(
    val accepted: Boolean,
    val duplicate: Boolean = false,
    val targetId: String? = null,
    val requestId: String? = null,
    val error: String? = null,
)

class StarIntelApiClient private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val repository = StarIntelRepository.get(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)

    suspend fun capabilities(): StarIntelCapabilities? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = request("GET", "/api/v1/capabilities").body
            parseCapabilities(raw)
        }.getOrNull()
    }

    suspend fun document(id: String): DocumentResult = withContext(Dispatchers.IO) {
        val clean = id.trim()
        if (clean.isBlank()) return@withContext DocumentResult(error = "Document id required")
        if (clean.length > MAX_ID_LENGTH) return@withContext DocumentResult(error = "Document id too long")

        runCatching {
            val caps = capabilities()
            val advertised = caps?.endpointById("document_read")?.path ?: "/document/:id"
            val path = advertised.replace(":id", encodePathSegment(clean))
            val response = request("GET", path)
            DocumentResult(id = clean, json = JSONObject(response.body))
        }.getOrElse { failure ->
            DocumentResult(id = clean, error = safeError(failure))
        }
    }

    suspend fun createTarget(
        actor: String,
        target: String,
        dataset: String,
        options: JSONArray = JSONArray(),
    ): TargetCreateResult = withContext(Dispatchers.IO) {
        val cleanActor = actor.trim()
        val cleanTarget = target.trim()
        val cleanDataset = dataset.trim()
        when {
            cleanActor.isBlank() -> return@withContext TargetCreateResult(false, error = "Actor required")
            cleanTarget.isBlank() -> return@withContext TargetCreateResult(false, error = "Target required")
            cleanDataset.isBlank() -> return@withContext TargetCreateResult(false, error = "Dataset required")
            cleanActor.length > 128 -> return@withContext TargetCreateResult(false, error = "Actor too long")
            cleanTarget.length > 2_048 -> return@withContext TargetCreateResult(false, error = "Target too long")
            cleanDataset.length > 256 -> return@withContext TargetCreateResult(false, error = "Dataset too long")
            options.length() > 64 -> return@withContext TargetCreateResult(false, error = "Too many target options")
        }

        val body = JSONObject()
            .put("actor", cleanActor)
            .put("target", cleanTarget)
            .put("dataset", cleanDataset)
            .put("idempotency_key", "wear-${UUID.randomUUID()}")
            .put("delay", 1)
            .put("recurring", false)
            .put("options", options)

        runCatching {
            val caps = capabilities()
            val canonical = caps?.endpoints?.firstOrNull {
                it.method.equals("POST", true) && it.path == "/api/v1/targets"
            }

            val response = if (canonical != null) {
                request("POST", canonical.path, body)
            } else {
                // Current StarIntel servers expose the stable v1 target route even
                // before every capability document advertises it. Prefer it, then
                // fall back only on an explicit route-missing response and only
                // when legacy target creation is advertised.
                try {
                    request("POST", "/api/v1/targets", body)
                } catch (failure: HttpFailure) {
                    val legacy = caps?.endpointById("target_create")
                    if (failure.status !in listOf(404, 405) || legacy == null) throw failure
                    val legacyBody = JSONObject()
                        .put("actor", cleanActor)
                        .put("target", cleanTarget)
                        .put("dataset", cleanDataset)
                        .put("options", options)
                    val path = legacy.path.replace(":actor", encodePathSegment(cleanActor))
                    request("POST", path, legacyBody)
                }
            }

            val parsed = JSONObject(response.body)
            val status = parsed.optString("status")
            TargetCreateResult(
                accepted = status == "accepted" || status == "duplicate" || response.status in 200..299,
                duplicate = status == "duplicate",
                targetId = parsed.optString("target_id").takeIf { it.isNotBlank() }
                    ?: parsed.optString("_id").takeIf { it.isNotBlank() },
                requestId = parsed.optString("request_id").takeIf { it.isNotBlank() },
            )
        }.getOrElse { failure ->
            TargetCreateResult(false, error = safeError(failure))
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): HttpResponse {
        val baseUrl = repository.baseUrl()
        val apiKey = apiKeyStore.read()
        if (baseUrl.isBlank() || apiKey.isNullOrBlank()) {
            throw IllegalStateException("StarIntel is not configured")
        }

        val connection = URL("$baseUrl$path").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "starintel-wearos/0.4")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = readBounded(stream?.bufferedReader())
            if (status !in 200..299) throw HttpFailure(status, responseBody)
            HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(reader: java.io.BufferedReader?): String {
        if (reader == null) return ""
        reader.use {
            val builder = StringBuilder()
            val buffer = CharArray(8_192)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > StarIntelRepository.MAX_RESPONSE_BYTES) {
                    throw IllegalStateException("Response too large")
                }
                builder.append(buffer, 0, read)
            }
            return builder.toString()
        }
    }

    private fun safeError(failure: Throwable): String = when (failure) {
        is HttpFailure -> {
            val detail = errorDetail(failure.body)
            when (failure.status) {
                400, 422 -> detail ?: "HTTP ${failure.status} invalid request"
                401 -> "HTTP 401 unauthorized"
                403 -> "HTTP 403 forbidden"
                404 -> "HTTP 404 not found"
                409 -> detail ?: "HTTP 409 conflict"
                else -> detail?.let { "HTTP ${failure.status} · $it" } ?: "HTTP ${failure.status}"
            }
        }
        else -> failure.message?.takeIf { it == "StarIntel is not configured" }
            ?: "StarIntel request unavailable"
    }

    private fun errorDetail(raw: String): String? = runCatching {
        val root = JSONObject(raw)
        val error = root.optJSONObject("error")
        listOfNotNull(
            error?.optString("message")?.takeIf { it.isNotBlank() },
            root.optString("message").takeIf { it.isNotBlank() },
            root.optString("reason").takeIf { it.isNotBlank() },
        ).firstOrNull()?.take(180)
    }.getOrNull()

    companion object {
        private const val MAX_ID_LENGTH = 512
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 8_000

        @Volatile private var instance: StarIntelApiClient? = null

        fun get(context: Context): StarIntelApiClient =
            instance ?: synchronized(this) {
                instance ?: StarIntelApiClient(context).also { instance = it }
            }
    }
}

private data class HttpResponse(val status: Int, val body: String)
private class HttpFailure(val status: Int, val body: String) : RuntimeException("HTTP $status")

internal fun parseCapabilities(raw: String): StarIntelCapabilities {
    val root = JSONObject(raw)
    val data = root.optJSONObject("data") ?: JSONObject()
    val endpointsJson = data.optJSONArray("endpoints") ?: JSONArray()
    val endpoints = buildList {
        for (index in 0 until endpointsJson.length()) {
            val item = endpointsJson.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val method = item.optString("method")
            val path = item.optString("path")
            if (id.isBlank() || method.isBlank() || path.isBlank()) continue
            add(
                CapabilityEndpoint(
                    id = id,
                    method = method,
                    path = path,
                    legacy = item.optBoolean("legacy", false),
                ),
            )
        }
    }
    val legacyRoutes = data.optJSONObject("compatibility")?.optBoolean("legacy_routes", false) ?: false
    return StarIntelCapabilities(endpoints, legacyRoutes)
}

private fun encodePathSegment(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
