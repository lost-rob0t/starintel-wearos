package actor.starintel.wear

import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.ActivitySeries
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.StarIntelPalette
import actor.starintel.wear.ui.applyStarIntelTheme
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ActivityTimelineActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: ActivityHistoryStore
    private lateinit var graph: ActivityTimelineView
    private lateinit var summary: TextView
    private lateinit var mode: Button
    private var selectedRange: ActivityRange? = null
    private var includeTypes = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ActivityHistoryStore.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "ACTIVITY"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Document ingest · real sampled lines"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(rangeButton("AUTO", null))
            ActivityRange.entries.forEach { addView(rangeButton(it.label, it)) }
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(rangeRow)
        }, matchWrap(top = 7))

        mode = Button(this).apply {
            text = "LINES · TOTAL + TYPE"
            textSize = 10f
            applyStarIntelTheme(palette)
            setOnClickListener {
                includeTypes = !includeTypes
                text = if (includeTypes) "LINES · TOTAL + TYPE" else "LINE · TOTAL ONLY"
                render()
            }
        }
        root.addView(mode, matchWrap(top = 4))

        summary = TextView(this).apply {
            text = "Collecting activity…"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(summary, matchWrap(top = 6))

        graph = ActivityTimelineView(this, palette)
        root.addView(
            graph,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)).apply { topMargin = dp(5) },
        )
        root.addView(TextView(this).apply {
            text = "Cyan = all docs · violet/lime/amber = busiest types"
            textSize = 9f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 4))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshSnapshot()
        handler.post(autoRefresh)
    }

    override fun onPause() {
        handler.removeCallbacks(autoRefresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    private val autoRefresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun refreshSnapshot() {
        scope.launch {
            StarIntelRepository.get(this@ActivityTimelineActivity).snapshot(forceRefresh = true)
            render()
        }
    }

    private fun render() {
        if (!::graph.isInitialized) return
        val range = selectedRange ?: store.autoRange()
        val points = store.points(range)
        val series = store.series(range, includeTypes)
        graph.setSeries(series, range)
        val total = points.sumOf { it.documentsAdded ?: 0L }
        val topType = points
            .flatMap { it.documentsByTypeAdded.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, values) -> values.sum() }
            .maxByOrNull { it.value }
        summary.text = buildString {
            append(if (selectedRange == null) "AUTO → " else "")
            append(range.label).append(" · +").append(total).append(" docs")
            if (topType != null) append(" · ").append(topType.key).append(" +").append(topType.value)
        }
        summary.setTextColor(if (series.firstOrNull()?.points?.any { it.second != null } == true) palette.accent else palette.muted)
    }

    private fun rangeButton(label: String, range: ActivityRange?) = Button(this).apply {
        text = label
        textSize = 10f
        minWidth = dp(50)
        applyStarIntelTheme(palette)
        setOnClickListener { selectedRange = range; render() }
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

internal class ActivityTimelineView(
    context: android.content.Context,
    private val palette: StarIntelPalette,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val colors = intArrayOf(
        palette.accent,
        Color.rgb(198, 91, 255),
        Color.rgb(126, 255, 128),
        Color.rgb(255, 190, 70),
    )
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(40, 50, 60)
        strokeWidth = density
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = 9f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private var series: List<ActivitySeries> = emptyList()
    private var range: ActivityRange = ActivityRange.H1

    fun setSeries(value: List<ActivitySeries>, selectedRange: ActivityRange) {
        series = value
        range = selectedRange
        contentDescription = "Document activity line graph for ${range.label}"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val left = 9f * density
        val right = width - 9f * density
        val top = 22f * density
        val bottom = height - 20f * density
        for (step in 0..3) {
            val y = top + (bottom - top) * step / 3f
            canvas.drawLine(left, y, right, y, grid)
        }
        canvas.drawText(range.label, left, 14f * density, label)
        val all = series.flatMap { it.points }
        val values = all.mapNotNull { it.second }
        if (values.isEmpty()) {
            label.textAlign = Paint.Align.CENTER
            canvas.drawText("COLLECTING REAL SAMPLES", width / 2f, height / 2f, label)
            label.textAlign = Paint.Align.LEFT
            return
        }
        val minTime = all.minOf { it.first }
        val maxTime = all.maxOf { it.first }.coerceAtLeast(minTime + 1L)
        val maxValue = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
        series.forEachIndexed { index, item ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors[index % colors.size]
                strokeWidth = (if (index == 0) 2.6f else 1.8f) * density
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = Path()
            var open = false
            item.points.forEach { (timestamp, value) ->
                if (value == null) {
                    open = false
                } else {
                    val x = left + (right - left) * (timestamp - minTime).toFloat() / (maxTime - minTime).toFloat()
                    val y = bottom - (bottom - top) * value.toFloat() / maxValue.toFloat()
                    if (open) path.lineTo(x, y) else path.moveTo(x, y)
                    open = true
                }
            }
            canvas.drawPath(path, paint)
            item.points.forEach { (timestamp, value) ->
                if (value != null) {
                    val x = left + (right - left) * (timestamp - minTime).toFloat() / (maxTime - minTime).toFloat()
                    val y = bottom - (bottom - top) * value.toFloat() / maxValue.toFloat()
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(x, y, (if (index == 0) 3.2f else 2.4f) * density, paint)
                    paint.style = Paint.Style.STROKE
                }
            }
        }
        label.textAlign = Paint.Align.LEFT
        canvas.drawText("-${range.label}", left, height - 5f * density, label)
        label.textAlign = Paint.Align.RIGHT
        canvas.drawText("NOW", right, height - 5f * density, label)
        label.textAlign = Paint.Align.LEFT
    }
}
