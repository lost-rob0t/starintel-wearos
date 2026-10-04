package actor.starintel.android.fbp

import org.json.JSONArray
import org.json.JSONObject

data class PortAddress(
    val process: String,
    val port: String,
) {
    init {
        require(process.matches(IDENTIFIER)) { "Invalid process id" }
        require(port.matches(PORT_NAME)) { "Invalid port name" }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("process", process)
        .put("port", port)
}

data class ProcessDefinition(
    val id: String,
    val component: String,
    val config: JSONObject = JSONObject(),
) {
    init {
        require(id.matches(IDENTIFIER)) { "Invalid process id" }
        require(component.matches(COMPONENT_TYPE)) { "Invalid component type" }
        require(config.toString().length <= MAX_CONFIG_CHARS) { "Process config exceeds 64 KiB" }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("component", component)
        .put("config", config.deepSortedCopy())
}

data class ConnectionDefinition(
    val source: PortAddress,
    val target: PortAddress,
    val capacity: Int = DEFAULT_CONNECTION_CAPACITY,
) {
    internal val label: String get() = "${source.process}.${source.port}->${target.process}.${target.port}"

    internal fun toJson(): JSONObject = JSONObject()
        .put("source", source.toJson())
        .put("target", target.toJson())
        .put("capacity", capacity)
}

data class InitialPacketDefinition(
    val target: PortAddress,
    val value: JSONObject,
) {
    init {
        require(value.toString().length <= MAX_PACKET_CHARS) { "Initial information packet exceeds 512 KiB" }
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("target", target.toJson())
        .put("value", value.deepSortedCopy())
}

/**
 * Persistence form for a Morrison-style FBP network.
 *
 * The graph is deliberately closed: executable code, protocol routes, and ambient authorities cannot
 * be smuggled into the topology. Components are symbolic types resolved by an explicit registry.
 */
class FlowGraph(
    val id: String,
    processes: List<ProcessDefinition>,
    connections: List<ConnectionDefinition> = emptyList(),
    initialPackets: List<InitialPacketDefinition> = emptyList(),
) {
    val processes: List<ProcessDefinition> = processes.sortedBy(ProcessDefinition::id)
    val connections: List<ConnectionDefinition> = connections.sortedBy(ConnectionDefinition::label)
    val initialPackets: List<InitialPacketDefinition> = initialPackets.sortedWith(
        compareBy({ it.target.process }, { it.target.port }),
    )

    init {
        require(id.matches(IDENTIFIER)) { "Invalid graph id" }
        require(processes.size <= MAX_PROCESSES) { "Graph exceeds $MAX_PROCESSES processes" }
        require(connections.size <= MAX_CONNECTIONS) { "Graph exceeds $MAX_CONNECTIONS connections" }
        require(initialPackets.size <= MAX_IIPS) { "Graph exceeds $MAX_IIPS initial information packets" }
    }

    fun validate(registry: FbpComponentRegistry): List<String> {
        val errors = mutableListOf<String>()
        val processCounts = processes.groupingBy(ProcessDefinition::id).eachCount()
        val processById = processes.associateBy(ProcessDefinition::id)

        processCounts.filterValues { it > 1 }.keys.forEach { errors += "process[$it]: duplicate process id" }
        processes.forEach { process ->
            val registered = registry.definition(process.component)
            if (registered == null) {
                errors += "process[${process.id}]: unknown component ${process.component}"
            } else {
                runCatching { registered.validateConfig(process.config) }
                    .onFailure { errors += "process[${process.id}]: ${it.message ?: "invalid config"}" }
            }
        }

        val connectedInputs = mutableSetOf<PortAddress>()
        val connectedOutputs = mutableSetOf<PortAddress>()
        connections.forEach { connection ->
            val prefix = "connection[${connection.label}]"
            if (connection.capacity !in MIN_CONNECTION_CAPACITY..MAX_CONNECTION_CAPACITY) {
                errors += "$prefix: capacity must be between $MIN_CONNECTION_CAPACITY and $MAX_CONNECTION_CAPACITY"
            }
            val sourceProcess = processById[connection.source.process]
            val targetProcess = processById[connection.target.process]
            val sourceDefinition = sourceProcess?.let { registry.definition(it.component) }
            val targetDefinition = targetProcess?.let { registry.definition(it.component) }
            if (sourceProcess == null) {
                errors += "$prefix: source process does not exist"
            } else if (sourceDefinition != null && !sourceDefinition.hasOutput(connection.source.port)) {
                errors += "$prefix: source port is not declared"
            }
            if (targetProcess == null) {
                errors += "$prefix: target process does not exist"
            } else if (targetDefinition != null && !targetDefinition.hasInput(connection.target.port)) {
                errors += "$prefix: target port is not declared"
            }
            if (!connectedOutputs.add(connection.source)) errors += "$prefix: source port is already connected"
            if (!connectedInputs.add(connection.target)) errors += "$prefix: target port is already connected"
        }

        initialPackets.forEach { iip ->
            val prefix = "iip[${iip.target.process}.${iip.target.port}]"
            val targetProcess = processById[iip.target.process]
            val targetDefinition = targetProcess?.let { registry.definition(it.component) }
            if (targetProcess == null) {
                errors += "$prefix: target process does not exist"
            } else if (targetDefinition != null && !targetDefinition.hasInput(iip.target.port)) {
                errors += "$prefix: target port is not declared"
            }
            if (!connectedInputs.add(iip.target)) errors += "$prefix: target port is already connected"
        }

        processes.forEach { process ->
            registry.definition(process.component)?.inputs?.filter(PortDefinition::required)?.forEach { port ->
                if (PortAddress(process.id, port.name) !in connectedInputs) {
                    errors += "process[${process.id}]: required input ${port.name} is not connected"
                }
            }
        }
        return errors.sorted()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", id)
        .put("processes", JSONArray(processes.map(ProcessDefinition::toJson)))
        .put("connections", JSONArray(connections.map(ConnectionDefinition::toJson)))
        .put("iips", JSONArray(initialPackets.map(InitialPacketDefinition::toJson)))

    override fun equals(other: Any?): Boolean = other is FlowGraph && toJson().toString() == other.toJson().toString()

    override fun hashCode(): Int = toJson().toString().hashCode()

    override fun toString(): String = toJson().toString()

    companion object {
        const val SCHEMA = "starintel.fbp.graph/v1"

        fun fromJson(root: JSONObject): FlowGraph {
            root.requireOnlyFields(GRAPH_FIELDS, "graph")
            require(root.optString("schema") == SCHEMA) { "Unsupported FBP graph schema" }
            val rawProcesses = root.requiredArray("processes")
            val rawConnections = root.requiredArray("connections")
            val rawIips = root.requiredArray("iips")
            return FlowGraph(
                id = root.requiredString("id"),
                processes = rawProcesses.objects("process").map { value ->
                    value.requireOnlyFields(PROCESS_FIELDS, "process")
                    ProcessDefinition(
                        id = value.requiredString("id"),
                        component = value.requiredString("component"),
                        config = value.optJSONObject("config")?.deepSortedCopy() ?: JSONObject(),
                    )
                },
                connections = rawConnections.objects("connection").map { value ->
                    value.requireOnlyFields(CONNECTION_FIELDS, "connection")
                    ConnectionDefinition(
                        source = value.requiredAddress("source"),
                        target = value.requiredAddress("target"),
                        capacity = value.optInt("capacity", DEFAULT_CONNECTION_CAPACITY),
                    )
                },
                initialPackets = rawIips.objects("iip").map { value ->
                    value.requireOnlyFields(IIP_FIELDS, "iip")
                    InitialPacketDefinition(
                        target = value.requiredAddress("target"),
                        value = value.optJSONObject("value")?.deepSortedCopy()
                            ?: throw IllegalArgumentException("iip value must be an object"),
                    )
                },
            )
        }
    }
}

internal val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
internal val PORT_NAME = Regex("[A-Za-z][A-Za-z0-9_-]{0,63}")
internal val COMPONENT_TYPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}/v[1-9][0-9]*")
internal const val DEFAULT_CONNECTION_CAPACITY = 16
internal const val MIN_CONNECTION_CAPACITY = 1
internal const val MAX_CONNECTION_CAPACITY = 1_024
internal const val MAX_PACKET_CHARS = 512 * 1_024
private const val MAX_CONFIG_CHARS = 64 * 1_024
private const val MAX_PROCESSES = 64
private const val MAX_CONNECTIONS = 256
private const val MAX_IIPS = 256

private val GRAPH_FIELDS = setOf("schema", "id", "processes", "connections", "iips")
private val PROCESS_FIELDS = setOf("id", "component", "config")
private val CONNECTION_FIELDS = setOf("source", "target", "capacity")
private val IIP_FIELDS = setOf("target", "value")
private val ADDRESS_FIELDS = setOf("process", "port")

private fun JSONObject.requiredAddress(key: String): PortAddress {
    val value = optJSONObject(key) ?: throw IllegalArgumentException("$key must be an object")
    value.requireOnlyFields(ADDRESS_FIELDS, key)
    return PortAddress(value.requiredString("process"), value.requiredString("port"))
}

private fun JSONObject.requiredArray(key: String): JSONArray = optJSONArray(key)
    ?: throw IllegalArgumentException("$key must be an array")

private fun JSONObject.requiredString(key: String): String {
    val value = optString(key).trim()
    require(value.isNotEmpty()) { "$key must be a non-empty string" }
    return value
}

private fun JSONArray.objects(label: String): List<JSONObject> = buildList {
    for (index in 0 until length()) {
        add(optJSONObject(index) ?: throw IllegalArgumentException("$label[$index] must be an object"))
    }
}

private fun JSONObject.requireOnlyFields(allowed: Set<String>, label: String) {
    val unknown = keys().asSequence().filterNot(allowed::contains).toList().sorted()
    require(unknown.isEmpty()) {
        if (label == "graph") "Unknown graph field: ${unknown.first()}" else "Unknown $label field: ${unknown.first()}"
    }
}

internal fun JSONObject.deepSortedCopy(): JSONObject {
    val result = JSONObject()
    keys().asSequence().toList().sorted().forEach { key -> result.put(key, get(key).deepSortedCopy()) }
    return result
}

private fun Any?.deepSortedCopy(): Any? = when (this) {
    is JSONObject -> deepSortedCopy()
    is JSONArray -> JSONArray((0 until length()).map { get(it).deepSortedCopy() })
    JSONObject.NULL -> JSONObject.NULL
    else -> this
}
