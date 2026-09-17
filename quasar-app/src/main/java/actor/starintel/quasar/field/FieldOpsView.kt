package actor.starintel.quasar.field

import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

internal class FieldOpsView(
    context: Context,
    private val onServerSearch: (String) -> Unit,
) : LinearLayout(context), AutoCloseable {
    private val map = FieldMapView(context)
    private val graph = GeoGraphView(context)
    private val mapStatus = QuasarDesign.pill(context, BasemapState.UNAVAILABLE.label, QuasarDesign.coral)
    private val dataStatus = QuasarDesign.pill(context, "LOCAL ONLY", QuasarDesign.amber)
    private val coordinate = QuasarDesign.body(context, GeoMath.coordinateReadout(GeoPoint(0.0, 0.0)), QuasarDesign.text, 11f)
    private val zoomReadout = QuasarDesign.eyebrow(context, "Z3", QuasarDesign.amber)
    private val count = QuasarDesign.eyebrow(context, "0 GEO DOCS", QuasarDesign.muted)
    private val search = QuasarDesign.field(context, hint = "Search loaded geo docs…")
    private val mapMode = compactControl("MAP", active = true)
    private val graphMode = compactControl("LINKS", active = false)
    private val layerControls = linkedMapOf<GeoLayer, TextView>()
    private val visibleLayers = GeoLayer.entries.toMutableSet()
    private val detail = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(14))
        setBackgroundColor(QuasarDesign.panel)
    }
    private var allFeatures: List<GeoFeature> = emptyList()
    private var selected: GeoFeature? = null
    private var fieldCenter = GeoPoint(0.0, 0.0)
    private var syncingSelection = false

    init {
        orientation = VERTICAL
        setBackgroundColor(QuasarDesign.background)
        addView(commandBar(), match())
        addView(layerBar(), match())
        addView(surface(), LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(detail, match())

        map.onViewportChanged = { center, zoom ->
            fieldCenter = center
            coordinate.text = GeoMath.coordinateReadout(center)
            zoomReadout.text = "Z$zoom"
            selected?.let(::renderDetail)
        }
        map.onSelectionChanged = { feature -> select(feature, fromMap = true) }
        map.onBasemapChanged = { state ->
            mapStatus.text = state.label
            mapStatus.setTextColor(if (state == BasemapState.ONLINE) QuasarDesign.lime else if (state == BasemapState.CACHED_ONLY) QuasarDesign.amber else QuasarDesign.coral)
        }
        graph.onSelectionChanged = { feature -> select(feature, fromGraph = true) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(value: Editable?) = Unit
        })
        mapMode.setOnClickListener { showMap() }
        graphMode.setOnClickListener { showGraph() }
        renderEmptyDetail()
    }

    fun setFeatures(features: List<GeoFeature>) {
        allFeatures = features
        applyFilter()
    }

    fun setDataStatus(label: String, ready: Boolean) {
        dataStatus.text = label.uppercase().take(24)
        dataStatus.setTextColor(if (ready) QuasarDesign.lime else QuasarDesign.amber)
    }

    fun showDataError(label: String) {
        dataStatus.text = "DATA DEGRADED"
        dataStatus.setTextColor(QuasarDesign.coral)
        if (allFeatures.isEmpty()) {
            detail.removeAllViews()
            detail.addView(QuasarDesign.eyebrow(context, "NO GEO DATA", QuasarDesign.coral))
            detail.addView(QuasarDesign.body(context, label, QuasarDesign.muted, 12f), QuasarDesign.match(top = context.dp(5)))
        }
    }

    private fun commandBar(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(7))
        val titleRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                addView(QuasarDesign.eyebrow(context, "Field intelligence", QuasarDesign.amber))
                addView(QuasarDesign.title(context, "Quasar tactical", 22f), QuasarDesign.match(top = context.dp(2)))
            }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(dataStatus)
            addView(mapStatus, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(6)
            })
        }
        addView(titleRow, match())
        val searchRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(search, LayoutParams(0, context.dp(48), 1f))
            addView(compactControl("SYNC", active = true).apply {
                contentDescription = "Search Star server for geo documents"
                setOnClickListener { onServerSearch(search.text.toString().trim()) }
            }, LayoutParams(context.dp(64), context.dp(48)).apply { marginStart = context.dp(7) })
        }
        addView(searchRow, QuasarDesign.match(top = context.dp(8)))
    }

    private fun layerBar(): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(QuasarDesign.panel)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(7), context.dp(10), context.dp(7))
            addView(mapMode, LayoutParams(context.dp(58), context.dp(38)))
            addView(graphMode, LayoutParams(context.dp(64), context.dp(38)).apply { marginStart = context.dp(5) })
            addView(divider(), LayoutParams(context.dp(1), context.dp(24)).apply {
                marginStart = context.dp(10)
                marginEnd = context.dp(10)
            })
            GeoLayer.entries.forEach { layer ->
                val control = layerControl(layer)
                layerControls[layer] = control
                addView(control, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(38)).apply { marginEnd = context.dp(5) })
            }
        })
    }

    private fun surface(): View = FrameLayout(context).apply {
        addView(map, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(graph, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            graph.visibility = View.GONE
        })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(squareControl("+", "Zoom in") { map.zoomIn() })
            addView(squareControl("−", "Zoom out") { map.zoomOut() }, QuasarDesign.match(top = context.dp(5)))
            addView(squareControl("◎", "Recenter selected document") { map.recenterSelection() }, QuasarDesign.match(top = context.dp(5)))
        }, FrameLayout.LayoutParams(context.dp(46), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = context.dp(12)
            marginEnd = context.dp(12)
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(10), context.dp(7), context.dp(10), context.dp(7))
            setBackgroundColor(0xdd11121b.toInt())
            addView(coordinate, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(zoomReadout)
            addView(count, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(10)
            })
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            marginStart = context.dp(8)
            marginEnd = context.dp(8)
            bottomMargin = context.dp(28)
        })
    }

    private fun applyFilter() {
        val visible = GeoFeatureFilter.apply(allFeatures, search.text.toString(), visibleLayers)
        map.setFeatures(visible)
        graph.setFeatures(visible)
        count.text = "${visible.size} GEO DOC${if (visible.size == 1) "" else "S"}"
        selected?.takeIf { it !in visible }?.let { select(null) }
    }

    private fun select(feature: GeoFeature?, fromMap: Boolean = false, fromGraph: Boolean = false) {
        if (syncingSelection) return
        syncingSelection = true
        selected = feature
        if (!fromMap) map.select(feature)
        if (!fromGraph) graph.select(feature)
        if (feature == null) renderEmptyDetail() else renderDetail(feature)
        syncingSelection = false
    }

    private fun renderEmptyDetail() {
        detail.removeAllViews()
        detail.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(QuasarDesign.eyebrow(context, "NO SELECTION", QuasarDesign.muted), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(QuasarDesign.body(context, "Tap a marker or graph node", QuasarDesign.muted, 11f))
        })
    }

    private fun renderDetail(feature: GeoFeature) {
        detail.removeAllViews()
        val distance = GeoMath.distanceMeters(fieldCenter, feature.point)
        val bearing = GeoMath.initialBearingDegrees(fieldCenter, feature.point)
        detail.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(QuasarDesign.eyebrow(context, feature.dtype, FieldMapView.layerColor(feature.layer)), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(QuasarDesign.pill(context, "${GeoMath.distanceReadout(distance)} · ${GeoMath.bearingReadout(bearing)}", QuasarDesign.amber))
        })
        detail.addView(QuasarDesign.title(context, feature.title, 18f), QuasarDesign.match(top = context.dp(5)))
        detail.addView(QuasarDesign.body(context, "${feature.id}  ·  ${GeoMath.coordinateReadout(feature.point)}", QuasarDesign.muted, 10.5f), QuasarDesign.match(top = context.dp(4)))
        val provenance = listOfNotNull(
            feature.source.takeIf(String::isNotBlank)?.let { "Source: $it" },
            feature.observedAt.takeIf(String::isNotBlank)?.let { "Observed: $it" },
            feature.links.takeIf(List<String>::isNotEmpty)?.let { "${it.size} linked document${if (it.size == 1) "" else "s"}" },
        ).joinToString("  ·  ")
        if (feature.summary.isNotBlank()) {
            detail.addView(QuasarDesign.body(context, feature.summary, QuasarDesign.text, 12f), QuasarDesign.match(top = context.dp(6)))
        }
        if (provenance.isNotBlank()) {
            detail.addView(QuasarDesign.body(context, provenance, QuasarDesign.muted, 10.5f), QuasarDesign.match(top = context.dp(5)))
        }
    }

    private fun showMap() {
        map.visibility = View.VISIBLE
        graph.visibility = View.GONE
        styleCompact(mapMode, true)
        styleCompact(graphMode, false)
    }

    private fun showGraph() {
        map.visibility = View.GONE
        graph.visibility = View.VISIBLE
        styleCompact(mapMode, false)
        styleCompact(graphMode, true)
    }

    private fun layerControl(layer: GeoLayer): TextView = compactControl(layer.label.uppercase(), active = true).apply {
        contentDescription = "${layer.label} layer visible"
        setOnClickListener {
            if (!visibleLayers.remove(layer)) visibleLayers.add(layer)
            val active = layer in visibleLayers
            styleCompact(this, active, FieldMapView.layerColor(layer))
            contentDescription = "${layer.label} layer ${if (active) "visible" else "hidden"}"
            applyFilter()
        }
        styleCompact(this, true, FieldMapView.layerColor(layer))
    }

    private fun compactControl(label: String, active: Boolean): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(context.dp(11), context.dp(5), context.dp(11), context.dp(5))
        isClickable = true
        isFocusable = true
        minHeight = context.dp(38)
        styleCompact(this, active)
    }

    private fun styleCompact(view: TextView, active: Boolean, accent: Int = QuasarDesign.amber) {
        view.setTextColor(if (active) accent else QuasarDesign.muted)
        view.setBackgroundColor(if (active) 0xff20202a.toInt() else 0xff11121b.toInt())
        view.alpha = if (active) 1f else .7f
    }

    private fun squareControl(label: String, description: String, action: () -> Unit) = compactControl(label, active = true).apply {
        textSize = 20f
        contentDescription = description
        setOnClickListener { action() }
        layoutParams = LayoutParams(context.dp(46), context.dp(46))
    }

    private fun divider() = View(context).apply { setBackgroundColor(QuasarDesign.border) }

    private fun match() = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    override fun close() = map.close()
}
