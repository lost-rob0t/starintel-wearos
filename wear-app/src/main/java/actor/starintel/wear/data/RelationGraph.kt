package actor.starintel.wear.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GraphNode(
    val id: String,
    val label: String,
    val dtype: String,
)

data class GraphEdge(
    val id: String,
    val source: String,
    val target: String,
    val predicate: String,
    val directed: Boolean = true,
)

data class RelationNeighborhood(
    val rootId: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val error: String? = null,
)

class RelationGraphLoader private constructor(context: Context) {
    private val api = StarIntelApiClient.get(context.applicationContext)
    private val search = StarIntelSearchClient.get(context.applicationContext)

    suspend fun load(rootInput: String): RelationNeighborhood {
        val requested = rootInput.trim()
        if (requested.isBlank()) return emptyGraph("Choose a document")

        val rootDocument = resolveRoot(requested)
            ?: return emptyGraph("Document not found")
        val rootId = rootDocument.optString("_id").ifBlank { requested }
        val rootNode = nodeFrom(rootDocument, rootId)

        val relationSearch = search.search(rootId, limit = MAX_RELATION_CANDIDATES)
        if (relationSearch.error != null) {
            return RelationNeighborhood(rootId, listOf(rootNode), emptyList(), relationSearch.error)
        }

        val relations = mutableListOf<ParsedRelation>()
        for (hit in relationSearch.hits) {
            if (relations.size >= MAX_RELATIONS) break
            if (!hit.secondary.split(" · ").any { it == "relation" }) continue
            val document = api.document(hit.id).json ?: continue
            if (document.optString("dtype") != "relation") continue
            val parsed = parseRelation(document) ?: continue
            if (rootId !in parsed.subjects && rootId !in parsed.objects) continue
            relations += parsed
        }

        val endpointIds = LinkedHashSet<String>()
        relations.forEach { relation ->
            relation.subjects.forEach { if (it != rootId && endpointIds.size < MAX_NEIGHBORS) endpointIds += it }
            relation.objects.forEach { if (it != rootId && endpointIds.size < MAX_NEIGHBORS) endpointIds += it }
        }

        val nodes = mutableListOf(rootNode)
        for (id in endpointIds) {
            val document = api.document(id).json
            nodes += document?.let { nodeFrom(it, id) } ?: GraphNode(id, compactId(id), "unresolved")
        }
        val visible = nodes.map { it.id }.toHashSet()
        val edges = buildList {
            relations.forEach { relation ->
                relation.subjects.forEach { source ->
                    relation.objects.forEach { target ->
                        if (source in visible && target in visible && size < MAX_EDGES) {
                            add(
                                GraphEdge(
                                    id = "${relation.id}:$source:$target",
                                    source = source,
                                    target = target,
                                    predicate = relation.predicate,
                                    directed = relation.directed,
                                ),
                            )
                        }
                    }
                }
            }
        }

        return RelationNeighborhood(rootId, nodes, edges)
    }

    private suspend fun resolveRoot(input: String): JSONObject? {
        api.document(input).json?.let { return it }
        val result = search.search(input, limit = 8)
        val hit = result.hits.firstOrNull() ?: return null
        return api.document(hit.id).json
    }

    companion object {
        private const val MAX_RELATION_CANDIDATES = 24
        private const val MAX_RELATIONS = 16
        private const val MAX_NEIGHBORS = 10
        private const val MAX_EDGES = 20

        @Volatile private var instance: RelationGraphLoader? = null
        fun get(context: Context): RelationGraphLoader =
            instance ?: synchronized(this) {
                instance ?: RelationGraphLoader(context).also { instance = it }
            }
    }
}

internal data class ParsedRelation(
    val id: String,
    val subjects: List<String>,
    val objects: List<String>,
    val predicate: String,
    val directed: Boolean,
)

internal fun parseRelation(document: JSONObject): ParsedRelation? {
    if (document.optString("dtype") != "relation") return null
    val data = document.optJSONObject("data") ?: document
    val subjects = endpointIds(data.opt("subject") ?: data.opt("source"))
    val objects = endpointIds(data.opt("object") ?: data.opt("target"))
    if (subjects.isEmpty() || objects.isEmpty()) return null
    return ParsedRelation(
        id = document.optString("_id").ifBlank { "relation" },
        subjects = subjects,
        objects = objects,
        predicate = data.optString("predicate")
            .ifBlank { data.optString("relation_type") }
            .ifBlank { document.optString("title") }
            .ifBlank { "related-to" }
            .take(48),
        directed = when (data.opt("directed")) {
            false, "false" -> false
            else -> true
        },
    )
}

private fun endpointIds(value: Any?): List<String> = when (value) {
    is JSONArray -> buildList {
        for (index in 0 until value.length()) endpointId(value.opt(index))?.let(::add)
    }
    else -> listOfNotNull(endpointId(value))
}.distinct().take(8)

private fun endpointId(value: Any?): String? = when (value) {
    is String -> value.trim().takeIf { it.isNotBlank() }
    is JSONObject -> listOf("id", "entity_id", "document_id", "external_id")
        .firstNotNullOfOrNull { key -> value.optString(key).trim().takeIf { it.isNotBlank() } }
    else -> null
}

private fun nodeFrom(document: JSONObject, fallbackId: String): GraphNode {
    val id = document.optString("_id").ifBlank { fallbackId }
    val data = document.optJSONObject("data")
    val label = firstValue(document, "name", "title", "username", "handle", "url")
        ?: data?.let { firstValue(it, "name", "title", "username", "handle", "url", "target") }
        ?: compactId(id)
    return GraphNode(id, label.take(48), document.optString("dtype").ifBlank { "document" })
}

private fun firstValue(objectValue: JSONObject, vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> objectValue.optString(key).trim().takeIf { it.isNotBlank() } }

private fun compactId(id: String): String = if (id.length <= 18) id else "${id.take(8)}…${id.takeLast(7)}"

private fun emptyGraph(message: String) = RelationNeighborhood("", emptyList(), emptyList(), message)
