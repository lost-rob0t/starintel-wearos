package actor.starintel.quasar.field

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class GeoEdge(val from: String, val to: String) {
    init {
        require(from <= to) { "GeoEdge identity must be ordered" }
    }

    companion object {
        fun normalized(first: String, second: String): GeoEdge =
            if (first <= second) GeoEdge(first, second) else GeoEdge(second, first)
    }
}

data class GeoGraph(val nodes: List<GeoFeature>, val edges: Set<GeoEdge>) {
    companion object {
        fun from(features: List<GeoFeature>): GeoGraph {
            val ordered = features.distinctBy(GeoFeature::id).sortedBy(GeoFeature::id)
            val ids = ordered.mapTo(mutableSetOf(), GeoFeature::id)
            val edges = ordered.flatMap { feature ->
                feature.links.filter { it in ids && it != feature.id }.map { GeoEdge.normalized(feature.id, it) }
            }.toSet()
            return GeoGraph(ordered, edges)
        }
    }
}

data class GraphPoint(val x: Float, val y: Float)

object GeoGraphLayout {
    fun layout(graph: GeoGraph, width: Float, height: Float): Map<String, GraphPoint> {
        if (graph.nodes.isEmpty() || width <= 0f || height <= 0f) return emptyMap()
        if (graph.nodes.size == 1) return mapOf(graph.nodes.first().id to GraphPoint(width / 2f, height / 2f))
        val centerX = width / 2f
        val centerY = height / 2f
        val radiusX = (width / 2f - 60f).coerceAtLeast(1f)
        val radiusY = (height / 2f - 60f).coerceAtLeast(1f)
        return graph.nodes.mapIndexed { index, feature ->
            val radians = -PI / 2 + (2 * PI * index / graph.nodes.size)
            feature.id to GraphPoint(
                (centerX + cos(radians).toFloat() * radiusX),
                (centerY + sin(radians).toFloat() * radiusY),
            )
        }.toMap()
    }
}
