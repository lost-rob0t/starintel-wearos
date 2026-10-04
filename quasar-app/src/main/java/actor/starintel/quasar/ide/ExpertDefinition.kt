package actor.starintel.quasar.ide

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ExpertArgument(
    val name: String,
    val type: String,
    val required: Boolean,
)

data class ExpertQuery(
    val collection: String,
    val expression: String,
    val limit: Int = 50,
)

data class ExpertAlert(
    val enabled: Boolean,
    val condition: String,
    val severity: String,
    val message: String,
)

data class ExpertDefinition(
    val id: String,
    val name: String,
    val description: String,
    val schemas: List<String>,
    val facts: List<String>,
    val rules: List<String>,
    val arguments: List<ExpertArgument>,
    val activation: List<String>,
    val query: ExpertQuery,
    val alert: ExpertAlert,
)

object ExpertValidator {
    private val idPattern = Regex("[a-z][a-z0-9-]{1,47}")
    private val predicatePattern = Regex("[a-z][a-zA-Z0-9_]*(?:\\([^\\r\\n]*\\))?\\.?")
    private val argumentPattern = Regex("[a-z][a-z0-9_]{0,31}")

    fun validate(expert: ExpertDefinition): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        if (!idPattern.matches(expert.id)) diagnostics += Diagnostic.error("expert.id", "Expert id must be a lower-case kebab identifier")
        if (expert.name.isBlank() || expert.name.length > 80) diagnostics += Diagnostic.error("expert.name", "Expert name is required and limited to 80 characters")
        if (expert.description.length > 500) diagnostics += Diagnostic.error("expert.description", "Description is limited to 500 characters")
        if (expert.schemas.isEmpty()) diagnostics += Diagnostic.warning("expert.schemas.empty", "Expert has no predicate schemas")
        (expert.schemas + expert.facts).forEachIndexed { index, form ->
            if (form.length > 2_000 || !predicatePattern.matches(form.trim())) {
                diagnostics += Diagnostic.error("expert.form", "Schema or fact ${index + 1} is not a bounded predicate form")
            }
        }
        expert.rules.forEachIndexed { index, rule ->
            if (rule.length > 4_000 || ":-" !in rule || !rule.trim().endsWith(".")) {
                diagnostics += Diagnostic.error("expert.rule", "Rule ${index + 1} must be a bounded Prolog rule ending in a period")
            }
        }
        if (expert.arguments.map { it.name }.distinct().size != expert.arguments.size || expert.arguments.any { !argumentPattern.matches(it.name) }) {
            diagnostics += Diagnostic.error("expert.arguments", "Arguments require unique lower-case identifiers")
        }
        val normalizedActivation = expert.activation.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalizedActivation.size != normalizedActivation.distinct().size) {
            diagnostics += Diagnostic.error("expert.activation.duplicate", "Activation phrases must be unique")
        }
        if (normalizedActivation.any { it.length > 120 }) diagnostics += Diagnostic.error("expert.activation.length", "Activation phrase is too long")
        if ('\n' in expert.query.expression || '\r' in expert.query.expression) {
            diagnostics += Diagnostic.error("expert.query.multiline", "Document query must be a single line")
        }
        if (expert.query.collection.isBlank() || expert.query.limit !in 1..500) {
            diagnostics += Diagnostic.error("expert.query", "Document query requires a collection and a limit from 1 to 500")
        }
        val secretMaterial = listOf(
            expert.id,
            expert.name,
            expert.description,
            expert.query.collection,
            expert.query.expression,
            expert.alert.condition,
            expert.alert.message,
        ) + expert.schemas + expert.facts + expert.rules + expert.activation + expert.arguments.flatMap { listOf(it.name, it.type) }
        if (secretMaterial.any(SecretGuard::containsLikelySecret)) {
            diagnostics += Diagnostic.error("expert.secret", "Expert contains credential-like material")
        }
        if (expert.alert.enabled && (expert.alert.condition.isBlank() || expert.alert.message.isBlank())) {
            diagnostics += Diagnostic.error("expert.alert", "Enabled alert requires a condition and message")
        }
        if (expert.alert.severity !in setOf("info", "low", "medium", "high", "critical")) {
            diagnostics += Diagnostic.error("expert.alert.severity", "Alert severity is not supported")
        }
        return diagnostics
    }
}

data class FbpPortDescriptor(
    val name: String,
    val type: String,
    val required: Boolean = false,
)

data class FbpNodeDescriptor(
    val id: String,
    val component: String,
    val label: String,
    val inputPorts: List<FbpPortDescriptor>,
    val outputPorts: List<FbpPortDescriptor>,
    val configuration: Map<String, Any>,
)

