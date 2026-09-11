package actor.starintel.wear.complications

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.compactCount

abstract class StarIntelComplicationService(
    private val preview: String,
    private val value: (StarIntelSnapshot) -> String,
) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val snapshot = StarIntelRepository.get(applicationContext).snapshot()
        return shortText(value(snapshot))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText(preview) else null

    private fun shortText(value: String): ShortTextComplicationData {
        val text = PlainComplicationText.Builder(value.take(7)).build()
        return ShortTextComplicationData.Builder(
            text = text,
            contentDescription = PlainComplicationText.Builder("StarIntel $value").build(),
        ).build()
    }
}

class StatusComplicationService : StarIntelComplicationService(
    preview = "ONLINE",
    value = { data ->
        when {
            !data.configured -> "SETUP"
            data.reachable && !data.stale -> "ONLINE"
            data.reachable -> "STALE"
            else -> "OFFLINE"
        }
    },
)

class TargetsComplicationService : StarIntelComplicationService(
    preview = "128",
    value = { data -> if (data.configured) data.targetsTotal.compactCount() else "SETUP" },
)

class DocumentsComplicationService : StarIntelComplicationService(
    preview = "12.4K",
    value = { data -> if (data.configured) data.documentsTotal.compactCount() else "SETUP" },
)
