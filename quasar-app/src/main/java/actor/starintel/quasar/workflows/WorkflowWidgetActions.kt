package actor.starintel.quasar.workflows

object WorkflowWidgetActions {
    const val RUN = "actor.starintel.quasar.workflow.action.RUN"
    const val PAUSE = "actor.starintel.quasar.workflow.action.PAUSE"
    const val RESUME = "actor.starintel.quasar.workflow.action.RESUME"
    const val STOP = "actor.starintel.quasar.workflow.action.STOP"

    const val EXTRA_WORKFLOW_ID = "actor.starintel.quasar.extra.WORKFLOW_ID"
    const val EXTRA_REQUEST_ID = "actor.starintel.quasar.extra.REQUEST_ID"

    fun commandFor(action: String?): WorkflowCommand = when (action) {
        RUN -> WorkflowCommand.RUN
        PAUSE -> WorkflowCommand.PAUSE
        RESUME -> WorkflowCommand.RESUME
        STOP -> WorkflowCommand.STOP
        else -> throw IllegalArgumentException("Unknown workflow widget action")
    }

    fun actionFor(command: WorkflowCommand): String = when (command) {
        WorkflowCommand.RUN -> RUN
        WorkflowCommand.PAUSE -> PAUSE
        WorkflowCommand.RESUME -> RESUME
        WorkflowCommand.STOP -> STOP
    }
}

enum class WorkflowCommandSource {
    HOME_WIDGET,
    IN_APP,
}

data class WorkflowCommandRequest(
    val workflowId: WorkflowId,
    val command: WorkflowCommand,
    val source: WorkflowCommandSource,
    val requestId: String,
) {
    init {
        require(REQUEST_ID_PATTERN.matches(requestId)) { "Invalid workflow request id" }
    }

    companion object {
        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
    }
}

data class WorkflowCommandReceipt(
    val accepted: Boolean,
    val duplicate: Boolean,
    val reason: String? = null,
)

interface WorkflowCommandRepository {
    /** Accepts only a typed runtime command. No source text or evaluator form crosses this boundary. */
    fun request(request: WorkflowCommandRequest): WorkflowCommandReceipt
}

class BoundedWorkflowActionController(
    private val repository: WorkflowCommandRepository,
) {
    fun route(
        action: String?,
        workflowId: String?,
        requestId: String?,
    ): WorkflowCommandReceipt = request(
        command = WorkflowWidgetActions.commandFor(action),
        workflowId = workflowId,
        requestId = requestId,
        source = WorkflowCommandSource.HOME_WIDGET,
    )

    fun request(
        command: WorkflowCommand,
        workflowId: String?,
        requestId: String?,
        source: WorkflowCommandSource,
    ): WorkflowCommandReceipt {
        requireNotNull(workflowId) { "Missing workflow id" }
        requireNotNull(requestId) { "Missing workflow request id" }
        return repository.request(
            WorkflowCommandRequest(
                workflowId = WorkflowId.parse(workflowId),
                command = command,
                source = source,
                requestId = requestId,
            ),
        )
    }
}

object WorkflowCommandPolicy {
    fun isAllowed(status: WorkflowRunStatus, command: WorkflowCommand): Boolean = when (command) {
        WorkflowCommand.RUN -> status in setOf(
            WorkflowRunStatus.IDLE,
            WorkflowRunStatus.SUCCEEDED,
            WorkflowRunStatus.FAILED,
            WorkflowRunStatus.STOPPED,
        )
        WorkflowCommand.PAUSE -> status == WorkflowRunStatus.RUNNING
        WorkflowCommand.RESUME -> status == WorkflowRunStatus.PAUSED
        WorkflowCommand.STOP -> status in setOf(
            WorkflowRunStatus.QUEUED,
            WorkflowRunStatus.RUNNING,
            WorkflowRunStatus.PAUSED,
        )
    }
}
