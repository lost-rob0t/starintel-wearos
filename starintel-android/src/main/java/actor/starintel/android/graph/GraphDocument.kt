package actor.starintel.android.graph

import org.json.JSONArray
import org.json.JSONObject

data class GraphPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite()) { "Graph coordinates must be finite" }
    }
}

data class GraphNode(
    val id: String,
    val documentId: String,
    val label: String,
    val dtype: String,
    val position: GraphPoint,
) {
    init {
        require(ID.matches(id)) { "Invalid graph node id" }
        require(documentId.isNotBlank() && documentId.length <= 512) { "Invalid document id" }
        require(label.length <= 240) { "Graph label is too long" }
        require(DTYPE.matches(dtype)) { "Invalid document type" }
    }

    companion object {
        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,159}")
        private val DTYPE = Regex("[a-z][a-z0-9-]{0,63}")
    }
}

data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val predicate: String,
) {
    init {
        require(id.isNotBlank() && id.length <= 240) { "Invalid graph edge id" }
        require(source != target) { "Graph self-edges require an explicit server relation" }
        require(PREDICATE.matches(predicate)) { "Invalid graph predicate" }
    }

    companion object {
        private val PREDICATE = Regex("[a-z][a-z0-9._:-]{0,127}")
    }
}

data class GraphDocument(
    val id: String,
    val name: String,
    val revision: Long,
    val nodes: Map<String, GraphNode>,
    val edges: Map<String, GraphEdge>,
) {
    init {
        require(id.isNotBlank() && id.length <= 160) { "Invalid graph id" }
        require(name.isNotBlank() && name.length <= 240) { "Invalid graph name" }
        require(revision >= 0L) { "Invalid graph revision" }
        require(nodes.size <= MAX_NODES) { "Graph exceeds $MAX_NODES nodes" }
        require(edges.size <= MAX_EDGES) { "Graph exceeds $MAX_EDGES edges" }
        require(nodes.keys == nodes.values.map(GraphNode::id).toSet()) { "Graph node keys disagree" }
        require(edges.keys == edges.values.map(GraphEdge::id).toSet()) { "Graph edge keys disagree" }
        require(edges.values.all { it.source in nodes && it.target in nodes }) { "Graph edge endpoint is missing" }
    }

    fun apply(command: GraphCommand): GraphDocument = command.applyTo(this).copy(revision = revision + 1)

    fun toJson(): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("id", id)
        .put("name", name)
        .put("revision", revision)
        .put("nodes", JSONArray().apply {
            nodes.values.sortedBy(GraphNode::id).forEach { node ->
                put(JSONObject()
                    .put("id", node.id)
                    .put("documentId", node.documentId)
                    .put("label", node.label)
                    .put("dtype", node.dtype)
                    .put("x", node.position.x.toDouble())
                    .put("y", node.position.y.toDouble()))
            }
        })
        .put("edges", JSONArray().apply {
            edges.values.sortedBy(GraphEdge::id).forEach { edge ->
                put(JSONObject()
                    .put("id", edge.id)
                    .put("source", edge.source)
                    .put("target", edge.target)
                    .put("predicate", edge.predicate))
            }
        })

    companion object {
        const val VERSION = 1
        const val MAX_NODES = 10_000
        const val MAX_EDGES = 40_000

        fun empty(id: String = "local", name: String = "Local graph") =
            GraphDocument(id, name, 0, emptyMap(), emptyMap())

        fun fromJson(raw: JSONObject): GraphDocument {
            require(raw.optInt("version", -1) == VERSION) { "Unsupported graph document version" }
            val nodeRows = raw.optJSONArray("nodes") ?: JSONArray()
            val edgeRows = raw.optJSONArray("edges") ?: JSONArray()
            require(nodeRows.length() <= MAX_NODES) { "Graph exceeds $MAX_NODES nodes" }
            require(edgeRows.length() <= MAX_EDGES) { "Graph exceeds $MAX_EDGES edges" }
            val nodes = linkedMapOf<String, GraphNode>()
            for (index in 0 until nodeRows.length()) {
                val row = nodeRows.getJSONObject(index)
                val node = GraphNode(
                    id = row.getString("id"),
                    documentId = row.getString("documentId"),
                    label = row.optString("label"),
                    dtype = row.optString("dtype").ifBlank { "document" },
                    position = GraphPoint(row.getDouble("x").toFloat(), row.getDouble("y").toFloat()),
                )
                require(nodes.put(node.id, node) == null) { "Duplicate graph node ${node.id}" }
            }
            val edges = linkedMapOf<String, GraphEdge>()
            for (index in 0 until edgeRows.length()) {
                val row = edgeRows.getJSONObject(index)
                val edge = GraphEdge(
                    id = row.getString("id"),
                    source = row.getString("source"),
                    target = row.getString("target"),
                    predicate = row.getString("predicate"),
                )
                require(edges.put(edge.id, edge) == null) { "Duplicate graph edge ${edge.id}" }
            }
            return GraphDocument(
                id = raw.getString("id"),
                name = raw.getString("name"),
                revision = raw.getLong("revision"),
                nodes = nodes,
                edges = edges,
            )
        }
    }
}

sealed interface GraphCommand {
    fun applyTo(graph: GraphDocument): GraphDocument

    data class PutNode(val node: GraphNode) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument =
            graph.copy(nodes = graph.nodes + (node.id to node))
    }

    data class MoveNode(val id: String, val position: GraphPoint) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument {
            val current = graph.nodes[id] ?: error("Unknown graph node: $id")
            return graph.copy(nodes = graph.nodes + (id to current.copy(position = position)))
        }
    }

    data class RenameNode(val id: String, val label: String) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument {
            val current = graph.nodes[id] ?: error("Unknown graph node: $id")
            return graph.copy(nodes = graph.nodes + (id to current.copy(label = label.take(240))))
        }
    }

    data class DeleteNode(val id: String) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument {
            require(id in graph.nodes) { "Unknown graph node: $id" }
            return graph.copy(
                nodes = graph.nodes - id,
                edges = graph.edges.filterValues { it.source != id && it.target != id },
            )
        }
    }

    data class PutEdge(val edge: GraphEdge) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument {
            require(edge.source in graph.nodes && edge.target in graph.nodes) { "Graph edge endpoint is missing" }
            return graph.copy(edges = graph.edges + (edge.id to edge))
        }
    }

    data class DeleteEdge(val id: String) : GraphCommand {
        override fun applyTo(graph: GraphDocument): GraphDocument {
            require(id in graph.edges) { "Unknown graph edge: $id" }
            return graph.copy(edges = graph.edges - id)
        }
    }
}

class GraphHistory(
    initial: GraphDocument,
    private val capacity: Int = 100,
) {
    init { require(capacity in 1..500) }

    private val undo = ArrayDeque<GraphDocument>()
    private val redo = ArrayDeque<GraphDocument>()
    var current: GraphDocument = initial
        private set

    fun execute(command: GraphCommand): GraphDocument {
        if (undo.size == capacity) undo.removeFirst()
        undo.addLast(current)
        redo.clear()
        current = current.apply(command)
        return current
    }

    fun undo(): GraphDocument {
        val previous = undo.removeLastOrNull() ?: return current
        redo.addLast(current)
        current = previous
        return current
    }

    fun redo(): GraphDocument {
        val next = redo.removeLastOrNull() ?: return current
        undo.addLast(current)
        current = next
        return current
    }

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
}
