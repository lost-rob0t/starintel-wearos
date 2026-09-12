package actor.starintel.wear.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    val malformed: Boolean = false,
    val source: SnapshotSource = SnapshotSource.NONE,
    val error: String? = null,
) {
    val targetCount: Long get() = documentsByType["target"] ?: 0L
    val investigationTargetCount: Long get() = documentsByType["investigation-target"] ?: 0L
}

enum class SnapshotSource { LIVE, CACHED, NONE }

interface StatsStore {
    fun getString(key: String, def: String?): String?
    fun getLong(key: String, def: Long): Long
    fun edit(): StatsEditor
}

interface StatsEditor {
    fun putString(key: String, value: String?): StatsEditor
    fun putLong(key: String, value: Long): StatsEditor
    fun remove(key: String): StatsEditor
    fun apply()
    fun commit(): Boolean
}

interface SecretStore {
    fun hasValue(): Boolean
    fun save(value: String)
    fun read(): String?
    fun clear()
}

interface ActivityHistoryRecorder {
    fun record(snapshot: StarIntelSnapshot)
    fun clear()
}

class PrefsStatsStore(private val prefs: android.content.SharedPreferences) : StatsStore {
    override fun getString(key: String, def: String?): String? = prefs.getString(key, def)
    override fun getLong(key: String, def: Long): Long = prefs.getLong(key, def)
    override fun edit(): StatsEditor = PrefsStatsEditor(prefs.edit())
}

private class PrefsStatsEditor(private val editor: android.content.SharedPreferences.Editor) : StatsEditor {
    override fun putString(key: String, value: String?): StatsEditor = editor.putString(key, value).let { this }
    override fun putLong(key: String, value: Long): StatsEditor = editor.putLong(key, value).let { this }
    override fun remove(key: String): StatsEditor = editor.remove(key).let { this }
    override fun apply() = editor.apply()
    override fun commit(): Boolean = editor.commit()
}

class ConfigEpoch {
    private val value = AtomicLong(0L)
    fun current(): Long = value.get()
    fun next(): Long = value.incrementAndGet()
    fun matches(epoch: Long): Boolean = value.get() == epoch
}

