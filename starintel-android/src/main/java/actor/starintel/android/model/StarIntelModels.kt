package actor.starintel.android.model

import org.json.JSONArray
import org.json.JSONObject

data class StarSession(
    val serverUrl: String,
    val apiKey: String,
)

data class Endpoint(
    val id: String,
    val method: String,
    val path: String,
    val legacy: Boolean,
)

data class LoginResult(
    val apiKey: String,
    val username: String,
    val mustChangePassword: Boolean,
)

enum class ActorTier(val wireName: String, val order: Int) {
    SENSOR("sensor", 0),
    EDGE("edge", 1),
    ENRICHMENT("enrichment", 2),
    COORDINATOR("coordinator", 3),
    UPLINK("uplink", 4),
    ;

    companion object {
        fun fromWire(value: String): ActorTier = entries.firstOrNull { it.wireName == value.trim().lowercase() }
            ?: throw IllegalArgumentException("Unknown actor tier: $value")
    }
}

data class ActorManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val entrypoint: String,
    val accepts: Set<String>,
    val capabilities: Set<ActorCapability>,
    val defaultConfig: JSONObject,
    val tier: ActorTier = ActorTier.EDGE,
) {
    companion object {
        fun fromJson(root: JSONObject): ActorManifest {
            val id = root.requiredBoundedString("id", 160)
            val entrypoint = root.requiredBoundedString("entrypoint", 240)
            require(entrypoint.matches(Regex("[A-Z0-9.-]+:[A-Z0-9.-]+", RegexOption.IGNORE_CASE))) {
                "Actor entrypoint must be a package-qualified Lisp symbol"
            }
            return ActorManifest(
                id = id,
                name = root.optString("name").ifBlank { id },
                version = root.optString("version").ifBlank { "1" },
                description = root.optString("description").take(800),
                entrypoint = entrypoint,
                accepts = root.stringSet("accepts", 64),
                capabilities = root.stringSet("capabilities", 64).mapTo(linkedSetOf()) {
                    ActorCapability.fromWire(it)
                },
                defaultConfig = root.optJSONObject("default_config") ?: JSONObject(),
                tier = ActorTier.fromWire(root.optString("tier").ifBlank { "edge" }),
            )
        }
    }
}

enum class ActorCapability(val wireName: String) {
    READ_DOCUMENT("document.read"),
    WRITE_DOCUMENT("document.write"),
    WRITE_RELATION("relation.write"),
    ASSERT_FACT("fact.assert"),
    DISPATCH_TARGET("target.dispatch"),
    DISPATCH_ACTOR("actor.dispatch"),
    VIDEO_STREAM("video.stream"),
    MAP_PROJECT("map.project"),
    NETWORK("network"),
    ;

    companion object {
        fun fromWire(value: String): ActorCapability = entries.firstOrNull { it.wireName == value }
            ?: throw IllegalArgumentException("Unknown actor capability: $value")
    }
}

data class ActorSupervisorPolicy(
    val maxConsecutiveFailures: Int = 3,
    val cooldownMs: Long = 5_000,
) {
    init {
        require(maxConsecutiveFailures in 1..32)
        require(cooldownMs in 0..300_000)
    }
}

data class ActorInstanceConfig(
    val instanceId: String,
    val actorId: String,
    val enabled: Boolean,
    val config: JSONObject,
    val supervisor: ActorSupervisorPolicy = ActorSupervisorPolicy(),
)

data class ActorEnvelope(
    val messageId: String,
    val document: JSONObject,
    val datasetId: String?,
    val depth: Int,
    val parentMessageId: String?,
)

sealed interface ActorEffect {
    val capability: ActorCapability

    data class SaveDocument(val document: JSONObject) : ActorEffect {
        override val capability = ActorCapability.WRITE_DOCUMENT
    }

    data class SaveRelation(
        val fromId: String,
        val predicate: String,
        val toId: String,
        val attributes: JSONObject = JSONObject(),
    ) : ActorEffect {
        override val capability = ActorCapability.WRITE_RELATION
    }

