package actor.starintel.quasar.field

import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.pow

internal class GeoGraphView(context: Context) : View(context) {
    var onSelectionChanged: (GeoFeature?) -> Unit = {}
    private var graph = GeoGraph(emptyList(), emptySet())
    private var selected: GeoFeature? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = context.dp(10).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        setBackgroundColor(QuasarDesign.background)
        contentDescription = "Linked geo-document graph"
    }

    fun setFeatures(features: List<GeoFeature>) {
        graph = GeoGraph.from(features)
        if (selected?.id !in graph.nodes.map(GeoFeature::id)) select(null)
        contentDescription = "Linked geo-document graph with ${graph.nodes.size} nodes and ${graph.edges.size} links."
        invalidate()
    }

    fun select(feature: GeoFeature?) {
        selected = feature
        onSelectionChanged(feature)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (graph.nodes.isEmpty()) {
            textPaint.color = QuasarDesign.muted
            textPaint.textSize = context.dp(13).toFloat()
            canvas.drawText("NO LINKED GEO DOCUMENTS", width / 2f, height / 2f, textPaint)
            return
        }
        val positions = GeoGraphLayout.layout(graph, width.toFloat(), height.toFloat())
        paint.color = QuasarDesign.border
        paint.strokeWidth = context.dp(1).toFloat()
        graph.edges.forEach { edge ->
            val from = positions[edge.from] ?: return@forEach
            val to = positions[edge.to] ?: return@forEach
            canvas.drawLine(from.x, from.y, to.x, to.y, paint)
        }
        graph.nodes.forEach { feature ->
            val point = positions[feature.id] ?: return@forEach
            val active = feature.id == selected?.id
            if (active) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = Color.WHITE
                canvas.drawCircle(point.x, point.y, context.dp(17).toFloat(), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = FieldMapView.layerColor(feature.layer)
            canvas.drawCircle(point.x, point.y, context.dp(11).toFloat(), paint)
            textPaint.color = QuasarDesign.text
            textPaint.textSize = context.dp(9).toFloat()
            canvas.drawText(feature.title.take(18), point.x, point.y + context.dp(28), textPaint)
        }
        paint.style = Paint.Style.FILL
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val positions = GeoGraphLayout.layout(graph, width.toFloat(), height.toFloat())
        val hit = graph.nodes.minByOrNull { feature ->
            val point = positions[feature.id] ?: return@minByOrNull Float.MAX_VALUE
            (point.x - event.x).pow(2) + (point.y - event.y).pow(2)
        }?.takeIf { feature ->
            val point = positions[feature.id] ?: return@takeIf false
            (point.x - event.x).pow(2) + (point.y - event.y).pow(2) <= context.dp(32).toFloat().pow(2)
        }
        select(hit)
        return true
    }
}
