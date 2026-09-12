package actor.starintel.wear.complications

import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.compactCount

/** Metrics that the current public stats contract can prove directly. */
enum class StarIntelMetricId {
    DOCUMENTS_TOTAL,
    TARGETS_TOTAL,
    TARGET_DOCUMENTS,
    INVESTIGATION_TARGETS,
    SYNC_FRESHNESS,
}

data class StarIntelMetricSpec(
    val id: StarIntelMetricId,
    val chooserPreview: String,
    val read: (StarIntelSnapshot, Long) -> MetricReading,
)

sealed interface MetricReading {
    data class Value(val text: String, val stale: Boolean = false) : MetricReading
    data class State(val text: String) : MetricReading
}

object StarIntelMetricCatalog {
    val documentsTotal = countMetric(
        id = StarIntelMetricId.DOCUMENTS_TOTAL,
        chooserPreview = "DOCS",
        value = { it.documentsTotal },
    )
    val targetsTotal = countMetric(
        id = StarIntelMetricId.TARGETS_TOTAL,
        chooserPreview = "TARGETS",
        value = { it.targetsTotal },
    )
    val targetDocuments = countMetric(
        id = StarIntelMetricId.TARGET_DOCUMENTS,
        chooserPreview = "TARGET",
        value = { it.targetCount },
    )
    val investigationTargets = countMetric(
        id = StarIntelMetricId.INVESTIGATION_TARGETS,
        chooserPreview = "INV TGT",
        value = { it.investigationTargetCount },
    )
    val syncFreshness = StarIntelMetricSpec(
        id = StarIntelMetricId.SYNC_FRESHNESS,
        chooserPreview = "FRESH",
        read = { snapshot, nowMs -> freshness(snapshot, nowMs) },
    )

    val currentStatsMetrics: List<StarIntelMetricSpec> = listOf(
        documentsTotal,
        targetsTotal,
        targetDocuments,
        investigationTargets,
        syncFreshness,
    )

    private fun countMetric(
        id: StarIntelMetricId,
        chooserPreview: String,
        value: (StarIntelSnapshot) -> Long,
    ) = StarIntelMetricSpec(
        id = id,
        chooserPreview = chooserPreview,
        read = { snapshot, _ ->
            when {
                !snapshot.configured -> MetricReading.State("SETUP")
                snapshot.receivedAt <= 0L -> MetricReading.State("NO DATA")
                else -> MetricReading.Value(value(snapshot).compactCount(), stale = snapshot.stale)
            }
        },
    )

    private fun freshness(snapshot: StarIntelSnapshot, nowMs: Long): MetricReading = when {
        !snapshot.configured -> MetricReading.State("SETUP")
        snapshot.receivedAt <= 0L -> MetricReading.State("NO DATA")
        snapshot.stale -> MetricReading.State("STALE")
        !snapshot.reachable -> MetricReading.State("OFFLINE")
        else -> {
            val ageSeconds = ((nowMs - snapshot.receivedAt).coerceAtLeast(0L) / 1000L)
            val text = when {
                ageSeconds < 60L -> "${ageSeconds}s"
                ageSeconds < 3600L -> "${ageSeconds / 60L}m"
                else -> "${ageSeconds / 3600L}h"
            }
            MetricReading.Value(text)
        }
    }
}

fun MetricReading.displayText(): String = when (this) {
    is MetricReading.State -> text
    is MetricReading.Value -> if (stale) "~$text" else text
}
