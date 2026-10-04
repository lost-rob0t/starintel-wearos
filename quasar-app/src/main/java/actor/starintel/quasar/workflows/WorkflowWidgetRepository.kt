package actor.starintel.quasar.workflows

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable hand-off shared by the native UI and the trusted FBP control plane.
 *
 * UI callers may enqueue only [WorkflowCommandRequest]. The Common Lisp runtime owns execution,
 * publishes authoritative snapshots, and acknowledges request ids after consuming the outbox.
 */
internal interface WorkflowRuntimeBridge {
    fun state(): WorkflowWidgetState
    fun publish(state: WorkflowWidgetState): Boolean
    fun pendingCommands(limit: Int = MAX_COMMAND_READ): List<WorkflowCommandRequest>
    fun acknowledge(requestId: String): Boolean

    companion object {
        const val MAX_COMMAND_READ = 32
    }
}

internal class SharedPreferencesWorkflowRepository(context: Context) :
    WorkflowCommandRepository,
    WorkflowRuntimeBridge {
    private val applicationContext = context.applicationContext
    private val preferences: SharedPreferences = applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun state(): WorkflowWidgetState = synchronized(LOCK) {
        WorkflowWidgetStateCodec.decode(preferences.getString(KEY_STATE, null))
    }

    override fun publish(state: WorkflowWidgetState): Boolean {
        val committed = synchronized(LOCK) {
            preferences.edit()
                .putString(KEY_STATE, WorkflowWidgetStateCodec.encode(state))
                .commit()
        }
        if (committed) WorkflowWidgetRenderer.updateAll(applicationContext)
        return committed
    }

    override fun request(request: WorkflowCommandRequest): WorkflowCommandReceipt = synchronized(LOCK) {
        val queued = readCommands()
        if (queued.any { it.requestId == request.requestId }) {
            return@synchronized WorkflowCommandReceipt(accepted = true, duplicate = true)
        }
        if (queued.size >= MAX_PENDING_COMMANDS) {
            return@synchronized WorkflowCommandReceipt(false, false, "Workflow command queue is full")
        }

        val currentState = state()
        val current = currentState.workflows.firstOrNull { it.id == request.workflowId }
            ?: return@synchronized WorkflowCommandReceipt(false, false, "Unknown workflow")
        if (current.pendingCommand != null) {
            return@synchronized WorkflowCommandReceipt(false, false, "A workflow command is already pending")
        }
        if (!WorkflowCommandPolicy.isAllowed(current.status, request.command)) {
            return@synchronized WorkflowCommandReceipt(false, false, "Command is not allowed in ${current.status.name}")
        }

        val nextSummary = WorkflowWidgetSummary.create(
            id = current.id.value,
            label = current.label,
            status = current.status,
            lastRunEpochMillis = current.lastRunEpochMillis,
            lastFailure = current.lastFailure,
            pendingCommand = request.command,
        )
        val nextState = currentState.copy(
            workflows = currentState.workflows.map { if (it.id == request.workflowId) nextSummary else it },
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
        val nextCommands = queued + request
        val committed = preferences.edit()
            .putString(KEY_STATE, WorkflowWidgetStateCodec.encode(nextState))
            .putString(KEY_COMMANDS, encodeCommands(nextCommands))
            .commit()
        if (committed) WorkflowCommandReceipt(true, false)
        else WorkflowCommandReceipt(false, false, "Could not persist workflow command")
    }

    override fun pendingCommands(limit: Int): List<WorkflowCommandRequest> = synchronized(LOCK) {
        require(limit in 1..WorkflowRuntimeBridge.MAX_COMMAND_READ) { "Invalid command read limit" }
        readCommands().take(limit)
    }

    override fun acknowledge(requestId: String): Boolean = synchronized(LOCK) {
        require(REQUEST_ID_PATTERN.matches(requestId)) { "Invalid workflow request id" }
        val current = readCommands()
        val retained = current.filterNot { it.requestId == requestId }
        if (retained.size == current.size) return@synchronized false
        preferences.edit().putString(KEY_COMMANDS, encodeCommands(retained)).commit()
    }

    private fun readCommands(): List<WorkflowCommandRequest> = runCatching {
        val rows = JSONArray(preferences.getString(KEY_COMMANDS, "[]"))
        buildList {
            for (index in 0 until rows.length().coerceAtMost(MAX_PENDING_COMMANDS)) {
                val row = rows.optJSONObject(index) ?: continue
                runCatching {
                    WorkflowCommandRequest(
                        workflowId = WorkflowId.parse(row.getString("workflowId")),
                        command = WorkflowCommand.valueOf(row.getString("command")),
                        source = WorkflowCommandSource.valueOf(row.getString("source")),
                        requestId = row.getString("requestId"),
                    )
                }.getOrNull()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    private fun encodeCommands(commands: List<WorkflowCommandRequest>): String = JSONArray().apply {
        commands.forEach { request ->
            put(
                JSONObject()
                    .put("workflowId", request.workflowId.value)
                    .put("command", request.command.name)
                    .put("source", request.source.name)
                    .put("requestId", request.requestId),
            )
        }
    }.toString()

    private companion object {
        const val PREFERENCES = "quasar_workflow_control_plane_v1"
        const val KEY_STATE = "widget_state"
        const val KEY_COMMANDS = "command_outbox"
        const val MAX_PENDING_COMMANDS = 64
        val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
        val LOCK = Any()
    }
}
