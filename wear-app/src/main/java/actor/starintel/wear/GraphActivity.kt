package actor.starintel.wear

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.ActivityHistoryStore
import actor.starintel.wear.data.ActivityPoint
import actor.starintel.wear.data.ActivityRange
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.compactCount
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.StarIntelPalette
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GraphActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: StarIntelRepository
    private lateinit var history: ActivityHistoryStore
    private lateinit var graph: ActivityGraphView
    private lateinit var status: TextView
    private lateinit var refresh: Button
    private var range = ActivityRange.H24

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = StarIntelRepository.get(this)
        history = ActivityHistoryStore.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(palette.background)
        }

        root.addView(TextView(this).apply {
            text = "ACTIVITY"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())

        status = TextView(this).apply {
            text = "Loading activity history…"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        graph = ActivityGraphView(this, palette)
        root.addView(graph, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(210),
        ).apply { topMargin = dp(8) })

        val ranges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        ActivityRange.entries.forEach { candidate ->
            ranges.addView(Button(this).apply {
                text = candidate.label
                textSize = 9f
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 0, 0)
                applyStarIntelTheme(palette)
                setOnClickListener {
                    range = candidate
                    renderHistory()
                }
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        root.addView(ranges, matchWrap(top = 4))

        refresh = Button(this).apply {
            text = "SYNC + REDRAW"
            applyStarIntelTheme(palette)
            setOnClickListener { sync() }
        }
        root.addView(refresh, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sync() {
        refresh.isEnabled = false
        status.text = "Syncing latest StarIntel stats…"
        status.setTextColor(palette.muted)
        scope.launch {
            repository.snapshot(forceRefresh = true)
            renderHistory()
            refresh.isEnabled = true
        }
    }

    private fun renderHistory() {
        val nowSeconds = System.currentTimeMillis() / 1000L
        val points = history.points(range, nowSeconds)
        val values = points.mapNotNull { it.documentsAdded }
        val total = values.sum()
        status.text = if (values.isEmpty()) {
            "${range.label} · collecting samples · graph fills as stats refresh"
        } else {
            "${range.label} · +${total.compactCount()} docs · ${values.size} intervals"
        }
        status.setTextColor(if (values.isEmpty()) palette.muted else palette.accent)
        graph.setData(points, range, nowSeconds)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

private class ActivityGraphView(
    context: android.content.Context,
    private val palette: StarIntelPalette,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.surface
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = 9f * density
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = 11f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var points: List<ActivityPoint> = emptyList()
    private var range: ActivityRange = ActivityRange.H24
    private var nowSeconds: Long = 0L

    fun setData(points: List<ActivityPoint>, range: ActivityRange, nowSeconds: Long) {
        this.points = points
        this.range = range
        this.nowSeconds = nowSeconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)

        val plot = RectF(dp(24f), dp(10f), width - dp(10f), height - dp(28f))
        if (plot.width() <= 0f || plot.height() <= 0f) return

        repeat(4) { index ->
            val y = plot.top + plot.height() * index / 3f
            canvas.drawLine(plot.left, y, plot.right, y, gridPaint)
        }

        val valid = points.mapNotNull { point -> point.documentsAdded?.let { point to it } }
        if (valid.isEmpty()) {
            canvas.drawText("NO ACTIVITY DATA", width / 2f, height / 2f, emptyPaint)
            canvas.drawText("${range.label} window", plot.left, height - dp(8f), labelPaint)
            return
        }

        val maxValue = valid.maxOf { it.second }.coerceAtLeast(1L)
        val startSeconds = nowSeconds - range.seconds
        val path = Path()
        var started = false

        points.forEach { point ->
            val value = point.documentsAdded
            if (value == null || point.gap || point.reset) {
                started = false
                return@forEach
            }

            val fractionX = ((point.epochSeconds - startSeconds).toDouble() / range.seconds.toDouble())
                .coerceIn(0.0, 1.0)
            val fractionY = (value.toDouble() / maxValue.toDouble()).coerceIn(0.0, 1.0)
            val x = plot.left + plot.width() * fractionX.toFloat()
            val y = plot.bottom - plot.height() * fractionY.toFloat()

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            canvas.drawCircle(x, y, dp(1.8f), pointPaint)
        }

        canvas.drawPath(path, linePaint)
        canvas.drawText(maxValue.compactCount(), dp(2f), plot.top + dp(7f), labelPaint)
        canvas.drawText("0", dp(12f), plot.bottom, labelPaint)
        canvas.drawText("-${range.label}", plot.left, height - dp(8f), labelPaint)
        labelPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("NOW", plot.right, height - dp(8f), labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
    }

    private fun dp(value: Float): Float = value * density
}
