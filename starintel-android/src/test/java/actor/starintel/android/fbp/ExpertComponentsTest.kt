package actor.starintel.android.fbp

import actor.starintel.android.store.Tek9Status
import actor.starintel.android.store.Tek9Store
import actor.starintel.android.store.Tek9Transaction
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpertComponentsTest {
    @Test
    fun expertQueriesTek9AndWritesBoundedAlertsThroughExistingOutboxContract() {
        val store = RecordingStore(
            listOf(
                JSONObject().put("_id", "geo:1").put("severity", "high"),
                JSONObject().put("_id", "geo:2").put("severity", "low"),
                JSONObject().put("_id", "geo:3").put("severity", "high"),
            ),
        )
        val registry = FbpComponentRegistry().also { registerTek9ExpertComponents(it, store) }
        val graph = FlowGraph(
            id = "expert-alerts",
            processes = listOf(
                ProcessDefinition(
                    "expert",
                    TEK9_EXPERT_QUERY_COMPONENT,
                    JSONObject()
                        .put("query", "dtype:geo")
                        .put("max_results", 10)
                        .put("max_alerts", 1)
                        .put("where", JSONObject().put("severity", "high"))
                        .put("alert", JSONObject().put("channel", "field-ops")),
                ),
                ProcessDefinition(
                    "outbox",
                    TEK9_ALERT_OUTBOX_COMPONENT,
                    JSONObject().put("max_effects", 1),
                ),
            ),
            connections = listOf(
                ConnectionDefinition(PortAddress("expert", "ALERT"), PortAddress("outbox", "IN"), 1),
            ),
            initialPackets = listOf(
                InitialPacketDefinition(PortAddress("expert", "TRIGGER"), JSONObject().put("reason", "schedule")),
            ),
        )

        val result = FlowRuntime(graph, registry).start().completion.get(2, TimeUnit.SECONDS)

        assertEquals(FlowRunStatus.COMPLETED, result.status)
        assertEquals(listOf("dtype:geo"), store.queries)
        assertEquals(1, store.outbox.size)
        assertEquals("alert", store.outbox.single().getString("type"))
        assertEquals("geo:1", store.outbox.single().getJSONObject("document").getString("_id"))
        assertEquals("expert-alerts", store.outbox.single().getJSONObject("provenance").getString("graph_id"))
    }

    private class RecordingStore(private val rows: List<JSONObject>) : Tek9Store {
        val queries = mutableListOf<String>()
        val outbox = mutableListOf<JSONObject>()
        override val status = Tek9Status(true, "/test", "ready")

        override fun document(id: String): JSONObject? = rows.firstOrNull { it.optString("_id") == id }

        override fun search(query: String, limit: Int): List<JSONObject> {
            queries += query
            return rows.take(limit)
        }

        override fun transact(block: Tek9Transaction.() -> Unit) {
            object : Tek9Transaction {
                override fun putDocument(document: JSONObject) = Unit
                override fun putRelation(fromId: String, predicate: String, toId: String, attributes: JSONObject) = Unit
                override fun assertFact(fact: JSONObject) = Unit
                override fun enqueueTarget(request: JSONObject) { outbox += JSONObject(request.toString()) }
                override fun appendEvent(event: JSONObject) = Unit
            }.block()
        }

        override fun close() = Unit
    }
}
