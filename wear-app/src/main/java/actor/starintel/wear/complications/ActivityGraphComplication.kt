package actor.starintel.wear.complications

import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityPoint
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.ActivitySeries
import actor.starintel.wear.data.StarIntelRepository
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

abstract class BaseActivityGraphComplicationService : SuspendingComplicationDataSourceService() {
    protected abstract fun range(store: ActivityHistoryStore): ActivityRange

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SMALL_IMAGE) return null
        StarIntelRepository.get(applicationContext).snapshot()
        val store = ActivityHistoryStore.get(applicationContext)
        val selected = range(store)
        val points = store.points(selected)
        return graphData(
            ActivityGraphRenderer.render(points, selected, includeTypes = true),
            "StarIntel document activity ${selected.label}; ${points.sumOf { it.documentsAdded ?: 0L }} documents",
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SMALL_IMAGE) return null
        val selected = ActivityRange.H1
        return graphData(ActivityGraphRenderer.renderPreview(selected), "StarIntel activity preview ${selected.label}")
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

/** Automatically rotates through ranges that have enough real samples once per minute. */
class ActivityGraphComplicationService : BaseActivityGraphComplicationService() {
    override fun range(store: ActivityHistoryStore): ActivityRange = store.autoRange()
}

abstract class FixedActivityGraphComplicationService(
    private val fixed: ActivityRange,
) : BaseActivityGraphComplicationService() {
    override fun range(store: ActivityHistoryStore): ActivityRange = fixed
}

class ActivityGraph1mComplicationService : FixedActivityGraphComplicationService(ActivityRange.M1)
class ActivityGraph5mComplicationService : FixedActivityGraphComplicationService(ActivityRange.M5)
class ActivityGraph15mComplicationService : FixedActivityGraphComplicationService(ActivityRange.M15)
class ActivityGraph1hComplicationService : FixedActivityGraphComplicationService(ActivityRange.H1)
class ActivityGraph6hComplicationService : FixedActivityGraphComplicationService(ActivityRange.H6)
class ActivityGraph1dComplicationService : FixedActivityGraphComplicationService(ActivityRange.D1)
class ActivityGraph1wComplicationService : FixedActivityGraphComplicationService(ActivityRange.W1)

data class ActivityGraphImages(val active: Bitmap, val ambient: Bitmap)

object ActivityGraphRenderer {
    private const val WIDTH = 274
    private const val HEIGHT = 66
    private const val PAD_X = 7f
    private const val PAD_TOP = 13f
    private const val PAD_BOTTOM = 8f
    private val colors = intArrayOf(
        Color.rgb(0, 229, 255),
        Color.rgb(198, 91, 255),
        Color.rgb(126, 255, 128),
        Color.rgb(255, 190, 70),
    )

    fun render(points: List<ActivityPoint>, range: ActivityRange, includeTypes: Boolean = true): ActivityGraphImages {
        val series = actor.starintel.wear.data.ActivityHistoryModel.series(points, includeTypes)
        return ActivityGraphImages(
            draw(series, range, ambient = false, preview = false),
            draw(series.take(1), range, ambient = true, preview = false),
        )
    }

    fun renderPreview(range: ActivityRange): ActivityGraphImages {
        val now = 1_800_000_000L
        val points = listOf(2L, 5L, 3L, 7L, 4L, 9L, 6L, 11L, 8L, 13L, 7L, 10L).mapIndexed { index, value ->
            ActivityPoint(
                epochSeconds = now - (11 - index) * 300L,
                documentsAdded = value,
                documentsByTypeAdded = mapOf("target" to (value / 2), "relation" to (value / 3)),
            )
        }
        val series = actor.starintel.wear.data.ActivityHistoryModel.series(points, includeTypes = true)
        return ActivityGraphImages(
            draw(series, range, ambient = false, preview = true),
            draw(series.take(1), range, ambient = true, preview = true),
        )
    }

    private fun draw(
        series: List<ActivitySeries>,
        range: ActivityRange,
        ambient: Boolean,
        preview: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) Color.WHITE else Color.rgb(183, 194, 207)
            textSize = 9f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("DOCS · ${range.label}", PAD_X, 9f, textPaint)
        if (preview) canvas.drawText("PREVIEW", WIDTH - 48f, 9f, textPaint)
        val values = series.flatMap { it.points }.mapNotNull { it.second }
        if (values.isEmpty()) {
            if (!preview) canvas.drawText("COLLECTING", PAD_X, HEIGHT - 5f, textPaint)
            return bitmap
        }

        val allTimes = series.flatMap { it.points }.map { it.first }
        val minTime = allTimes.minOrNull() ?: 0L
        val maxTime = allTimes.maxOrNull()?.coerceAtLeast(minTime + 1L) ?: minTime + 1L
        val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        val graphHeight = HEIGHT - PAD_TOP - PAD_BOTTOM
        val graphWidth = WIDTH - PAD_X * 2f

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (ambient) Color.rgb(80, 80, 80) else Color.rgb(40, 55, 66)
            strokeWidth = 1f
        }
        for (line in 0..2) {
            val y = PAD_TOP + graphHeight * line / 2f
            canvas.drawLine(PAD_X, y, WIDTH - PAD_X, y, grid)
        }

        series.forEachIndexed { index, valuesForSeries ->
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (ambient) Color.WHITE else colors[index % colors.size]
                strokeWidth = if (index == 0) 2.6f else 1.7f
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            var open = false
            valuesForSeries.points.forEach { (timestamp, value) ->
                if (value == null) {
                    open = false
                } else {
                    val x = PAD_X + graphWidth * (timestamp - minTime).toFloat() / (maxTime - minTime).toFloat()
                    val y = PAD_TOP + graphHeight * (1f - (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f))
                    if (open) path.lineTo(x, y) else path.moveTo(x, y)
                    open = true
                }
            }
            canvas.drawPath(path, line)
            valuesForSeries.points.forEach { (timestamp, value) ->
                if (value != null) {
                    val x = PAD_X + graphWidth * (timestamp - minTime).toFloat() / (maxTime - minTime).toFloat()
                    val y = PAD_TOP + graphHeight * (1f - (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f))
                    canvas.drawCircle(x, y, if (index == 0) 2f else 1.4f, line.apply { style = Paint.Style.FILL })
                    line.style = Paint.Style.STROKE
                }
            }
        }
        return bitmap
    }
}
