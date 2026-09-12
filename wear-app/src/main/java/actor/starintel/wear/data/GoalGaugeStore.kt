package actor.starintel.wear.data

import android.content.Context
import kotlin.math.max

data class GoalGaugeConfig(
    val documentsGoal: Long = 0L,
    val targetsGoal: Long = 0L,
    val documentType: String = "",
    val documentTypeGoal: Long = 0L,
    val hourlyNetGrowthGoal: Long = 0L,
)

enum class GoalGaugeMetric {
    DOCUMENTS,
    TARGETS,
    DOCUMENT_TYPE,
    HOURLY_NET_GROWTH,
}

data class GoalGaugeReading(
    val current: Long,
    val goal: Long,
    val label: String,
    val available: Boolean,
    val stale: Boolean = false,
) {
    val rangedValue: Float get() = current.coerceIn(0L, max(1L, goal)).toFloat()
    val rangedMin: Float get() = 0f
    val rangedMax: Float get() = max(1L, goal).toFloat()
    val percent: Int get() = if (goal <= 0L) 0 else ((current.coerceAtLeast(0L) * 100L) / goal).coerceIn(0L, 100L).toInt()
}

class GoalGaugeStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): GoalGaugeConfig = GoalGaugeConfig(
        documentsGoal = prefs.getLong(KEY_DOCUMENTS, 0L).coerceAtLeast(0L),
        targetsGoal = prefs.getLong(KEY_TARGETS, 0L).coerceAtLeast(0L),
        documentType = prefs.getString(KEY_DTYPE, "")?.trim().orEmpty(),
        documentTypeGoal = prefs.getLong(KEY_DTYPE_GOAL, 0L).coerceAtLeast(0L),
        hourlyNetGrowthGoal = prefs.getLong(KEY_RATE, 0L).coerceAtLeast(0L),
    )

    fun save(config: GoalGaugeConfig) {
        prefs.edit()
            .putLong(KEY_DOCUMENTS, config.documentsGoal.coerceAtLeast(0L))
            .putLong(KEY_TARGETS, config.targetsGoal.coerceAtLeast(0L))
            .putString(KEY_DTYPE, config.documentType.trim().take(128))
            .putLong(KEY_DTYPE_GOAL, config.documentTypeGoal.coerceAtLeast(0L))
            .putLong(KEY_RATE, config.hourlyNetGrowthGoal.coerceAtLeast(0L))
            .apply()
    }

    companion object {
        private const val PREFS = "starintel_goal_gauges"
        private const val KEY_DOCUMENTS = "documents_goal"
        private const val KEY_TARGETS = "targets_goal"
        private const val KEY_DTYPE = "document_type"
        private const val KEY_DTYPE_GOAL = "document_type_goal"
        private const val KEY_RATE = "hourly_net_growth_goal"

        @Volatile private var instance: GoalGaugeStore? = null
        fun get(context: Context): GoalGaugeStore =
            instance ?: synchronized(this) {
                instance ?: GoalGaugeStore(context).also { instance = it }
            }
    }
}

fun goalGaugeReading(
    metric: GoalGaugeMetric,
    config: GoalGaugeConfig,
    snapshot: StarIntelSnapshot,
    hourlyNetGrowth: Long? = null,
): GoalGaugeReading {
    val stale = snapshot.stale || !snapshot.reachable
    return when (metric) {
        GoalGaugeMetric.DOCUMENTS -> GoalGaugeReading(
            current = snapshot.documentsTotal,
            goal = config.documentsGoal,
            label = "DOCS",
            available = snapshot.configured && config.documentsGoal > 0L,
            stale = stale,
        )
        GoalGaugeMetric.TARGETS -> GoalGaugeReading(
            current = snapshot.targetsTotal.takeIf { it > 0L } ?: snapshot.targetCount,
            goal = config.targetsGoal,
            label = "TARGETS",
            available = snapshot.configured && config.targetsGoal > 0L,
            stale = stale,
        )
        GoalGaugeMetric.DOCUMENT_TYPE -> {
            val dtype = config.documentType.trim()
            GoalGaugeReading(
                current = snapshot.documentsByType[dtype] ?: 0L,
                goal = config.documentTypeGoal,
                label = dtype.uppercase().take(10).ifBlank { "TYPE" },
                available = snapshot.configured && dtype.isNotBlank() && config.documentTypeGoal > 0L,
                stale = stale,
            )
        }
        GoalGaugeMetric.HOURLY_NET_GROWTH -> GoalGaugeReading(
            current = hourlyNetGrowth ?: 0L,
            goal = config.hourlyNetGrowthGoal,
            label = "NET/H",
            available = snapshot.configured && config.hourlyNetGrowthGoal > 0L && hourlyNetGrowth != null,
            stale = stale,
        )
    }
}

fun hourlyNetGrowth(points: List<ActivityPoint>): Long? {
    val values = points.mapNotNull { it.documentsAdded }
    if (values.isEmpty()) return null
    return values.sumOf { it.coerceAtLeast(0L) }
}
