package actor.starintel.wear.complications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import actor.starintel.wear.data.GeoActivityRepository
import actor.starintel.wear.data.GeoActivitySnapshot
import actor.starintel.wear.data.GeoBucket
import actor.starintel.wear.data.compactCount
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlin.math.ln

private const val MAX_SHORT_TEXT_CHARS = 12
private const val GEO_IMAGE_WIDTH = 180
private const val GEO_IMAGE_HEIGHT = 68

class GeoActivityComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = GeoActivityRepository.get(applicationContext).snapshot()
        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> shortText(snapshot)
            ComplicationType.SMALL_IMAGE -> smallImage(snapshot)
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> shortTextData(
            "2.4K",
            "StarIntel geo preview",
        )
        ComplicationType.SMALL_IMAGE -> {
            val bitmap = GeoActivityRenderer.render(
                buckets = listOf(
                    GeoBucket(37.5, -82.5, 12),
                    GeoBucket(52.5, 7.5, 7),
                    GeoBucket(-37.5, 142.5, 4),
                ),
                label = "PREVIEW",
            )
            smallImageData(bitmap, "StarIntel geo preview")
        }
        else -> null
    }

    private fun shortText(snapshot: GeoActivitySnapshot): ComplicationData {
        val text = when {
            !snapshot.configured -> "SETUP"
            snapshot.fetchedAtMs <= 0L && !snapshot.reachable -> "NO DATA"
            snapshot.stale -> "~${snapshot.totalGeocodedDocuments.compactCount()}"
            else -> snapshot.totalGeocodedDocuments.compactCount()
        }
        return shortTextData(text, description(snapshot))
    }

    private fun smallImage(snapshot: GeoActivitySnapshot): ComplicationData {
        val stateLabel = when {
            !snapshot.configured -> "SETUP"
            snapshot.fetchedAtMs <= 0L && !snapshot.reachable -> "NO DATA"
            snapshot.totalGeocodedDocuments == 0L -> "NO GEO"
            snapshot.buckets.isEmpty() -> "NO SAMPLE"
            snapshot.stale -> "STALE"
            else -> null
        }
        val bitmap = GeoActivityRenderer.render(snapshot.buckets, stateLabel)
        return smallImageData(bitmap, description(snapshot))
    }

    private fun description(snapshot: GeoActivitySnapshot): String = when {
        !snapshot.configured -> "StarIntel geo activity: setup required"
        snapshot.fetchedAtMs <= 0L && !snapshot.reachable -> "StarIntel geo activity: no data"
        else -> buildString {
            append("StarIntel geocoded documents: ")
            append(snapshot.totalGeocodedDocuments)
            append("; bounded sample: ")
            append(snapshot.sampledDocuments)
            append(" documents in ")
            append(snapshot.buckets.size)
            append(" coarse cells")
            if (snapshot.stale) append("; stale")
        }
    }
}

private fun shortTextData(text: String, description: String): ShortTextComplicationData {
    return ShortTextComplicationData.Builder(
        text = PlainComplicationText.Builder(text.take(MAX_SHORT_TEXT_CHARS)).build(),
        contentDescription = PlainComplicationText.Builder(description).build(),
    ).build()
}

private fun smallImageData(bitmap: Bitmap, description: String): SmallImageComplicationData {
    val image = SmallImage.Builder(
        Icon.createWithBitmap(bitmap),
        SmallImageType.ICON,
    ).build()
    return SmallImageComplicationData.Builder(
        smallImage = image,
        contentDescription = PlainComplicationText.Builder(description).build(),
    ).build()
}

internal object GeoActivityRenderer {
    fun render(buckets: List<GeoBucket>, label: String? = null): Bitmap {
        val bitmap = Bitmap.createBitmap(
            GEO_IMAGE_WIDTH,
            GEO_IMAGE_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        val structural = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(145, 120, 160, 175)
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val hotspot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f
            textAlign = Paint.Align.CENTER
        }

        val top = if (label == null) 3f else 14f
        val bottom = GEO_IMAGE_HEIGHT - 3f
        val left = 3f
        val right = GEO_IMAGE_WIDTH - 3f
        val mapHeight = bottom - top
        val mapWidth = right - left

        canvas.drawRect(left, top, right, bottom, structural)
        for (fraction in listOf(0.25f, 0.5f, 0.75f)) {
            val x = left + mapWidth * fraction
            canvas.drawLine(x, top, x, bottom, structural)
        }
        for (fraction in listOf(1f / 3f, 2f / 3f)) {
            val y = top + mapHeight * fraction
            canvas.drawLine(left, y, right, y, structural)
        }

        buckets.forEach { bucket ->
            val x = left + ((bucket.longitude + 180.0) / 360.0 * mapWidth).toFloat()
            val y = top + ((90.0 - bucket.latitude) / 180.0 * mapHeight).toFloat()
            val radius = (2.2 + ln(bucket.count.toDouble() + 1.0) * 1.8)
                .coerceAtMost(7.0)
                .toFloat()
            canvas.drawCircle(x, y, radius, hotspot)
        }

        if (label != null) {
            canvas.drawText(label, GEO_IMAGE_WIDTH / 2f, 10f, text)
        }

        return bitmap
    }
}
