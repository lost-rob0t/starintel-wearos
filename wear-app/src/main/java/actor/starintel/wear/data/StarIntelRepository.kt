package actor.starintel.wear.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StarIntelSnapshot(
    val configured: Boolean,
    val reachable: Boolean,
    val service: String = "starintel-gserver",
    val version: String = "?",
    val generatedAt: Long = 0L,
    val receivedAt: Long = 0L,
    val documentsTotal: Long = 0L,
    val documentsByType: Map<String, Long> = emptyMap(),
    val targetsTotal: Long = 0L,
    val stale: Boolean = false,
    val error: String? = null,
) {
    val targetCount: Long get() = documentsByType["target"] ?: 0L
    val investigationTargetCount: Long get() = documentsByType["investigation-target"] ?: 0L
}

class StarIntelRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val activityHistory = ActivityHistoryStore.get(appContext)

    fun baseUrl(): String = prefs.getString(KEY_BASE_URL, "")?.trim().orEmpty()

    fun setBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        val changed = normalized != baseUrl()
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        if (changed) activityHistory.clear()
    }

    fun hasApiKey(): Boolean = apiKeyStore.hasValue()

    fun setApiKey(value: String) {
        val normalized = value.trim()
        val changed = normalized != apiKeyStore.read()
        apiKeyStore.save(normalized)
        if (changed) activityHistory.clear()
    }

    fun clearApiKey() {
        apiKeyStore.clear()
        prefs.edit().remove(KEY_STATS_JSON).remove(KEY_RECEIVED_AT).apply()
        activityHistory.clear()
    }

    fun commitConfiguration(baseUrl: String, apiKey: String?): Boolean {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val candidateKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
        val previousBase = this.baseUrl()
        val previousKey = apiKeyStore.read()

        return runCatching {
            if (candidateKey != null) {
                apiKeyStore.save(candidateKey)
            }
            check(prefs.edit().putString(KEY_BASE_URL, normalizedBase).commit()) {
                "Could not persist server URL"
            }
            prefs.edit().remove(KEY_STATS_JSON).remove(KEY_RECEIVED_AT).apply()
            if (normalizedBase != previousBase || (candidateKey != null && candidateKey != previousKey)) {
                activityHistory.clear()
            }
            true
        }.getOrElse {
            runCatching {
                if (previousKey.isNullOrBlank()) {
                    if (apiKeyStore.hasValue()) apiKeyStore.clear()
                } else {
                    apiKeyStore.save(previousKey)
                }
                val editor = prefs.edit()
                if (previousBase.isBlank()) editor.remove(KEY_BASE_URL) else editor.putString(KEY_BASE_URL, previousBase)
                editor.commit()
            }
            false
        }
    }

    suspend fun testConnection(baseUrl: String, apiKey: String): StarIntelSnapshot = withContext(Dispatchers.IO) {
        testConnectionInternal(baseUrl, apiKey)
    }

    suspend fun testStoredConnection(baseUrl: String): StarIntelSnapshot = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.read()
        if (apiKey.isNullOrBlank()) {
            StarIntelSnapshot(
                configured = false,
                reachable = false,
                error = "API key required",
            )
        } else {
            testConnectionInternal(baseUrl, apiKey)
        }
    }

    private fun testConnectionInternal(baseUrl: String, apiKey: String): StarIntelSnapshot {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val normalizedKey = apiKey.trim()
        val now = System.currentTimeMillis()
        return runCatching {
            val raw = get("$normalizedBase/api/v1/stats", normalizedKey)
            parse(raw, now, now)
        }.getOrElse { failure ->
            StarIntelSnapshot(
                configured = true,
                reachable = false,
                stale = true,
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    suspend fun snapshot(forceRefresh: Boolean = false): StarIntelSnapshot = withContext(Dispatchers.IO) {
        val base = baseUrl()
        val apiKey = apiKeyStore.read()
        if (base.isBlank() || apiKey.isNullOrBlank()) {
            return@withContext StarIntelSnapshot(
                configured = false,
                reachable = false,
                error = if (base.isBlank()) "Server URL required" else "API key required",
            )
        }

        val now = System.currentTimeMillis()
        val cachedRaw = prefs.getString(KEY_STATS_JSON, null)
        val cachedAt = prefs.getLong(KEY_RECEIVED_AT, 0L)
        val cached = cachedRaw?.let { runCatching { parse(it, cachedAt, now) }.getOrNull() }

        if (!forceRefresh && cached != null && now - cachedAt < CACHE_MS) {
            return@withContext cached
        }

        runCatching {
            val raw = get("$base/api/v1/stats", apiKey)
            val parsed = parse(raw, now, now)
            prefs.edit()
                .putString(KEY_STATS_JSON, raw)
                .putLong(KEY_RECEIVED_AT, now)
                .apply()
            activityHistory.record(parsed)
            parsed
        }.getOrElse { failure ->
            cached?.copy(
                reachable = false,
                stale = true,
                error = failure.message ?: failure.javaClass.simpleName,
            ) ?: StarIntelSnapshot(
                configured = true,
                reachable = false,
                stale = true,
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    private fun get(url: String, apiKey: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "starintel-wearos/0.3")

            val code = connection.responseCode
            if (code !in 200..299) {
                val message = when (code) {
                    401 -> "HTTP 401 unauthorized"
                    403 -> "HTTP 403 forbidden"
                    else -> "HTTP $code"
                }
                throw IllegalStateException(message)
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String, receivedAt: Long, now: Long): StarIntelSnapshot {
        val root = JSONObject(raw)
        val status = root.optString("status", "")
        val data = root.optJSONObject("data") ?: error("missing data")
        val documents = data.optJSONObject("documents") ?: JSONObject()
        val byTypeJson = documents.optJSONObject("by_dtype") ?: JSONObject()
        val byType = buildMap {
            val keys = byTypeJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, byTypeJson.optLong(key, 0L))
            }
        }

        val generatedAt = data.optLong("generated_at", 0L)
        val generatedAtMs = if (generatedAt > 0L) generatedAt * 1000L else receivedAt
        val stale = now - generatedAtMs > STALE_MS

        return StarIntelSnapshot(
            configured = true,
            reachable = status.equals("ok", ignoreCase = true),
            service = data.optString("service", "starintel-gserver"),
            version = data.optString("version", "?"),
            generatedAt = generatedAt,
            receivedAt = receivedAt,
            documentsTotal = documents.optLong("total", 0L),
            documentsByType = byType,
            targetsTotal = data.optJSONObject("targets")?.optLong("total", 0L) ?: 0L,
            stale = stale,
        )
    }

    companion object {
        private const val PREFS = "starintel_wear"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_STATS_JSON = "stats_json"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val CACHE_MS = 60_000L
        private const val STALE_MS = 15 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000

        @Volatile
        private var instance: StarIntelRepository? = null

        fun get(context: Context): StarIntelRepository =
            instance ?: synchronized(this) {
                instance ?: StarIntelRepository(context).also { instance = it }
            }
    }
}

fun Long.compactCount(): String = when {
    this >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", this / 1_000_000_000.0)
    this >= 1_000_000L -> String.format(Locale.US, "%.1fM", this / 1_000_000.0)
    this >= 1_000L -> String.format(Locale.US, "%.1fK", this / 1_000.0)
    else -> toString()
}

fun StarIntelSnapshot.ageLabel(nowMs: Long = System.currentTimeMillis()): String {
    if (receivedAt <= 0L) return "no data"
    val seconds = ((nowMs - receivedAt).coerceAtLeast(0L) / 1000L)
    return when {
        seconds < 60 -> "${seconds}s ago"
        seconds < 3600 -> "${seconds / 60}m ago"
        else -> "${seconds / 3600}h ago"
    }
}
