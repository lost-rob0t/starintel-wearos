package actor.starintel.wear.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val GEO_SEARCH_LIMIT_PER_TYPE = 25
internal const val GEO_MAX_BUCKETS = 32
internal const val GEO_BUCKET_DEGREES = 15.0
private const val GEO_CACHE_MS = 15 * 60_000L
private const val GEO_STALE_MS = 60 * 60_000L

data class GeoCoordinate(
    val latitude: Double,
    val longitude: Double,
)

data class GeoBucket(
    val latitude: Double,
    val longitude: Double,
    val count: Int,
)

data class GeoActivitySnapshot(
    val configured: Boolean,
    val reachable: Boolean,
    val fetchedAtMs: Long = 0L,
    val totalGeocodedDocuments: Long = 0L,
    val sampledDocuments: Int = 0,
    val buckets: List<GeoBucket> = emptyList(),
    val stale: Boolean = false,
    val error: String? = null,
)

internal object GeoActivityModel {
    fun parseSearchDocuments(raw: String): List<GeoCoordinate> {
        val root = JSONObject(raw)
        val rows = root.optJSONArray("rows") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val doc = rows.optJSONObject(index)?.optJSONObject("doc") ?: continue
                parseCoordinate(doc)?.let(::add)
            }
        }
    }

    fun aggregate(
        coordinates: List<GeoCoordinate>,
        maxBuckets: Int = GEO_MAX_BUCKETS,
    ): List<GeoBucket> {
        if (coordinates.isEmpty() || maxBuckets <= 0) return emptyList()

        return coordinates
            .groupingBy { coordinate ->
                val latCell = floor((coordinate.latitude + 90.0) / GEO_BUCKET_DEGREES)
                    .toInt()
                    .coerceIn(0, 11)
                val lonCell = floor((normalizeLongitude(coordinate.longitude) + 180.0) / GEO_BUCKET_DEGREES)
                    .toInt()
                    .coerceIn(0, 23)
                latCell to lonCell
            }
            .eachCount()
            .map { (cell, count) ->
                GeoBucket(
                    latitude = -90.0 + (cell.first + 0.5) * GEO_BUCKET_DEGREES,
                    longitude = -180.0 + (cell.second + 0.5) * GEO_BUCKET_DEGREES,
                    count = count,
                )
            }
            .sortedWith(
                compareByDescending<GeoBucket> { it.count }
                    .thenBy { it.latitude }
                    .thenBy { it.longitude },
            )
            .take(maxBuckets)
    }

    private fun parseCoordinate(doc: JSONObject): GeoCoordinate? {
        val data = doc.optJSONObject("data")
        val latitude = number(data, "lat")
            ?: number(data, "latitude")
            ?: number(doc, "lat")
            ?: number(doc, "latitude")
        val longitude = number(data, "long")
            ?: number(data, "lon")
            ?: number(data, "longitude")
            ?: number(doc, "long")
            ?: number(doc, "lon")
            ?: number(doc, "longitude")

        if (latitude == null || longitude == null) return null
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null

        return GeoCoordinate(
            latitude = latitude,
            longitude = normalizeLongitude(longitude),
        )
    }

    private fun number(obj: JSONObject?, key: String): Double? {
        if (obj == null || !obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun normalizeLongitude(value: Double): Double = when {
        value == 180.0 -> -180.0
        value < -180.0 || value > 180.0 ->
            ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        else -> value
    }
}

class GeoActivityRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val starIntelRepository = StarIntelRepository.get(appContext)

    suspend fun snapshot(forceRefresh: Boolean = false): GeoActivitySnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val base = starIntelRepository.baseUrl()
        val apiKey = apiKeyStore.read()

        if (base.isBlank() || apiKey.isNullOrBlank()) {
            return@withContext GeoActivitySnapshot(
                configured = false,
                reachable = false,
                error = "StarIntel setup required",
            )
        }

        val cached = loadCached(now)
        if (!forceRefresh && cached != null && now - cached.fetchedAtMs < GEO_CACHE_MS) {
            return@withContext cached
        }

        runCatching {
            val stats = starIntelRepository.snapshot(forceRefresh = false)
            if (!stats.reachable && stats.receivedAt <= 0L) {
                error(stats.error ?: "StarIntel stats unavailable")
            }

            val total = (stats.documentsByType["geo"] ?: 0L) +
                (stats.documentsByType["address"] ?: 0L)
            val coordinates = buildList {
                addAll(GeoActivityModel.parseSearchDocuments(search(base, apiKey, "dtype:geo")))
                addAll(GeoActivityModel.parseSearchDocuments(search(base, apiKey, "dtype:address")))
            }

            GeoActivitySnapshot(
                configured = true,
                reachable = true,
                fetchedAtMs = now,
                totalGeocodedDocuments = total,
                sampledDocuments = coordinates.size,
                buckets = GeoActivityModel.aggregate(coordinates),
            ).also(::saveCached)
        }.getOrElse { failure ->
            cached?.copy(
                reachable = false,
                stale = true,
                error = failure.message ?: failure.javaClass.simpleName,
            ) ?: GeoActivitySnapshot(
                configured = true,
                reachable = false,
                stale = true,
                error = failure.message ?: failure.javaClass.simpleName,
            )
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    private fun search(base: String, apiKey: String, query: String): String {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val connection = URL(
            "$base/api/v1/search?q=$encoded&limit=$GEO_SEARCH_LIMIT_PER_TYPE",
        ).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 4_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", "starintel-wearos/0.3")
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun saveCached(snapshot: GeoActivitySnapshot) {
        val buckets = JSONArray()
        snapshot.buckets.forEach { bucket ->
            buckets.put(
                JSONArray()
                    .put(bucket.latitude)
                    .put(bucket.longitude)
                    .put(bucket.count),
            )
        }

        val root = JSONObject()
            .put("version", 1)
            .put("fetched_at_ms", snapshot.fetchedAtMs)
            .put("total", snapshot.totalGeocodedDocuments)
            .put("sampled", snapshot.sampledDocuments)
            .put("buckets", buckets)
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    private fun loadCached(now: Long): GeoActivitySnapshot? = runCatching {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        val root = JSONObject(raw)
        if (root.optInt("version", 0) != 1) return null

        val fetchedAt = root.optLong("fetched_at_ms", 0L)
        val bucketsJson = root.optJSONArray("buckets") ?: JSONArray()
        val buckets = buildList {
            for (index in 0 until bucketsJson.length()) {
                val item = bucketsJson.optJSONArray(index) ?: continue
                val latitude = item.optDouble(0, Double.NaN)
                val longitude = item.optDouble(1, Double.NaN)
                val count = item.optInt(2, 0)
                if (!latitude.isFinite() || !longitude.isFinite() || count <= 0) continue
                if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
                add(GeoBucket(latitude, longitude, count))
            }
        }

        GeoActivitySnapshot(
            configured = true,
            reachable = true,
            fetchedAtMs = fetchedAt,
            totalGeocodedDocuments = root.optLong("total", 0L).coerceAtLeast(0L),
            sampledDocuments = root.optInt("sampled", 0).coerceAtLeast(0),
            buckets = buckets,
            stale = fetchedAt <= 0L || now - fetchedAt > GEO_STALE_MS,
        )
    }.getOrNull()

    companion object {
        private const val PREFS = "starintel_geo_activity"
        private const val KEY_JSON = "geo_v1"

        @Volatile
        private var instance: GeoActivityRepository? = null

        fun get(context: Context): GeoActivityRepository =
            instance ?: synchronized(this) {
                instance ?: GeoActivityRepository(context).also { instance = it }
            }
    }
}