class StarIntelRepository(
    private val statsStore: StatsStore,
    private val apiKeyStore: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val activityHistory: ActivityHistoryRecorder? = null,
) {
    constructor(context: Context, clock: () -> Long = System::currentTimeMillis) : this(
        statsStore = PrefsStatsStore(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        ),
        apiKeyStore = ApiKeyStore(context.applicationContext),
        clock = clock,
        activityHistory = ActivityHistoryStore.get(context.applicationContext),
    )

    private val refreshMutex = Mutex()
    private val configEpoch = ConfigEpoch()
    private val configLock = Any()

    fun baseUrl(): String = statsStore.getString(KEY_BASE_URL, "")?.trim().orEmpty()

    fun setBaseUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        synchronized(configLock) {
            statsStore.edit().putString(KEY_BASE_URL, normalized).apply()
            invalidateCachedStateLocked()
        }
    }

    fun hasApiKey(): Boolean = apiKeyStore.hasValue()

    fun setApiKey(value: String) {
        synchronized(configLock) {
            apiKeyStore.save(value.trim())
            invalidateCachedStateLocked()
        }
    }

    fun clearApiKey() {
        synchronized(configLock) {
            apiKeyStore.clear()
            invalidateCachedStateLocked()
        }
    }

    fun commitConfiguration(baseUrl: String, apiKey: String?): Boolean {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val candidateKey = apiKey?.trim()?.takeIf { it.isNotBlank() }
        val previousBase = this.baseUrl()
        val previousKey = apiKeyStore.read()

        return runCatching {
            synchronized(configLock) {
                if (candidateKey != null) apiKeyStore.save(candidateKey)
                check(statsStore.edit().putString(KEY_BASE_URL, normalizedBase).commit()) {
                    "Could not persist server URL"
                }
                invalidateCachedStateLocked()
            }
            true
        }.getOrElse {
            runCatching {
                synchronized(configLock) {
                    if (previousKey.isNullOrBlank()) {
                        if (apiKeyStore.hasValue()) apiKeyStore.clear()
                    } else {
                        apiKeyStore.save(previousKey)
                    }
                    val editor = statsStore.edit()
                    if (previousBase.isBlank()) editor.remove(KEY_BASE_URL) else editor.putString(KEY_BASE_URL, previousBase)
                    editor.commit()
                    invalidateCachedStateLocked()
                }
            }
            false
        }
    }

    private fun invalidateCachedStateLocked() {
        configEpoch.next()
        statsStore.edit().remove(KEY_STATS_JSON).remove(KEY_RECEIVED_AT).apply()
        activityHistory?.clear()
    }

    suspend fun testConnection(baseUrl: String, apiKey: String): StarIntelSnapshot = withContext(Dispatchers.IO) {
        testConnectionInternal(baseUrl, apiKey)
    }

    suspend fun testStoredConnection(baseUrl: String): StarIntelSnapshot = withContext(Dispatchers.IO) {
        val apiKey = apiKeyStore.read()
        if (apiKey.isNullOrBlank()) notConfigured("API key required") else testConnectionInternal(baseUrl, apiKey)
    }

    private fun testConnectionInternal(baseUrl: String, apiKey: String): StarIntelSnapshot {
        val now = clock()
        return runCatching {
            parseStatsPayload(
                fetchUrl("${baseUrl.trim().trimEnd('/')}/api/v1/stats", apiKey.trim()),
                now,
                now,
            ).copy(source = SnapshotSource.LIVE)
        }.getOrElse { failure ->
            StarIntelSnapshot(
                configured = true,
                reachable = false,
                stale = true,
                malformed = failure.message?.startsWith("malformed:") == true,
                source = SnapshotSource.NONE,
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    fun cachedSnapshot(): StarIntelSnapshot {
        val base = baseUrl()
        if (base.isBlank() || !apiKeyStore.hasValue()) {
            return notConfigured(if (base.isBlank()) "Server URL required" else "API key required")
        }
        val now = clock()
        val raw = statsStore.getString(KEY_STATS_JSON, null)
            ?: return StarIntelSnapshot(configured = true, reachable = false, source = SnapshotSource.NONE)
        val receivedAt = statsStore.getLong(KEY_RECEIVED_AT, 0L)
        return runCatching {
            parseStatsPayload(raw, receivedAt, now).copy(source = SnapshotSource.CACHED)
        }.getOrElse { failure ->
            StarIntelSnapshot(
                configured = true,
                reachable = false,
                stale = true,
                malformed = true,
                source = SnapshotSource.NONE,
                error = failure.message ?: "Cached stats malformed",
            )
        }
    }

    suspend fun refresh(forceRefresh: Boolean = false): StarIntelSnapshot = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val base = baseUrl()
            val apiKey = apiKeyStore.read()
            if (base.isBlank() || apiKey.isNullOrBlank()) {
                return@withLock notConfigured(if (base.isBlank()) "Server URL required" else "API key required")
            }

            val now = clock()
            val cachedRaw = statsStore.getString(KEY_STATS_JSON, null)
            val cachedAt = statsStore.getLong(KEY_RECEIVED_AT, 0L)
            val cached = cachedRaw?.let { raw ->
                runCatching { parseStatsPayload(raw, cachedAt, now) }.getOrNull()
            }
            if (!forceRefresh && cached != null && now - cachedAt < CACHE_MS) {
                return@withLock cached.copy(source = SnapshotSource.CACHED)
            }

            val epoch = configEpoch.current()
            runCatching {
                val raw = fetchUrl("$base/api/v1/stats", apiKey)
                val parsed = parseStatsPayload(raw, now, now)
                if (!parsed.reachable) error("stats status is not ok")
                if (!configEpoch.matches(epoch)) return@withLock cachedSnapshot()

                statsStore.edit()
                    .putString(KEY_STATS_JSON, raw)
                    .putLong(KEY_RECEIVED_AT, now)
                    .apply()
                activityHistory?.record(parsed.copy(source = SnapshotSource.LIVE))
                parsed.copy(source = SnapshotSource.LIVE)
            }.getOrElse { failure ->
                val malformed = failure.message?.startsWith("malformed:") == true
                cached?.copy(
                    reachable = false,
                    stale = true,
                    malformed = malformed,
                    source = SnapshotSource.CACHED,
                    error = failure.message ?: failure.javaClass.simpleName,
                ) ?: StarIntelSnapshot(
                    configured = true,
                    reachable = false,
                    stale = true,
                    malformed = malformed,
                    source = SnapshotSource.NONE,
                    error = failure.message ?: failure.javaClass.simpleName,
                )
            }
        }
    }

    suspend fun snapshot(forceRefresh: Boolean = false): StarIntelSnapshot = refresh(forceRefresh)

    private fun notConfigured(error: String) = StarIntelSnapshot(
        configured = false,
        reachable = false,
        source = SnapshotSource.NONE,
        error = error,
    )

    private fun fetchUrl(url: String, apiKey: String): String {
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
            if (connection.contentLengthLong > MAX_RESPONSE_BYTES) {
                throw IllegalStateException("stats response exceeds $MAX_RESPONSE_BYTES bytes")
            }

            connection.inputStream.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8_192)
                var total = 0
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IllegalStateException("stats response exceeds $MAX_RESPONSE_BYTES bytes")
                    }
                    builder.append(buffer, 0, read)
                }
                builder.toString()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val PREFS = "starintel_wear"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_STATS_JSON = "stats_json"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val CACHE_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val READ_TIMEOUT_MS = 5_000
        const val MAX_RESPONSE_BYTES = 256 * 1_024

        @Volatile private var instance: StarIntelRepository? = null

        fun get(context: Context): StarIntelRepository =
            instance ?: synchronized(this) {
                instance ?: StarIntelRepository(context).also { instance = it }
            }
    }
}

