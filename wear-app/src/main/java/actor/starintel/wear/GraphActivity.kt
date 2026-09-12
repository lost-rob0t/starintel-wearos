package actor.starintel.wear

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.GraphEdge
import actor.starintel.wear.data.GraphNode
import actor.starintel.wear.data.RelationGraphLoader
import actor.starintel.wear.data.RelationNeighborhood
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.StarIntelPalette
import actor.starintel.wear.ui.applyStarIntelInput
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class GraphActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var loader: RelationGraphLoader
    private lateinit var rootInput: EditText
    private lateinit var status: TextView
    private lateinit var graph: RelationGraphView
    private lateinit var open: Button
    private lateinit var center: Button
    private var selected: GraphNode? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loader = RelationGraphLoader.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "GRAPH"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "StarIntel relation neighborhood"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        rootInput = EditText(this).apply {
            setSingleLine(true)
            hint = "document id or search"
            textSize = 11f
            inputType = InputType.TYPE_CLASS_TEXT
            setText(intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty())
            applyStarIntelInput(palette)
        }
        root.addView(rootInput, matchWrap(top = 6))
        root.addView(Button(this).apply {
            text = "LOAD NEIGHBORHOOD"
            applyStarIntelTheme(palette)
            setOnClickListener { load(rootInput.text.toString()) }
        }, matchWrap(top = 3))

        status = TextView(this).apply {
            text = "Choose a document to graph"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        graph = RelationGraphView(this, palette).apply {
            onNodeSelected = { node -> select(node) }
        }
        root.addView(
            graph,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280)).apply { topMargin = dp(6) },
        )

        open = Button(this).apply {
            text = "OPEN DOCUMENT"
            isEnabled = false
            applyStarIntelTheme(palette)
            setOnClickListener {
                selected?.let { node ->
                    startActivity(
                        Intent(this@GraphActivity, DocumentViewerActivity::class.java)
                            .putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, node.id),
                    )
                }
            }
        }
        root.addView(open, matchWrap(top = 4))

        center = Button(this).apply {
            text = "CENTER + EXPAND"
            isEnabled = false
            applyStarIntelTheme(palette)
            setOnClickListener { selected?.let { load(it.id) } }
        }
        root.addView(center, matchWrap(top = 3))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        if (rootInput.text.isNotBlank()) load(rootInput.text.toString())
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(value: String) {
        val requested = value.trim()
        if (requested.isBlank()) {
            status.text = "Choose a document"
            status.setTextColor(palette.warning)
            return
        }
        status.text = "Loading bounded relation graph…"
        status.setTextColor(palette.muted)
        selected = null
        open.isEnabled = false
        center.isEnabled = false
        scope.launch {
            val neighborhood = loader.load(requested)
            render(neighborhood)
        }
    }

    private fun render(neighborhood: RelationNeighborhood) {
        graph.setGraph(neighborhood)
        if (neighborhood.rootId.isNotBlank()) rootInput.setText(neighborhood.rootId)
        status.text = when {
            neighborhood.error != null && neighborhood.nodes.isEmpty() -> neighborhood.error
            neighborhood.error != null -> "${neighborhood.nodes.size} node${plural(neighborhood.nodes.size)} · ${neighborhood.error}"
            neighborhood.edges.isEmpty() -> "${neighborhood.nodes.size} node${plural(neighborhood.nodes.size)} · no relations found"
            else -> "${neighborhood.nodes.size} nodes · ${neighborhood.edges.size} directed relation${plural(neighborhood.edges.size)}"
        }
        status.setTextColor(if (neighborhood.error == null) palette.accent else palette.warning)
        neighborhood.nodes.firstOrNull { it.id == neighborhood.rootId }?.let(::select)
    }

    private fun select(node: GraphNode) {
        selected = node
        graph.select(node.id)
        open.isEnabled = node.dtype != "unresolved"
        center.isEnabled = true
        status.text = "${node.dtype} · ${node.label}"
        status.setTextColor(palette.text)
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
    }
}

