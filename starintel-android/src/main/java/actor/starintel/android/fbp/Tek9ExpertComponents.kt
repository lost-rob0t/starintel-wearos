package actor.starintel.android.fbp

import actor.starintel.android.store.Tek9Store
import org.json.JSONArray
import org.json.JSONObject

const val TEK9_EXPERT_QUERY_COMPONENT = "starintel.tek9-expert-query/v1"
const val TEK9_ALERT_OUTBOX_COMPONENT = "starintel.tek9-alert-outbox/v1"

/** Registers bounded expert/query nodes against the existing Tek9 and outbox contracts. */
fun registerTek9ExpertComponents(registry: FbpComponentRegistry, store: Tek9Store) {
    registry.register(
        TEK9_EXPERT_QUERY_COMPONENT,
        ComponentDefinition(
            inputs = setOf(PortDefinition("TRIGGER", required = true)),
            outputs = setOf(PortDefinition("RESULT"), PortDefinition("ALERT")),
            configValidator = ::validateExpertConfig,
        ),
    ) { config -> Tek9ExpertQueryComponent(store, config) }
    registry.register(
        TEK9_ALERT_OUTBOX_COMPONENT,
        ComponentDefinition(
            inputs = setOf(PortDefinition("IN", required = true)),
            configValidator = ::validateOutboxConfig,
        ),
    ) { config -> Tek9AlertOutboxComponent(store, config) }
}

private class Tek9ExpertQueryComponent(
    private val store: Tek9Store,
    private val config: JSONObject,
) : FlowComponent {
    override fun run(context: ProcessContext) {
        val query = config.getString("query").trim()
        val maxResults = config.optInt("max_results", 40)
        val maxAlerts = config.optInt("max_alerts", 10)
        val predicate = config.optJSONObject("where") ?: JSONObject()
        val alertTemplate = config.optJSONObject("alert") ?: JSONObject()
        var alerts = 0
        while (context.receive("TRIGGER") != null) {
            store.search(query, maxResults).take(maxResults).forEach { document ->
                context.send("RESULT", document)
                if (alerts < maxAlerts && document.matches(predicate)) {
                    context.send(
                        "ALERT",
                        JSONObject()
                            .put("type", "alert")
                            .put("document", document.deepSortedCopy())
                            .put("alert", alertTemplate.deepSortedCopy()),
                    )
                    alerts += 1
                }
            }
        }
    }
}

private class Tek9AlertOutboxComponent(
    private val store: Tek9Store,
    private val config: JSONObject,
) : FlowComponent {
    override fun run(context: ProcessContext) {
        val maxEffects = config.optInt("max_effects", 10)
        var effects = 0
        while (true) {
            val packet = context.receive("IN") ?: return
            check(effects < maxEffects) { "Expert outbox effect limit exceeded" }
            val content = packet.content
            require(content.optString("type") == "alert") { "Expert outbox accepts alert packets only" }
            val request = content.deepSortedCopy().put(
                "provenance",
                JSONObject()
                    .put("authority", "starintel.fbp")
                    .put("graph_id", context.graphId)
                    .put("process_id", context.processId)
                    .put("packet_id", packet.id),
            )
            store.transact {
                // Reuse the canonical Tek9 outbox operation; this does not create an Android protocol fork.
                enqueueTarget(request)
                appendEvent(
                    JSONObject()
                        .put("type", "fbp_alert_enqueued")
                        .put("graph_id", context.graphId)
                        .put("process_id", context.processId)
                        .put("packet_id", packet.id),
                )
            }
            effects += 1
        }
    }
}

private fun validateExpertConfig(config: JSONObject) {
    config.requireOnly(EXPERT_CONFIG_FIELDS, "expert config")
    val query = config.optString("query").trim()
    require(query.isNotEmpty() && query.length <= 512) { "query must contain 1 to 512 characters" }
    require(config.optInt("max_results", 40) in 1..100) { "max_results must be between 1 and 100" }
    require(config.optInt("max_alerts", 10) in 0..100) { "max_alerts must be between 0 and 100" }
    require(config.optJSONObject("where") != null) { "where must be an object" }
    require(config.optJSONObject("alert") != null) { "alert must be an object" }
}

private fun validateOutboxConfig(config: JSONObject) {
    config.requireOnly(OUTBOX_CONFIG_FIELDS, "outbox config")
    require(config.optInt("max_effects", 10) in 1..100) { "max_effects must be between 1 and 100" }
}

private fun JSONObject.matches(predicate: JSONObject): Boolean = predicate.keys().asSequence().all { key ->
    has(key) && jsonValuesEqual(get(key), predicate.get(key))
}

private fun jsonValuesEqual(left: Any?, right: Any?): Boolean = when {
    left is JSONObject && right is JSONObject -> left.deepSortedCopy().toString() == right.deepSortedCopy().toString()
    left is JSONArray && right is JSONArray -> left.toString() == right.toString()
    else -> left == right || left?.toString() == right?.toString()
}

private fun JSONObject.requireOnly(fields: Set<String>, label: String) {
    val unknown = keys().asSequence().filterNot(fields::contains).toList().sorted()
    require(unknown.isEmpty()) { "$label contains unknown field: ${unknown.first()}" }
}

private val EXPERT_CONFIG_FIELDS = setOf("query", "max_results", "max_alerts", "where", "alert")
private val OUTBOX_CONFIG_FIELDS = setOf("max_effects")
