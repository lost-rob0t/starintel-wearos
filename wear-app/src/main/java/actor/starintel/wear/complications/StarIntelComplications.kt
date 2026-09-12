package actor.starintel.wear.complications

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot

private const val MAX_SHORT_TEXT_CHARS = 12

abstract class StarIntelMetricComplicationService(
    private val spec: StarIntelMetricSpec,
) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snapshot = StarIntelRepository.get(applicationContext).snapshot()
        val reading = spec.read(snapshot, System.currentTimeMillis())
        return shortText(
            text = reading.displayText(),
            description = contentDescription(spec.id, reading),
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) {
            shortText(spec.chooserPreview, "StarIntel ${spec.id.name.lowercase().replace('_', ' ')}")
        } else {
            null
        }

    private fun contentDescription(id: StarIntelMetricId, reading: MetricReading): String {
        val metric = id.name.lowercase().replace('_', ' ')
        val state = when (reading) {
            is MetricReading.State -> reading.text
            is MetricReading.Value -> if (reading.stale) "${reading.text}, stale" else reading.text
        }
        return "StarIntel $metric: $state"
    }
}

abstract class StarIntelStatusComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snapshot = StarIntelRepository.get(applicationContext).snapshot()
        val text = statusText(snapshot)
        return shortText(text, "StarIntel status: $text")
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText("STATUS", "StarIntel status") else null

    private fun statusText(snapshot: StarIntelSnapshot): String = when {
        !snapshot.configured -> "SETUP"
        snapshot.receivedAt <= 0L -> "NO DATA"
        snapshot.stale -> "STALE"
        snapshot.reachable -> "ONLINE"
        else -> "OFFLINE"
    }
}

private fun shortText(text: String, description: String): ShortTextComplicationData {
    val rendered = PlainComplicationText.Builder(text.take(MAX_SHORT_TEXT_CHARS)).build()
    return ShortTextComplicationData.Builder(
        text = rendered,
        contentDescription = PlainComplicationText.Builder(description).build(),
    ).build()
}

class StatusComplicationService : StarIntelStatusComplicationService()

class TargetsComplicationService : StarIntelMetricComplicationService(
    StarIntelMetricCatalog.targetsTotal,
)

class DocumentsComplicationService : StarIntelMetricComplicationService(
    StarIntelMetricCatalog.documentsTotal,
)

class TargetDocumentsComplicationService : StarIntelMetricComplicationService(
    StarIntelMetricCatalog.targetDocuments,
)

class InvestigationTargetsComplicationService : StarIntelMetricComplicationService(
    StarIntelMetricCatalog.investigationTargets,
)

class FreshnessComplicationService : StarIntelMetricComplicationService(
    StarIntelMetricCatalog.syncFreshness,
)
