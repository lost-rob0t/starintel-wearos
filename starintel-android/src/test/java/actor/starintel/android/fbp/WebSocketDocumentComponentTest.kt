package actor.starintel.android.fbp

import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketDocumentComponentTest {
    @Test
    fun emitsDocumentsInPacketOrderAndRequiresCorrelatedAcceptance() {
        val stream = RecordingStream()
        val registry = registry(stream)
        val run = FlowRuntime(graph(3), registry).start().completion.get(2, TimeUnit.SECONDS)

        assertEquals(FlowRunStatus.COMPLETED, run.status)
        assertEquals(listOf("doc:0", "doc:1", "doc:2"), stream.documents.map { it.getString("_id") })
        assertEquals(
            listOf("document-stream:sink:2", "document-stream:sink:3", "document-stream:sink:4"),
            stream.correlations,
        )
        assertTrue(stream.closed)
    }

    @Test
    fun rejectedAcknowledgementFailsTheFlowAndClosesTheStream() {
        val stream = RecordingStream(rejectAt = 2)
        val run = FlowRuntime(graph(3), registry(stream)).start().completion.get(2, TimeUnit.SECONDS)

        assertEquals(FlowRunStatus.FAILED, run.status)
        assertTrue(run.failure.orEmpty().contains("policy denied"))
        assertEquals(2, stream.documents.size)
        assertTrue(stream.closed)
    }

    private fun registry(stream: RecordingStream) = FbpComponentRegistry().apply {
        register(
            "test.documents/v1",
            ComponentDefinition(
                inputs = setOf(PortDefinition("COUNT", required = true)),
                outputs = setOf(PortDefinition("OUT")),
            ),
        ) {
            FlowComponent { context ->
                val count = context.receive("COUNT")!!.content.getInt("value")
                repeat(count) { context.send("OUT", JSONObject().put("_id", "doc:$it")) }
            }
        }
        registerWebSocketDocumentComponent(this) { stream }
    }

    private fun graph(count: Int) = FlowGraph(
        id = "document-stream",
        processes = listOf(
            ProcessDefinition("source", "test.documents/v1"),
            ProcessDefinition(
                "sink",
                WEBSOCKET_DOCUMENT_COMPONENT,
                JSONObject().put("max_documents", 3),
            ),
        ),
        connections = listOf(
            ConnectionDefinition(PortAddress("source", "OUT"), PortAddress("sink", "IN"), 1),
        ),
        initialPackets = listOf(
            InitialPacketDefinition(PortAddress("source", "COUNT"), JSONObject().put("value", count)),
        ),
    )

    private class RecordingStream(private val rejectAt: Int? = null) : DocumentStream {
        val correlations = mutableListOf<String>()
        val documents = mutableListOf<JSONObject>()
        var closed = false

        override fun emit(correlationId: String, document: JSONObject): JSONObject {
            correlations += correlationId
            documents += JSONObject(document.toString())
            return JSONObject()
                .put("correlation_id", correlationId)
                .put("outcome", if (documents.size == rejectAt) "rejected" else "accepted")
                .put("error", if (documents.size == rejectAt) "policy denied" else "")
        }

        override fun close() {
            closed = true
        }
    }
}
