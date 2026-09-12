package actor.starintel.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class ActivityRange(val seconds: Long, val label: String) {
    H1(60 * 60L, "1H"),
    H6(6 * 60 * 60L, "6H"),
    H24(24 * 60 * 60L, "24H"),
    D7(7 * 24 * 60 * 60L, "7D"),
    D30(30 * 24 * 60 * 60L, "30D"),
}

data class ActivitySample(val epochSeconds: Long, val documentsTotal: Long)

data class ActivityPoint(
    val epochSeconds: Long,
    val documentsAdded: Long?,
    val gap: Boolean = false,
    val reset: Boolean = false,
)

internal data class ActivityHistoryState(
    val detail: List<ActivitySample> = emptyList(),
    val hourly: List<ActivitySample> = emptyList(),
)

internal object ActivityHistoryModel {
    private const val DETAIL_RETENTION_SECONDS = 25 * 60 * 60L
    private const val LONG_RETENTION_SECONDS = 31 * 24 * 60 * 60L
    private const val DETAIL_GAP_SECONDS = 20 * 60L
    private const val HOURLY_GAP_SECONDS = 2 * 60 * 60L
    private const val MAX_DETAIL_SAMPLES = 360
    private const val MAX_HOURLY_SAMPLES = 760

    fun record(state: ActivityHistoryState, sample: ActivitySample): ActivityHistoryState {
        if (sample.epochSeconds <= 0L || sample.documentsTotal < 0L) return state
        val detail = upsertSorted(state.detail, sample)
            .filter { it.epochSeconds >= sample.epochSeconds - DETAIL_RETENTION_SECONDS }
            .takeLast(MAX_DETAIL_SAMPLES)
        val hour = sample.epochSeconds / 3600L
        val hourly = (state.hourly.filter { it.epochSeconds / 3600L != hour } + sample)
            .sortedBy { it.epochSeconds }
            .filter { it.epochSeconds >= sample.epochSeconds - LONG_RETENTION_SECONDS }
            .takeLast(MAX_HOURLY_SAMPLES)
        return ActivityHistoryState(detail, hourly)
    }

    fun points(state: ActivityHistoryState, range: ActivityRange, nowSeconds: Long): List<ActivityPoint> {
        val source = if (range.seconds <= ActivityRange.H24.seconds) state.detail else state.hourly
        val samples = source.filter { it.epochSeconds in (nowSeconds - range.seconds)..nowSeconds }
        if (samples.size < 2) return emptyList()
        val gapLimit = if (range.seconds <= ActivityRange.H24.seconds) DETAIL_GAP_SECONDS else HOURLY_GAP_SECONDS
        return samples.zipWithNext { previous, current ->
            val interval = current.epochSeconds - previous.epochSeconds
            val reset = current.documentsTotal < previous.documentsTotal
            val gap = interval <= 0L || interval > gapLimit
            ActivityPoint(
                epochSeconds = current.epochSeconds,
                documentsAdded = if (!reset && !gap) current.documentsTotal - previous.documentsTotal else null,
                gap = gap,
                reset = reset,
            )
        }
    }

    private fun upsertSorted(samples: List<ActivitySample>, sample: ActivitySample): List<ActivitySample> =
        (samples.filter { it.epochSeconds != sample.epochSeconds } + sample).sortedBy { it.epochSeconds }
}

class ActivityHistoryStore private constructor(context: Context) : ActivityHistoryRecorder {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun record(snapshot: StarIntelSnapshot) {
        if (!snapshot.reachable || snapshot.malformed || snapshot.receivedAt <= 0L) return
        val epochSeconds = snapshot.generatedAt.takeIf { it > 0L } ?: snapshot.receivedAt / 1000L
        save(ActivityHistoryModel.record(load(), ActivitySample(epochSeconds, snapshot.documentsTotal)))
    }

    @Synchronized
    fun points(range: ActivityRange, nowSeconds: Long = System.currentTimeMillis() / 1000L): List<ActivityPoint> =
        ActivityHistoryModel.points(load(), range, nowSeconds)

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
            .put("version", 1)
            .put("detail", encode(state.detail))
            .put("hourly", encode(state.hourly))
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    private fun encode(samples: List<ActivitySample>): JSONArray = JSONArray().also { array ->
        samples.forEach { array.put(JSONArray().put(it.epochSeconds).put(it.documentsTotal)) }
    }

    private fun decode(array: JSONArray?): List<ActivitySample> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONArray(index) ?: continue
                val timestamp = item.optLong(0, -1L)
                val total = item.optLong(1, -1L)
                if (timestamp > 0L && total >= 0L) add(ActivitySample(timestamp, total))
            }
        }.sortedBy { it.epochSeconds }
    }

    companion object {
        private const val PREFS = "starintel_activity_history"
        private const val KEY_JSON = "history_v1"
        @Volatile private var instance: ActivityHistoryStore? = null
        fun get(context: Context): ActivityHistoryStore =
            instance ?: synchronized(this) {
                instance ?: ActivityHistoryStore(context).also { instance = it }
            }
    }
}