    data class AssertFact(val fact: JSONObject) : ActorEffect {
        override val capability = ActorCapability.ASSERT_FACT
    }

    data class DispatchTarget(val request: JSONObject) : ActorEffect {
        override val capability = ActorCapability.DISPATCH_TARGET
    }

    data class DispatchActor(
        val instanceId: String,
        val document: JSONObject,
        val datasetId: String? = null,
    ) : ActorEffect {
        override val capability = ActorCapability.DISPATCH_ACTOR
    }
}

data class ActorResult(
    val effects: List<ActorEffect>,
    val summary: String,
)

data class ActorRun(
    val actorId: String,
    val messageId: String,
    val acceptedEffects: Int,
    val summary: String,
)

data class AgentBudget(
    val wallTimeMs: Long = 30_000,
    val maxToolCalls: Int = 12,
    val maxOutputBytes: Int = 128 * 1024,
    val maxDepth: Int = 1,
) {
    init {
        require(wallTimeMs in 1_000..120_000)
        require(maxToolCalls in 0..64)
        require(maxOutputBytes in 1_024..1_048_576)
        require(maxDepth in 0..1) { "Depth above one requires the Prolog-RLM experimental gate" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("wall_time_ms", wallTimeMs)
        .put("max_tool_calls", maxToolCalls)
        .put("max_output_bytes", maxOutputBytes)
        .put("max_depth", maxDepth)
}

data class AgentTurnRequest(
    val prompt: String,
    val contextDocumentIds: List<String> = emptyList(),
    val capabilities: Set<String> = DEFAULT_CAPABILITIES,
    val budget: AgentBudget = AgentBudget(),
) {
    init {
        require(prompt.isNotBlank() && prompt.length <= 16_384)
        require(contextDocumentIds.size <= 128)
        require(capabilities.size <= 64)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("runtime", "prolog-rlm")
        .put("prompt", prompt.trim())
        .put("context_document_ids", JSONArray(contextDocumentIds))
        .put("capabilities", JSONArray(capabilities.toList().sorted()))
        .put("budget", budget.toJson())

    companion object {
        val DEFAULT_CAPABILITIES = setOf("document.search", "document.read", "actor.list")
    }
}

data class AgentOperation(
    val name: String,
    val arguments: JSONObject,
    val authority: String,
)

data class AgentTurnResult(
    val status: String,
    val answer: String,
    val traceId: String,
    val operations: List<AgentOperation>,
) {
    companion object {
        fun fromJson(root: JSONObject): AgentTurnResult {
            val data = root.optJSONObject("data") ?: root
            val operations = data.optJSONArray("operations") ?: JSONArray()
            return AgentTurnResult(
                status = data.optString("status").ifBlank { "unknown" },
                answer = data.optString("answer").take(262_144),
                traceId = data.optString("trace_id").take(240),
                operations = buildList {
                    for (index in 0 until operations.length().coerceAtMost(64)) {
                        val item = operations.optJSONObject(index) ?: continue
                        add(
                            AgentOperation(
                                name = item.requiredBoundedString("name", 160),
                                arguments = item.optJSONObject("arguments") ?: JSONObject(),
                                authority = item.optString("authority").ifBlank { "approve_diff" },
                            ),
                        )
                    }
                },
            )
        }
    }
}

internal fun JSONObject.requiredBoundedString(key: String, maxLength: Int): String {
    val value = optString(key).trim()
    require(value.isNotBlank() && value.length <= maxLength) { "Invalid $key" }
    return value
}

internal fun JSONObject.stringSet(key: String, maxItems: Int): Set<String> {
    val values = optJSONArray(key) ?: return emptySet()
    require(values.length() <= maxItems) { "$key contains too many values" }
    return buildSet {
        for (index in 0 until values.length()) {
            val value = values.optString(index).trim()
            require(value.isNotBlank() && value.length <= 160) { "Invalid $key value" }
            add(value)
        }
    }
}