internal const val STALE_MS = 15 * 60_000L

internal fun parseStatsPayload(raw: String, receivedAt: Long, nowMs: Long): StarIntelSnapshot {
    val root = JSONObject(raw)
    val status = strictString(root, "status")
    val data = root.optJSONObject("data") ?: error("malformed: missing data")
    val documents = data.optJSONObject("documents") ?: error("malformed: missing documents")
    val total = strictLong(documents, "total", allowZero = true)
    val byTypeJson = documents.optJSONObject("by_dtype") ?: error("malformed: missing documents.by_dtype")
    val byType = buildMap {
        val keys = byTypeJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            put(key, strictLong(byTypeJson, key, allowZero = true))
        }
    }
    val targets = data.optJSONObject("targets") ?: error("malformed: missing targets")
    val targetsTotal = strictLong(targets, "total", allowZero = true)
    val generatedAt = strictLong(data, "generated_at", allowZero = false)
    val generatedAtMs = generatedAt * 1000L

    return StarIntelSnapshot(
        configured = true,
        reachable = status.equals("ok", ignoreCase = true),
        service = optionalString(data, "service") ?: "starintel-gserver",
        version = optionalString(data, "version") ?: "?",
        generatedAt = generatedAt,
        receivedAt = receivedAt,
        documentsTotal = total,
        documentsByType = byType,
        targetsTotal = targetsTotal,
        stale = nowMs - generatedAtMs > STALE_MS,
    )
}

private fun strictString(obj: JSONObject, key: String): String {
    if (!obj.has(key) || obj.isNull(key)) error("malformed: missing $key")
    return obj.get(key) as? String ?: error("malformed: $key must be a string")
}

private fun optionalString(obj: JSONObject, key: String): String? {
    if (!obj.has(key) || obj.isNull(key)) return null
    return obj.get(key) as? String ?: error("malformed: $key must be a string")
}

private fun strictLong(obj: JSONObject, key: String, allowZero: Boolean): Long {
    if (!obj.has(key) || obj.isNull(key)) error("malformed: missing $key")
    val value = obj.get(key)
    if (value !is Number) error("malformed: $key must be an integer")
    val longValue = when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong()
        else -> {
            val number = value.toDouble()
            if (!number.isFinite() || number % 1.0 != 0.0 ||
                number < Long.MIN_VALUE.toDouble() || number > Long.MAX_VALUE.toDouble()
            ) error("malformed: $key must be an integer")
            number.toLong()
        }
    }
    if (longValue < 0L || (!allowZero && longValue == 0L)) {
        error("malformed: $key out of range")
    }
    return longValue
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
