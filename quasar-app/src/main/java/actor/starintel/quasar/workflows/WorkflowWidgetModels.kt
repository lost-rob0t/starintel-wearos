package actor.starintel.quasar.workflows

import org.json.JSONArray
import org.json.JSONObject

@JvmInline
value class WorkflowId private constructor(val value: String) {
    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        fun parse(raw: String): WorkflowId {
            require(PATTERN.matches(raw)) { "Invalid workflow id" }
            return WorkflowId(raw)
        }
    }
}

enum class WorkflowRunStatus {
    IDLE,
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    STOPPED,
}

enum class WorkflowConnectivity {
    ONLINE,
    OFFLINE,
}

enum class WorkflowCommand {
    RUN,
    PAUSE,
    RESUME,
    STOP,
}

@ConsistentCopyVisibility
data class WorkflowWidgetSummary private constructor(
    val id: WorkflowId,
    val label: String,
    val status: WorkflowRunStatus,
    val lastRunEpochMillis: Long?,
    val lastFailure: String?,
    val pendingCommand: WorkflowCommand?,
) {
    companion object {
        const val MAX_LABEL_CHARS = 64
        const val MAX_FAILURE_CHARS = 160

        fun create(
            id: String,
            label: String,
            status: WorkflowRunStatus,
            lastRunEpochMillis: Long? = null,
            lastFailure: String? = null,
            pendingCommand: WorkflowCommand? = null,
        ): WorkflowWidgetSummary {
            require(lastRunEpochMillis == null || lastRunEpochMillis >= 0) { "Invalid last-run time" }
            val safeLabel = singleLine(label).take(MAX_LABEL_CHARS).ifBlank { id }
            val safeFailure = lastFailure
                ?.let(::singleLine)
                ?.take(MAX_FAILURE_CHARS)
                ?.ifBlank { null }
            return WorkflowWidgetSummary(
                id = WorkflowId.parse(id),
                label = safeLabel,
                status = status,
                lastRunEpochMillis = lastRunEpochMillis,
                lastFailure = safeFailure,
                pendingCommand = pendingCommand,
            )
        }

        private fun singleLine(value: String): String = value
            .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

data class WorkflowWidgetState(
    val connectivity: WorkflowConnectivity,
    val workflows: List<WorkflowWidgetSummary>,
    val updatedAtEpochMillis: Long? = null,
) {
    init {
        require(workflows.size <= MAX_WORKFLOWS) { "Too many widget workflows" }
        require(updatedAtEpochMillis == null || updatedAtEpochMillis >= 0) { "Invalid update time" }
    }

    companion object {
        const val MAX_WORKFLOWS = 32
        val OFFLINE_EMPTY = WorkflowWidgetState(WorkflowConnectivity.OFFLINE, emptyList())
    }
}

object WorkflowWidgetStateCodec {
    private const val VERSION = 1

    fun encode(state: WorkflowWidgetState): String = JSONObject()
        .put("version", VERSION)
        .put("connectivity", state.connectivity.name)
        .put("updatedAt", state.updatedAtEpochMillis ?: JSONObject.NULL)
        .put(
            "workflows",
            JSONArray().apply {
                state.workflows.forEach { summary ->
                    put(
                        JSONObject()
                            .put("id", summary.id.value)
                            .put("label", summary.label)
                            .put("status", summary.status.name)
                            .put("lastRun", summary.lastRunEpochMillis ?: JSONObject.NULL)
                            .put("failure", summary.lastFailure ?: JSONObject.NULL)
                            .put("pending", summary.pendingCommand?.name ?: JSONObject.NULL),
                    )
                }
            },
        )
        .toString()

    fun decode(raw: String?): WorkflowWidgetState = runCatching {
        if (raw.isNullOrBlank()) return@runCatching WorkflowWidgetState.OFFLINE_EMPTY
        val root = JSONObject(raw)
        require(root.optInt("version", -1) == VERSION) { "Unsupported workflow widget state" }
        val rows = root.optJSONArray("workflows") ?: JSONArray()
        val workflows = buildList {
            for (index in 0 until rows.length().coerceAtMost(WorkflowWidgetState.MAX_WORKFLOWS)) {
                val row = rows.optJSONObject(index) ?: continue
                runCatching {
                    WorkflowWidgetSummary.create(
                        id = row.getString("id"),
                        label = row.optString("label", row.getString("id")),
                        status = WorkflowRunStatus.valueOf(row.getString("status")),
                        lastRunEpochMillis = row.nullableLong("lastRun"),
                        lastFailure = row.nullableString("failure"),
                        pendingCommand = row.nullableString("pending")?.let(WorkflowCommand::valueOf),
                    )
                }.getOrNull()?.let(::add)
            }
        }
        WorkflowWidgetState(
            connectivity = enumValueOrDefault(root.optString("connectivity"), WorkflowConnectivity.OFFLINE),
            workflows = workflows,
            updatedAtEpochMillis = root.nullableLong("updatedAt"),
        )
    }.getOrDefault(WorkflowWidgetState.OFFLINE_EMPTY)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == raw } ?: fallback

    private fun JSONObject.nullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.nullableLong(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null
}
