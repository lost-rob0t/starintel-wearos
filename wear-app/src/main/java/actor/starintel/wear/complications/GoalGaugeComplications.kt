package actor.starintel.wear.complications

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.GoalGaugeMetric
import actor.starintel.wear.data.GoalGaugeReading
import actor.starintel.wear.data.GoalGaugeStore
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.goalGaugeReading
import actor.starintel.wear.data.hourlyNetGrowth

abstract class GoalGaugeComplicationService(
    private val metric: GoalGaugeMetric,
) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType !in SUPPORTED_TYPES) return null
        val repository = StarIntelRepository.get(applicationContext)
        val snapshot = repository.snapshot()
        val config = GoalGaugeStore.get(applicationContext).load()
        val rate = if (metric == GoalGaugeMetric.HOURLY_NET_GROWTH) {
            hourlyNetGrowth(ActivityHistoryStore.get(applicationContext).points(ActivityRange.H1))
        } else {
            null
        }
        val reading = goalGaugeReading(metric, config, snapshot, rate)
        if (!reading.available) return null
        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> ranged(reading)
            ComplicationType.SHORT_TEXT -> short(reading)
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type !in SUPPORTED_TYPES) return null
        val preview = GoalGaugeReading(
            current = 67L,
            goal = 100L,
            label = previewLabel(metric),
            available = true,
        )
        return when (type) {
            ComplicationType.RANGED_VALUE -> ranged(preview)
            ComplicationType.SHORT_TEXT -> short(preview)
            else -> null
        }
    }

    private fun ranged(reading: GoalGaugeReading): RangedValueComplicationData {
        val state = if (reading.stale) "stale" else "live"
        val description = "${reading.label} ${reading.current} of ${reading.goal}, ${reading.percent} percent, $state"
        return RangedValueComplicationData.Builder(
            value = reading.rangedValue,
            min = reading.rangedMin,
            max = reading.rangedMax,
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setText(PlainComplicationText.Builder("${reading.percent}%").build())
            .setTitle(PlainComplicationText.Builder(reading.label).build())
            .build()
    }

    private fun short(reading: GoalGaugeReading): ShortTextComplicationData {
        val suffix = if (reading.stale) "*" else ""
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("${reading.percent}%$suffix").build(),
            contentDescription = PlainComplicationText.Builder(
                "${reading.label} ${reading.current} of ${reading.goal}${if (reading.stale) ", stale" else ""}",
            ).build(),
        )
            .setTitle(PlainComplicationText.Builder(reading.label).build())
            .build()
    }

    companion object {
        private val SUPPORTED_TYPES = setOf(ComplicationType.SHORT_TEXT, ComplicationType.RANGED_VALUE)
    }
}

class DocumentsGoalComplicationService : GoalGaugeComplicationService(GoalGaugeMetric.DOCUMENTS)
class TargetsGoalComplicationService : GoalGaugeComplicationService(GoalGaugeMetric.TARGETS)
class DocumentTypeGoalComplicationService : GoalGaugeComplicationService(GoalGaugeMetric.DOCUMENT_TYPE)
class DocumentRateGoalComplicationService : GoalGaugeComplicationService(GoalGaugeMetric.HOURLY_NET_GROWTH)

private fun previewLabel(metric: GoalGaugeMetric): String = when (metric) {
    GoalGaugeMetric.DOCUMENTS -> "DOCS"
    GoalGaugeMetric.TARGETS -> "TARGETS"
    GoalGaugeMetric.DOCUMENT_TYPE -> "TYPE"
    GoalGaugeMetric.HOURLY_NET_GROWTH -> "NET/H"
}
