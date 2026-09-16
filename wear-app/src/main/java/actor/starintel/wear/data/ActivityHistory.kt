package actor.starintel.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ActivityRange(val seconds: Long, val label: String) {
    M1(60L, "1M"),
    M5(5 * 60L, "5M"),
    M15(15 * 60L, "15M"),
    H1(60 * 60L, "1H"),
    H6(6 * 60 * 60L, "6H"),
    D1(24 * 60 * 60L, "1D"),
    W1(7 * 24 * 60 * 60L, "1W"),
}

data class ActivitySample(
    val epochSeconds: Long,
    val documentsTotal: Long,
    val documentsByType: Map<String, Long> = emptyMap(),
)

data class ActivityPoint(
    val epochSeconds: Long,
    val documentsAdded: Long?,
    val documentsByTypeAdded: Map<String, Long> = emptyMap(),
    val gap: Boolean = false,
    val reset: Boolean = false,
)

data class ActivitySeries(
    val key: String,
    val label: String,
    val points: List<Pair<Long, Long?>>,
)

internal data class ActivityHistoryState(
    val detail: List<ActivitySample> = emptyList(),
    val hourly: List<ActivitySample> = emptyList(),
)

internal object ActivityHistoryModel {
    private const val DETAIL_RETENTION_SECONDS = 25 * 60 * 60L
    private const val LONG_RETENTION_SECONDS = 8 * 24 * 60 * 60L
    private const val DETAIL_GAP_SECONDS = 20 * 60L
    private const val HOURLY_GAP_SECONDS = 2 * 60 * 60L
    private const val MAX_DETAIL_SAMPLES = 1_600
    private const val MAX_HOURLY_SAMPLES = 200

    fun record(state: ActivityHistoryState, sample: ActivitySample): ActivityHistoryState {
        if (sample.epochSeconds <= 0L || sample.documentsTotal < 0L) return state
        val clean = sample.copy(
            documentsByType = sample.documentsByType
                .filter { it.key.isNotBlank() && it.value >= 0L }
                .toSortedMap(),
        )
        val detail = upsertSorted(state.detail, clean)
            .filter { it.epochSeconds >= clean.epochSeconds - DETAIL_RETENTION_SECONDS }
            .takeLast(MAX_DETAIL_SAMPLES)
        val hour = clean.epochSeconds / 3600L
        val hourly = (state.hourly.filter { it.epochSeconds / 3600L != hour } + clean)
            .sortedBy { it.epochSeconds }
            .filter { it.epochSeconds >= clean.epochSeconds - LONG_RETENTION_SECONDS }
            .takeLast(MAX_HOURLY_SAMPLES)
        return ActivityHistoryState(detail, hourly)
    }

    fun points(state: ActivityHistoryState, range: ActivityRange, nowSeconds: Long): List<ActivityPoint> {
        val source = if (range.seconds <= ActivityRange.D1.seconds) state.detail else state.hourly
        val samples = source.filter { it.epochSeconds in (nowSeconds - range.seconds)..nowSeconds }
        if (samples.size < 2) return emptyList()
        val gapLimit = if (range.seconds <= ActivityRange.D1.seconds) DETAIL_GAP_SECONDS else HOURLY_GAP_SECONDS
        return samples.zipWithNext { previous, current ->
            val interval = current.epochSeconds - previous.epochSeconds
            val reset = current.documentsTotal < previous.documentsTotal
            val gap = interval <= 0L || interval > gapLimit
            val typeDeltas = if (reset || gap) emptyMap() else {
                previous.documentsByType.keys.intersect(current.documentsByType.keys)
                    .mapNotNull { type ->
                        val before = previous.documentsByType[type] ?: 0L
                        val after = current.documentsByType[type] ?: 0L
                        (after - before).takeIf { it >= 0L }?.let { type to it }
                    }
                    .toMap()
            }
            ActivityPoint(
                epochSeconds = current.epochSeconds,
                documentsAdded = if (!reset && !gap) current.documentsTotal - previous.documentsTotal else null,
                documentsByTypeAdded = typeDeltas,
                gap = gap,
                reset = reset,
            )
        }
    }

    fun series(points: List<ActivityPoint>, includeTypes: Boolean, maxTypes: Int = 3): List<ActivitySeries> {
        if (points.isEmpty()) return emptyList()
        val result = mutableListOf(
            ActivitySeries("total", "All docs", points.map { it.epochSeconds to it.documentsAdded }),
        )
        if (!includeTypes) return result
        val types = points
            .flatMap { it.documentsByTypeAdded.entries }
            .groupingBy { it.key }
            .fold(0L) { total, entry -> total + entry.value }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .take(maxTypes)
            .map { it.key }
        types.forEach { type ->
            result += ActivitySeries(
                key = type,
                label = type,
                points = points.map { point -> point.epochSeconds to point.documentsByTypeAdded[type] },
            )
        }
        return result
    }

