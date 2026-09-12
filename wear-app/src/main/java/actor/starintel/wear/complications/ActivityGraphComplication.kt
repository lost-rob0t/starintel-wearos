package actor.starintel.wear.complications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityPoint
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.StarIntelRepository
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class ActivityGraphPreferences(private val context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    fun range(): ActivityRange = runCatching {
        ActivityRange.valueOf(prefs.getString(KEY_RANGE, ActivityRange.H24.name) ?: ActivityRange.H24.name)
    }.getOrDefault(ActivityRange.H24)
    fun setRange(range: ActivityRange) { prefs.edit().putString(KEY_RANGE, range.name).apply() }
    companion object { private const val PREFS = "starintel_graph"; private const val KEY_RANGE = "range" }
}

class ActivityGraphComplicationService : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        StarIntelRepository.get(applicationContext).snapshot()
        val range = ActivityGraphPreferences(applicationContext).range()
        val images = ActivityGraphRenderer.render(ActivityHistoryStore.get(applicationContext).points(range), range)
        return graphData(images, "StarIntel document activity ${range.label}")
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val range = ActivityRange.H24
        return graphData(ActivityGraphRenderer.renderPreview(range), "StarIntel activity preview ${range.label}")
    }

    private fun graphData(images: ActivityGraphImages, description: String): SmallImageComplicationData {
        val smallImage = SmallImage.Builder(Icon.createWithBitmap(images.active), SmallImageType.ICON)
            .setAmbientImage(Icon.createWithBitmap(images.ambient)).build()
        return SmallImageComplicationData.Builder(
            smallImage,
            PlainComplicationText.Builder(description).build(),
        ).build()
    }
}

data class ActivityGraphImages(val active: Bitmap, val ambient: Bitmap)

object ActivityGraphRenderer {
    // Matches the approved Neon HUD ingest-panel envelope in the 450-space WFF scene.
    private const val WIDTH = 274
    private const val HEIGHT = 66
    private const val PAD_X = 7f
    private const val PAD_TOP = 12f
    private const val PAD_BOTTOM = 7f

    fun render(points: List<ActivityPoint>, range: ActivityRange) = ActivityGraphImages(
        draw(points, range, false, false), draw(points, range, true, false),
    )

    fun renderPreview(range: ActivityRange): ActivityGraphImages {
        val values = listOf(2L, 5L, 3L, 7L, 4L, 9L, 6L, 11L, 8L, 13L, 7L, 10L)
        val now = 1_800_000_000L
        val points = values.mapIndexed { index, value ->
            ActivityPoint(now - (values.lastIndex - index) * 300L, value)
        }
        return ActivityGraphImages(draw(points, range, false, true), draw(points, range, true, true))
    }

    private fun draw(points: List<ActivityPoint>, range: ActivityRange, ambient: Boolean, preview: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) Color.WHITE else Color.rgb(0, 229, 255)
            strokeWidth = if (ambient) 1.5f else 2.5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) Color.WHITE else Color.rgb(175, 190, 205)
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText("INGEST · ${range.label}", PAD_X, 9f, textPaint)
        if (preview) canvas.drawText("PREVIEW", WIDTH - 48f, 9f, textPaint)
        val valid = points.mapNotNull { it.documentsAdded }
        if (valid.isEmpty()) {
            if (!preview) canvas.drawText("COLLECTING", PAD_X, HEIGHT - 6f, textPaint)
            return bitmap
        }
        val maxValue = valid.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val graphHeight = HEIGHT - PAD_TOP - PAD_BOTTOM
        val graphWidth = WIDTH - PAD_X * 2f
        val denominator = (points.size - 1).coerceAtLeast(1)
        var previousX: Float? = null
        var previousY: Float? = null
        points.forEachIndexed { index, point ->
            val value = point.documentsAdded
            if (value == null) { previousX = null; previousY = null; return@forEachIndexed }
            val x = PAD_X + graphWidth * index.toFloat() / denominator.toFloat()
            val y = PAD_TOP + graphHeight * (1f - (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f))
            val px = previousX; val py = previousY
            if (px != null && py != null) canvas.drawLine(px, py, x, y, linePaint)
            previousX = x; previousY = y
        }
        return bitmap
    }
}
