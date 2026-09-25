package actor.starintel.quasar.field

import actor.starintel.android.config.StarIntelSharedConfig
import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal class FieldMapView(context: Context) : View(context), AutoCloseable {
    var onSelectionChanged: (GeoFeature?) -> Unit = {}
    var onViewportChanged: (GeoPoint, Int) -> Unit = { _, _ -> }
    var onBasemapChanged: (BasemapState) -> Unit = {}

    private val sharedConfig = StarIntelSharedConfig(context.applicationContext)
    private val tiles = OpenMapTileRepository(context) { state ->
        basemapState = state
        onBasemapChanged(state)
        invalidate()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textSize = context.dp(10).toFloat()
    }
    private var features: List<GeoFeature> = emptyList()
    private var selected: GeoFeature? = null
    private var center = GeoPoint(0.0, 0.0)
    private var zoom = 3
    private var didAnchorToData = false
    private var scaleAccumulator = 1f
    private var basemapState = BasemapState.UNAVAILABLE

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val world = WebMercator.project(center, zoom)
            center = WebMercator.unproject(
                WorldPoint(WebMercator.wrapWorldX(world.x + distanceX, zoom), world.y + distanceY),
                zoom,
            )
            notifyViewport()
            invalidate()
            return true
        }

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            val hit = features
                .map { it to screenPoint(it.point) }
                .filter { (_, point) -> point != null }
                .minByOrNull { (_, point) ->
                    val dx = point!!.first - event.x
                    val dy = point.second - event.y
                    dx * dx + dy * dy
                }
                ?.takeIf { (_, point) ->
                    val dx = point!!.first - event.x
                    val dy = point.second - event.y
                    dx * dx + dy * dy <= context.dp(34).toFloat().pow(2)
                }
                ?.first
            select(hit)
            return true
        }
    })

    private val scales = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAccumulator *= detector.scaleFactor
            val delta = log2(scaleAccumulator.toDouble()).roundToInt()
            if (delta != 0) {
                setZoom(zoom + delta)
                scaleAccumulator /= 2.0.pow(delta).toFloat()
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaleAccumulator = 1f
        }
    })

    init {
        setBackgroundColor(QuasarDesign.background)
        isFocusable = true
        contentDescription = "Interactive field map. Pinch to zoom, drag to pan, and tap a geo document for details."
    }

    fun setFeatures(value: List<GeoFeature>) {
        features = value
        if (!didAnchorToData && value.isNotEmpty()) {
            center = value.first().point
            zoom = 12
            didAnchorToData = true
            notifyViewport()
        }
        if (selected?.id !in value.map(GeoFeature::id)) select(null)
        invalidate()
    }

    /** Recenters the viewport on an externally supplied point without touching selection. */
    fun focus(point: GeoPoint, targetZoom: Int = 14) {
        center = point
        didAnchorToData = true
        zoom = targetZoom.coerceIn(OpenMapTilePolicy.minZoom, OpenMapTilePolicy.maxZoom)
        notifyViewport()
        invalidate()
    }

    fun select(feature: GeoFeature?) {
        selected = feature
        onSelectionChanged(feature)
        contentDescription = if (feature == null) {
            "Interactive field map with ${features.size} geo documents."
        } else {
            "Selected ${feature.title}, ${feature.dtype}, ${GeoMath.coordinateReadout(feature.point)}."
        }
        invalidate()
    }

    fun setZoom(value: Int) {
        zoom = value.coerceIn(OpenMapTilePolicy.minZoom, OpenMapTilePolicy.maxZoom)
        notifyViewport()
        invalidate()
    }

    fun zoomIn() = setZoom(zoom + 1)

    fun zoomOut() = setZoom(zoom - 1)

    fun recenterSelection() {
        selected?.let {
            center = it.point
            notifyViewport()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawGrid(canvas)
        drawTiles(canvas)
        drawFeatures(canvas)
        drawCrosshair(canvas)
        drawAttribution(canvas)
    }

    private fun drawTiles(canvas: Canvas) {
        val centerWorld = WebMercator.project(center, zoom)
        val left = centerWorld.x - width / 2.0
        val top = centerWorld.y - height / 2.0
        val firstX = floor(left / WebMercator.TILE_SIZE).toInt()
        val lastX = floor((left + width) / WebMercator.TILE_SIZE).toInt()
        val firstY = floor(top / WebMercator.TILE_SIZE).toInt()
        val lastY = floor((top + height) / WebMercator.TILE_SIZE).toInt()
        for (tileY in firstY..lastY) {
            for (tileX in firstX..lastX) {
                val bitmap = tiles.tile(zoom, tileX, tileY) ?: continue
                val x = (tileX * WebMercator.TILE_SIZE - left).roundToInt()
                val y = (tileY * WebMercator.TILE_SIZE - top).roundToInt()
                paint.alpha = 178
                canvas.drawBitmap(bitmap, null, Rect(x, y, x + WebMercator.TILE_SIZE, y + WebMercator.TILE_SIZE), paint)
            }
        }
        paint.alpha = 255
    }

    private fun drawGrid(canvas: Canvas) {
        paint.color = Color.rgb(30, 31, 38)
        paint.strokeWidth = 1f
        val step = context.dp(48)
        var x = (width / 2) % step
        while (x < width) { canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), paint); x += step }
        var y = (height / 2) % step
        while (y < height) { canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), paint); y += step }
    }

    private fun drawFeatures(canvas: Canvas) {
        features.forEach { feature ->
            val point = screenPoint(feature.point) ?: return@forEach
            val active = feature.id == selected?.id
            val radius = context.dp(if (active) 10 else 7).toFloat()
            if (active) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = context.dp(2).toFloat()
                paint.color = Color.WHITE
                canvas.drawCircle(point.first, point.second, radius + context.dp(6), paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = layerColor(feature.layer)
            if (feature.layer == GeoLayer.SIGNALS || feature.layer == GeoLayer.TARGETS) {
                val path = Path().apply {
                    moveTo(point.first, point.second - radius)
                    lineTo(point.first + radius, point.second)
                    lineTo(point.first, point.second + radius)
                    lineTo(point.first - radius, point.second)
                    close()
                }
                canvas.drawPath(path, paint)
            } else {
                canvas.drawCircle(point.first, point.second, radius, paint)
            }
            if (active || zoom >= 14) {
                labelPaint.color = QuasarDesign.text
                canvas.drawText(feature.title.take(28), point.first + radius + context.dp(5), point.second + context.dp(4), labelPaint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawCrosshair(canvas: Canvas) {
        paint.color = Color.argb(180, 255, 255, 255)
        paint.strokeWidth = 1f
        val arm = context.dp(8).toFloat()
        canvas.drawLine(width / 2f - arm, height / 2f, width / 2f + arm, height / 2f, paint)
        canvas.drawLine(width / 2f, height / 2f - arm, width / 2f, height / 2f + arm, paint)
    }

    private fun drawAttribution(canvas: Canvas) {
        labelPaint.textSize = context.dp(9).toFloat()
        labelPaint.color = Color.argb(220, 230, 230, 230)
        val attribution = sharedConfig.get(StarIntelSharedConfig.KEY_MAP_ATTRIBUTION, "StarIntel Maps")
        val label = "$attribution  ·  z$zoom"
        canvas.drawText(label, context.dp(8).toFloat(), height - context.dp(8).toFloat(), labelPaint)
    }

    private fun screenPoint(point: GeoPoint): Pair<Float, Float>? {
        val centerWorld = WebMercator.project(center, zoom)
        val target = WebMercator.project(point, zoom)
        val worldWidth = WebMercator.TILE_SIZE * 2.0.pow(zoom)
        var deltaX = target.x - centerWorld.x
        if (deltaX > worldWidth / 2) deltaX -= worldWidth
        if (deltaX < -worldWidth / 2) deltaX += worldWidth
        val x = width / 2f + deltaX.toFloat()
        val y = height / 2f + (target.y - centerWorld.y).toFloat()
        if (x !in -80f..(width + 80f) || y !in -80f..(height + 80f)) return null
        return x to y
    }

    private fun notifyViewport() = onViewportChanged(center, zoom)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scales.onTouchEvent(event)
        val gestureHandled = gestures.onTouchEvent(event)
        return scaleHandled || gestureHandled || super.onTouchEvent(event)
    }

    override fun close() = tiles.close()

    companion object {
        fun layerColor(layer: GeoLayer): Int = when (layer) {
            GeoLayer.PLACES -> QuasarDesign.amber
            GeoLayer.EVENTS -> QuasarDesign.coral
            GeoLayer.SIGNALS -> QuasarDesign.cyan
            GeoLayer.PEOPLE -> QuasarDesign.pink
            GeoLayer.TARGETS -> QuasarDesign.lime
            GeoLayer.DOCUMENTS -> QuasarDesign.muted
        }
    }
}