    fun autoRange(state: ActivityHistoryState, nowSeconds: Long): ActivityRange {
        val available = ActivityRange.entries.filter { range ->
            points(state, range, nowSeconds).count { it.documentsAdded != null } >= 2
        }
        if (available.isEmpty()) return ActivityRange.H1
        val rotation = (nowSeconds / AUTO_ROTATE_SECONDS).toInt()
        return available[Math.floorMod(rotation, available.size)]
    }

    private fun upsertSorted(samples: List<ActivitySample>, sample: ActivitySample): List<ActivitySample> =
        (samples.filter { it.epochSeconds != sample.epochSeconds } + sample).sortedBy { it.epochSeconds }

    private const val AUTO_ROTATE_SECONDS = 60L
}

class ActivityHistoryStore private constructor(context: Context) : ActivityHistoryRecorder {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(snapshot: StarIntelSnapshot) {
        if (!snapshot.reachable || snapshot.malformed || snapshot.receivedAt <= 0L) return
        val epochSeconds = snapshot.generatedAt.takeIf { it > 0L } ?: snapshot.receivedAt / 1000L
        save(
            ActivityHistoryModel.record(
                load(),
                ActivitySample(epochSeconds, snapshot.documentsTotal, snapshot.documentsByType),
            ),
        )
    }

    @Synchronized
    fun points(range: ActivityRange, nowSeconds: Long = System.currentTimeMillis() / 1000L): List<ActivityPoint> =
        ActivityHistoryModel.points(load(), range, nowSeconds)

    @Synchronized
    fun series(
        range: ActivityRange,
        includeTypes: Boolean,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): List<ActivitySeries> = ActivityHistoryModel.series(points(range, nowSeconds), includeTypes)

    @Synchronized
    fun autoRange(nowSeconds: Long = System.currentTimeMillis() / 1000L): ActivityRange =
        ActivityHistoryModel.autoRange(load(), nowSeconds)

    @Synchronized
    override fun clear() {
        prefs.edit().remove(KEY_JSON).apply()
    }

    private fun load(): ActivityHistoryState = runCatching {
        val root = JSONObject(prefs.getString(KEY_JSON, "{}") ?: "{}")
        ActivityHistoryState(decode(root.optJSONArray("detail")), decode(root.optJSONArray("hourly")))
    }.getOrDefault(ActivityHistoryState())

    private fun save(state: ActivityHistoryState) {
        val root = JSONObject()
            .put("version", 2)
            .put("detail", encode(state.detail))
            .put("hourly", encode(state.hourly))
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    private fun encode(samples: List<ActivitySample>): JSONArray = JSONArray().also { array ->
        samples.forEach { sample ->
            array.put(
                JSONObject()
                    .put("at", sample.epochSeconds)
                    .put("total", sample.documentsTotal)
                    .put("types", JSONObject(sample.documentsByType)),
            )
        }
    }

    private fun decode(array: JSONArray?): List<ActivitySample> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                when (val item = array.opt(index)) {
                    is JSONArray -> {
                        val timestamp = item.optLong(0, -1L)
                        val total = item.optLong(1, -1L)
                        if (timestamp > 0L && total >= 0L) add(ActivitySample(timestamp, total))
                    }
                    is JSONObject -> {
                        val timestamp = item.optLong("at", -1L)
                        val total = item.optLong("total", -1L)
                        val types = item.optJSONObject("types")?.let(::decodeTypes).orEmpty()
                        if (timestamp > 0L && total >= 0L) add(ActivitySample(timestamp, total, types))
                    }
                }
            }
        }.sortedBy { it.epochSeconds }
    }

    private fun decodeTypes(value: JSONObject): Map<String, Long> = buildMap {
        value.keys().forEach { key ->
            val count = value.optLong(key, -1L)
            if (key.isNotBlank() && count >= 0L) put(key, count)
        }
    }

    companion object {
        private const val PREFS = "starintel_activity_history"
        // Keep the established key: decode() accepts both the old tuple format and v2 objects.
        private const val KEY_JSON = "history_v1"
        @Volatile private var instance: ActivityHistoryStore? = null
        fun get(context: Context): ActivityHistoryStore =
            instance ?: synchronized(this) {
                instance ?: ActivityHistoryStore(context).also { instance = it }
            }
    }
}