object ExpertFbpAdapter {
    fun toNode(expert: ExpertDefinition): FbpNodeDescriptor {
        val diagnostics = ExpertValidator.validate(expert)
        require(diagnostics.none { it.severity == DiagnosticSeverity.ERROR }) { "Expert definition is invalid" }
        return FbpNodeDescriptor(
            id = expert.id,
            component = "starintel.expert",
            label = expert.name,
            inputPorts = listOf(
                FbpPortDescriptor("document", "starintel.document"),
                FbpPortDescriptor("trigger", "starintel.trigger"),
            ),
            outputPorts = listOf(
                FbpPortDescriptor("result", "starintel.expert-result"),
                FbpPortDescriptor("alert", "starintel.alert"),
                FbpPortDescriptor("error", "starintel.error"),
            ),
            configuration = linkedMapOf(
                "expert_id" to expert.id,
                "arguments" to expert.arguments.map { mapOf("name" to it.name, "type" to it.type, "required" to it.required) },
                "activation" to expert.activation,
                "query" to mapOf("collection" to expert.query.collection, "expression" to expert.query.expression, "limit" to expert.query.limit),
                "alert" to mapOf(
                    "enabled" to expert.alert.enabled,
                    "condition" to expert.alert.condition,
                    "severity" to expert.alert.severity,
                    "message" to expert.alert.message,
                ),
            ),
        )
    }

    fun toJson(node: FbpNodeDescriptor): String = JSONObject().apply {
        put("id", node.id)
        put("component", node.component)
        put("label", node.label)
        put("inports", JSONArray(node.inputPorts.map(::portJson)))
        put("outports", JSONArray(node.outputPorts.map(::portJson)))
        put("configuration", JSONObject(node.configuration))
    }.toString(2)

    private fun portJson(port: FbpPortDescriptor) = JSONObject()
        .put("name", port.name)
        .put("type", port.type)
        .put("required", port.required)
}

object ExpertJsonCodec {
    fun encode(expert: ExpertDefinition): String = JSONObject().apply {
        put("version", 1)
        put("id", expert.id)
        put("name", expert.name)
        put("description", expert.description)
        put("schemas", JSONArray(expert.schemas))
        put("facts", JSONArray(expert.facts))
        put("rules", JSONArray(expert.rules))
        put("arguments", JSONArray(expert.arguments.map { argument ->
            JSONObject().put("name", argument.name).put("type", argument.type).put("required", argument.required)
        }))
        put("activation", JSONArray(expert.activation))
        put("query", JSONObject()
            .put("collection", expert.query.collection)
            .put("expression", expert.query.expression)
            .put("limit", expert.query.limit))
        put("alert", JSONObject()
            .put("enabled", expert.alert.enabled)
            .put("condition", expert.alert.condition)
            .put("severity", expert.alert.severity)
            .put("message", expert.alert.message))
    }.toString(2)

    fun decode(encoded: String): ExpertDefinition {
        val root = JSONObject(encoded)
        require(root.optInt("version") == 1) { "Unsupported expert definition version" }
        val query = root.getJSONObject("query")
        val alert = root.getJSONObject("alert")
        return ExpertDefinition(
            id = root.getString("id"),
            name = root.getString("name"),
            description = root.optString("description"),
            schemas = root.getJSONArray("schemas").strings(),
            facts = root.getJSONArray("facts").strings(),
            rules = root.getJSONArray("rules").strings(),
            arguments = root.getJSONArray("arguments").objects().map { argument ->
                ExpertArgument(argument.getString("name"), argument.getString("type"), argument.optBoolean("required"))
            },
            activation = root.getJSONArray("activation").strings(),
            query = ExpertQuery(query.getString("collection"), query.getString("expression"), query.getInt("limit")),
            alert = ExpertAlert(
                alert.getBoolean("enabled"),
                alert.getString("condition"),
                alert.getString("severity"),
                alert.getString("message"),
            ),
        )
    }
}

class ExpertDefinitionStore(
    private val root: File,
    private val maxDefinitionBytes: Int = 128 * 1024,
    private val maxExperts: Int = 64,
) {
    init {
        root.mkdirs()
    }

    @Synchronized
    fun save(expert: ExpertDefinition) {
        val diagnostics = ExpertValidator.validate(expert)
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            throw WorkspaceException("Expert has ${diagnostics.count { it.severity == DiagnosticSeverity.ERROR }} blocking diagnostic(s)")
        }
        val target = resolve(expert.id)
        if (!target.exists() && listFiles().size >= maxExperts) throw WorkspaceException("Private expert limit reached")
        val bytes = ExpertJsonCodec.encode(expert).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxDefinitionBytes) throw WorkspaceException("Expert definition exceeds size limit")
        PrivateWorkspaceStore.atomicWrite(target, bytes)
    }

    fun load(id: String): ExpertDefinition {
        val file = resolve(id)
        if (!file.isFile || file.length() > maxDefinitionBytes) throw WorkspaceException("Expert definition is unavailable")
        return ExpertJsonCodec.decode(file.readText(Charsets.UTF_8))
    }

    fun list(): List<ExpertDefinition> = listFiles().sortedBy { it.name }.map { file ->
        if (file.length() > maxDefinitionBytes) throw WorkspaceException("Expert definition exceeds size limit")
        ExpertJsonCodec.decode(file.readText(Charsets.UTF_8))
    }

    private fun resolve(id: String): File {
        if (!Regex("[a-z][a-z0-9-]{1,47}").matches(id)) throw WorkspaceException("Invalid expert id")
        return File(root, "$id.json")
    }

    private fun listFiles(): List<File> = root.listFiles()?.filter { it.isFile && it.extension == "json" }.orEmpty()
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
