package actor.starintel.android.fbp

import java.io.Closeable
import org.json.JSONObject

const val WEBSOCKET_DOCUMENT_COMPONENT = "starintel.websocket-document/v1"

/** A blocking, ordered document stream. Implementations own transport retries and acknowledgements. */
interface DocumentStream : Closeable {
    fun emit(correlationId: String, document: JSONObject): JSONObject
}

/** Registers the sole external workflow boundary: canonical documents emitted to Star ingest. */
fun registerWebSocketDocumentComponent(
    registry: FbpComponentRegistry,
    streamFactory: () -> DocumentStream,
) {
    registry.register(
        WEBSOCKET_DOCUMENT_COMPONENT,
        ComponentDefinition(
            inputs = setOf(PortDefinition("IN", required = true)),
            configValidator = ::validateDocumentStreamConfig,
        ),
    ) { config -> WebSocketDocumentComponent(streamFactory, config) }
}

private class WebSocketDocumentComponent(
    private val streamFactory: () -> DocumentStream,
    private val config: JSONObject,
) : FlowComponent {
    override fun run(context: ProcessContext) {
        val maximum = config.optInt("max_documents", 100)
        var emitted = 0
        streamFactory().use { stream ->
            while (true) {
                val packet = context.receive("IN") ?: return
                check(emitted < maximum) { "WebSocket document emission limit exceeded" }
                val correlationId = "${context.graphId}:${context.processId}:${packet.id}"
                val acknowledgement = stream.emit(correlationId, packet.content)
                require(acknowledgement.optString("correlation_id") == correlationId) {
                    "Star ingest acknowledgement correlation mismatch"
                }
                check(acknowledgement.optString("outcome") == "accepted") {
                    acknowledgement.optString("error").ifBlank { "Star ingest rejected document" }.take(400)
                }
                emitted += 1
            }
        }
    }
}

private fun validateDocumentStreamConfig(config: JSONObject) {
    val fields = config.keys().asSequence().toSet()
    require(fields.all { it == "max_documents" }) { "document stream config contains unknown field" }
    require(config.optInt("max_documents", 100) in 1..1_000) {
        "max_documents must be between 1 and 1000"
    }
}
