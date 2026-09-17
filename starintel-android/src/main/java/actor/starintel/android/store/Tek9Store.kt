package actor.starintel.android.store

import actor.starintel.android.lisp.LispRuntime
import org.json.JSONArray
import org.json.JSONObject

interface Tek9Store : AutoCloseable {
    val status: Tek9Status

    fun document(id: String): JSONObject?

    fun search(query: String, limit: Int = 40): List<JSONObject>

    fun transact(block: Tek9Transaction.() -> Unit)
}

data class Tek9Status(
    val available: Boolean,
    val path: String,
    val detail: String,
)

interface Tek9Transaction {
    fun putDocument(document: JSONObject)

    fun putRelation(fromId: String, predicate: String, toId: String, attributes: JSONObject)

    fun assertFact(fact: JSONObject)

    fun enqueueTarget(request: JSONObject)

    fun appendEvent(event: JSONObject)
}

class LispTek9Store(
    private val runtime: LispRuntime,
    private val path: String,
) : Tek9Store {
    private var opened = false

    override val status: Tek9Status
        get() {
            if (!runtime.status.available) return Tek9Status(false, path, runtime.status.detail)
            return runCatching {
                ensureOpen()
                Tek9Status(true, path, "Tek9 document + graph store ready")
            }.getOrElse { Tek9Status(false, path, it.message ?: "Tek9 failed to open") }
        }

    override fun document(id: String): JSONObject? {
        require(id.isNotBlank() && id.length <= 512) { "Invalid document id" }
        ensureOpen()
        val result = runtime.request("tek9.document", JSONObject().put("id", id))
        return if (result.isNull("document")) null else result.optJSONObject("document")
    }

    override fun search(query: String, limit: Int): List<JSONObject> {
        require(query.isNotBlank() && query.length <= 512) { "Invalid search query" }
        ensureOpen()
        val rows = runtime.request(
            "tek9.search",
            JSONObject().put("query", query.trim()).put("limit", limit.coerceIn(1, 100)),
        ).optJSONArray("rows") ?: JSONArray()
        return buildList {
            for (index in 0 until rows.length().coerceAtMost(100)) {
                rows.optJSONObject(index)?.let(::add)
            }
        }
    }

    override fun transact(block: Tek9Transaction.() -> Unit) {
        ensureOpen()
        val transaction = StagedTransaction()
        transaction.block()
        if (transaction.operations.length() == 0) return
        runtime.request("tek9.transaction", JSONObject().put("operations", transaction.operations))
    }

    override fun close() {
        if (opened) runCatching { runtime.request("tek9.close") }
        opened = false
        runtime.close()
    }

    @Synchronized
    private fun ensureOpen() {
        if (opened) return
        check(runtime.status.available) { runtime.status.detail }
        runtime.request("tek9.open", JSONObject().put("path", path))
        opened = true
    }

    private class StagedTransaction : Tek9Transaction {
        val operations = JSONArray()

        override fun putDocument(document: JSONObject) {
            require(document.toString().length <= MAX_ITEM_CHARS) { "Document exceeds 512 KiB" }
            operations.put(JSONObject().put("type", "put_document").put("document", document))
        }

        override fun putRelation(fromId: String, predicate: String, toId: String, attributes: JSONObject) {
            requireIdentifier(fromId, "from id")
            requireIdentifier(predicate, "predicate")
            requireIdentifier(toId, "to id")
            operations.put(
                JSONObject()
                    .put("type", "put_relation")
                    .put("from", fromId)
                    .put("predicate", predicate)
                    .put("to", toId)
                    .put("attributes", attributes),
            )
        }

        override fun assertFact(fact: JSONObject) {
            operations.put(JSONObject().put("type", "assert_fact").put("fact", fact))
        }

        override fun enqueueTarget(request: JSONObject) {
            operations.put(JSONObject().put("type", "enqueue_target").put("request", request))
        }

        override fun appendEvent(event: JSONObject) {
            operations.put(JSONObject().put("type", "append_event").put("event", event))
        }

        private fun requireIdentifier(value: String, label: String) {
            require(value.isNotBlank() && value.length <= 512) { "Invalid $label" }
        }

        companion object {
            private const val MAX_ITEM_CHARS = 512 * 1024
        }
    }
}