private class RelationGraphView(
    context: android.content.Context,
    private val palette: StarIntelPalette,
) : View(context) {
    var onNodeSelected: ((GraphNode) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        strokeWidth = dp(1.2f)
        style = Paint.Style.STROKE
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        strokeWidth = dp(2.2f)
        style = Paint.Style.STROKE
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.surface
        style = Paint.Style.FILL
    }
    private val nodeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.accent
        strokeWidth = dp(1.5f)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.text
        textSize = dp(8.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val edgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = dp(6.5f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.muted
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private var graph = RelationNeighborhood("", emptyList(), emptyList())
    private var selectedId: String? = null
    private val positions = mutableMapOf<String, Pair<Float, Float>>()

    fun setGraph(value: RelationNeighborhood) {
        graph = value
        selectedId = value.rootId.takeIf { it.isNotBlank() }
        requestLayout()
        invalidate()
    }

    fun select(id: String) {
        selectedId = id
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(palette.background)
        if (graph.nodes.isEmpty()) {
            canvas.drawText("NO GRAPH", width / 2f, height / 2f, emptyPaint)
            return
        }

        layoutNodes()
        graph.edges.forEach { edge -> drawEdge(canvas, edge) }
        graph.nodes.forEach { node -> drawNode(canvas, node) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val hit = graph.nodes.minByOrNull { node ->
            val point = positions[node.id] ?: return@minByOrNull Float.MAX_VALUE
            val dx = point.first - event.x
            val dy = point.second - event.y
            dx * dx + dy * dy
        } ?: return true
        val point = positions[hit.id] ?: return true
        val dx = point.first - event.x
        val dy = point.second - event.y
        if (dx * dx + dy * dy <= dp(30f) * dp(30f)) onNodeSelected?.invoke(hit)
        return true
    }

    private fun layoutNodes() {
        positions.clear()
        val cx = width / 2f
        val cy = height / 2f
        val root = graph.nodes.firstOrNull { it.id == graph.rootId } ?: graph.nodes.first()
        positions[root.id] = cx to cy
        val neighbors = graph.nodes.filterNot { it.id == root.id }
        val nodeRadius = dp(22f)
        val safeRadius = (min(width, height) / 2f - nodeRadius - dp(10f)).coerceAtLeast(dp(48f))
        neighbors.forEachIndexed { index, node ->
            val angle = -Math.PI / 2.0 + (Math.PI * 2.0 * index / neighbors.size.coerceAtLeast(1))
            positions[node.id] =
                (cx + cos(angle).toFloat() * safeRadius) to (cy + sin(angle).toFloat() * safeRadius)
        }
    }

    private fun drawEdge(canvas: Canvas, edge: GraphEdge) {
        val from = positions[edge.source] ?: return
        val to = positions[edge.target] ?: return
        val paint = if (edge.source == selectedId || edge.target == selectedId) accentPaint else edgePaint
        val path = Path().apply {
            moveTo(from.first, from.second)
            lineTo(to.first, to.second)
        }
        canvas.drawPath(path, paint)

        val mx = (from.first + to.first) / 2f
        val my = (from.second + to.second) / 2f
        canvas.drawText(edge.predicate.take(12), mx, my - dp(3f), edgeTextPaint)
        if (edge.directed) drawArrow(canvas, from.first, from.second, to.first, to.second, paint)
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val nodeInset = dp(24f)
        val tipX = x2 - cos(angle).toFloat() * nodeInset
        val tipY = y2 - sin(angle).toFloat() * nodeInset
        val wing = dp(7f)
        val a1 = angle + Math.PI * 0.82
        val a2 = angle - Math.PI * 0.82
        canvas.drawLine(tipX, tipY, tipX + cos(a1).toFloat() * wing, tipY + sin(a1).toFloat() * wing, paint)
        canvas.drawLine(tipX, tipY, tipX + cos(a2).toFloat() * wing, tipY + sin(a2).toFloat() * wing, paint)
    }

    private fun drawNode(canvas: Canvas, node: GraphNode) {
        val point = positions[node.id] ?: return
        val selected = node.id == selectedId
        val radius = if (node.id == graph.rootId) dp(24f) else dp(20f)
        nodeStroke.color = when {
            selected -> palette.text
            node.dtype == "unresolved" -> palette.warning
            else -> palette.accent
        }
        nodeStroke.strokeWidth = if (selected) dp(2.5f) else dp(1.4f)

        when (node.dtype) {
            "org", "document", "url" -> {
                val rect = RectF(point.first - radius, point.second - radius * .75f, point.first + radius, point.second + radius * .75f)
                canvas.drawRoundRect(rect, dp(6f), dp(6f), nodePaint)
                canvas.drawRoundRect(rect, dp(6f), dp(6f), nodeStroke)
            }
            "event", "meeting" -> {
                val diamond = Path().apply {
                    moveTo(point.first, point.second - radius)
                    lineTo(point.first + radius, point.second)
                    lineTo(point.first, point.second + radius)
                    lineTo(point.first - radius, point.second)
                    close()
                }
                canvas.drawPath(diamond, nodePaint)
                canvas.drawPath(diamond, nodeStroke)
            }
            else -> {
                canvas.drawCircle(point.first, point.second, radius, nodePaint)
                canvas.drawCircle(point.first, point.second, radius, nodeStroke)
            }
        }
        canvas.drawText(node.label.take(8), point.first, point.second + dp(3f), textPaint)
    }

    private fun dp(value: Float): Float = value * density
}
