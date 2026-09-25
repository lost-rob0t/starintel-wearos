package actor.starintel.wear.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SearchHit(
    val id: String,
    val title: String,
    val secondary: String,
)

data class SearchResult(
    val hits: List<SearchHit> = emptyList(),
    val totalRows: Long? = null,
    val bookmark: String? = null,
    val error: String? = null,
)

class StarIntelSearchClient private constructor(context: Context) {
    private val repository = StarIntelRepository.get(context.applicationContext)
    private val apiKeyStore = ApiKeyStore(context.applicationContext)

    suspend fun search(query: String, limit: Int = DEFAULT_RESULTS): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResult(error = "Search query required")
        if (trimmed.length > MAX_QUERY_LENGTH) return@withContext SearchResult(error = "Search query too long")

        val baseUrl = repository.baseUrl()
        val apiKey = apiKeyStore.read()
        if (baseUrl.isBlank() || apiKey.isNullOrBlank()) {
            return@withContext SearchResult(error = "StarIntel is not configured")
        }

        val requestedLimit = limit.coerceIn(1, MAX_RESULTS)
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        var lastFailure: Throwable? = null

        // Search latency and response size increase with the requested page size.
        // Retry the same query with smaller pages on bounded, transient failures.
        retryLimits(requestedLimit).forEach { boundedLimit ->
            val url = "$baseUrl/api/v1/search?q=$encoded&limit=$boundedLimit"
            try {
                return@withContext parseSearchPayload(fetch(url, apiKey), boundedLimit)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                lastFailure = failure
                if (!shouldRetrySearch(failure)) {
                    return@withContext SearchResult(error = safeError(failure))
                }
            }
        }

        SearchResult(error = safeError(lastFailure ?: IllegalStateException(RESPONSE_TOO_LARGE)))
    }

    private fun fetch(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "starintel-wearos/0.4")

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException(
                    when (code) {
                        400 -> "HTTP 400 invalid search"
                        401 -> "HTTP 401 unauthorized"
                        403 -> "HTTP 403 forbidden"
                        else -> "HTTP $code"
                    },
                )
            }
            if (connection.contentLengthLong > StarIntelRepository.MAX_RESPONSE_BYTES) {
                throw IllegalStateException(RESPONSE_TOO_LARGE)
            }

            connection.inputStream.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8_192)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > StarIntelRepository.MAX_RESPONSE_BYTES) {
                        throw IllegalStateException(RESPONSE_TOO_LARGE)
                    }
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun safeError(failure: Throwable): String = when {
        failure.message?.startsWith("HTTP ") == true -> failure.message!!
        failure.message == RESPONSE_TOO_LARGE -> "Search results are too large for the watch; narrow the query"
        failure is SocketTimeoutException -> "Search timed out; try a narrower query"
        else -> "Search unavailable"
    }

    companion object {
        private const val MAX_QUERY_LENGTH = 512
        private const val DEFAULT_RESULTS = 16
        private const val MAX_RESULTS = 50
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 6_000
        private const val RESPONSE_TOO_LARGE = "Search response too large"

        internal fun retryLimits(requested: Int): List<Int> = buildList {
            listOf(requested, minOf(requested, 16), minOf(requested, 8), minOf(requested, 4))
                .distinct()
                .forEach(::add)
        }

        internal fun shouldRetrySearch(failure: Exception): Boolean =
            failure is SocketTimeoutException ||
                failure.message == RESPONSE_TOO_LARGE ||
                (failure.message?.removePrefix("HTTP ")?.toIntOrNull()?.let { it in 500..599 } == true)

        @Volatile private var instance: StarIntelSearchClient? = null

        fun get(context: Context): StarIntelSearchClient =
            instance ?: synchronized(this) {
                instance ?: StarIntelSearchClient(context).also { instance = it }
            }
    }
}

internal fun parseSearchPayload(raw: String, resultLimit: Int = 20): SearchResult {
    val root = JSONObject(raw)
    val rows = root.optJSONArray("rows") ?: JSONArray()
    val limit = resultLimit.coerceIn(1, 50)
    val hits = buildList {
        for (index in 0 until minOf(rows.length(), limit)) {
            val row = rows.optJSONObject(index) ?: continue
            val doc = row.optJSONObject("doc") ?: row
            val data = doc.optJSONObject("data")
            val id = stringValue(row, "id")
                ?: stringValue(doc, "_id")
                ?: "result-${index + 1}"
            val title = firstString(doc, "name", "title", "username", "handle", "url")
                ?: data?.let {
                    firstString(
                        it,
                        "name",
                        "title",
                        "username",
                        "handle",
                        "url",
                        "target",
                        "address",
                        "domain",
                        "hostname",
                    )
                }
                ?: stringValue(doc, "_id")
                ?: id
            val secondary = listOfNotNull(
                stringValue(doc, "dtype"),
                stringValue(doc, "dataset"),
                stringValue(doc, "source") ?: data?.let { stringValue(it, "source") },
                data?.let { stringValue(it, "platform") },
                data?.let { stringValue(it, "actor") },
            ).distinct().joinToString(" · ")
            add(
                SearchHit(
                    id = id.take(256),
                    title = title.take(120),
                    secondary = secondary.take(160),
                ),
            )
        }
    }

    val totalRows = when {
        root.has("total_rows") && !root.isNull("total_rows") -> {
            val value = root.get("total_rows")
            (value as? Number)?.toLong()?.takeIf { it >= 0L }
        }
        else -> null
    }
    val bookmark = stringValue(root, "bookmark")
    return SearchResult(hits = hits, totalRows = totalRows, bookmark = bookmark)
}

private fun firstString(obj: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { stringValue(obj, it) }

private fun stringValue(obj: JSONObject, key: String): String? {
    if (!obj.has(key) || obj.isNull(key)) return null
    val value = obj.get(key)
    return (value as? String)?.trim()?.takeIf { it.isNotBlank() }
}
