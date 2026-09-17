package actor.starintel.quasar.workflows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowWidgetActionRoutingTest {
    @Test
    fun exactAppActionsRouteToTypedCommands() {
        val repository = RecordingWorkflowCommandRepository()
        val controller = BoundedWorkflowActionController(repository)

        val receipt = controller.route(
            action = WorkflowWidgetActions.RUN,
            workflowId = "daily-osint",
            requestId = "request_token_123456",
        )

        assertTrue(receipt.accepted)
        assertEquals(
            WorkflowCommandRequest(
                workflowId = WorkflowId.parse("daily-osint"),
                command = WorkflowCommand.RUN,
                source = WorkflowCommandSource.HOME_WIDGET,
                requestId = "request_token_123456",
            ),
            repository.requests.single(),
        )
    }

    @Test
    fun unknownActionsAndInjectionShapedIdsAreRejectedBeforeRepository() {
        val repository = RecordingWorkflowCommandRepository()
        val controller = BoundedWorkflowActionController(repository)

        assertThrows(IllegalArgumentException::class.java) {
            controller.route("actor.starintel.quasar.workflow.action.EVAL", "daily", "request_token_123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            controller.route(WorkflowWidgetActions.RUN, "(load \"/tmp/payload\")", "request_token_123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            controller.route(WorkflowWidgetActions.RUN, "../daily", "request_token_123456")
        }

        assertTrue(repository.requests.isEmpty())
    }

    @Test
    fun shortOrMalformedReplayTokensAreRejected() {
        val controller = BoundedWorkflowActionController(RecordingWorkflowCommandRepository())

        assertThrows(IllegalArgumentException::class.java) {
            controller.route(WorkflowWidgetActions.STOP, "daily", "short")
        }
        assertThrows(IllegalArgumentException::class.java) {
            controller.route(WorkflowWidgetActions.STOP, "daily", "request token with spaces")
        }
    }

    @Test
    fun commandPolicyOnlyAllowsMeaningfulTransitions() {
        assertTrue(WorkflowCommandPolicy.isAllowed(WorkflowRunStatus.RUNNING, WorkflowCommand.PAUSE))
        assertTrue(WorkflowCommandPolicy.isAllowed(WorkflowRunStatus.PAUSED, WorkflowCommand.RESUME))
        assertTrue(WorkflowCommandPolicy.isAllowed(WorkflowRunStatus.RUNNING, WorkflowCommand.STOP))
        assertFalse(WorkflowCommandPolicy.isAllowed(WorkflowRunStatus.IDLE, WorkflowCommand.PAUSE))
        assertFalse(WorkflowCommandPolicy.isAllowed(WorkflowRunStatus.FAILED, WorkflowCommand.RESUME))
    }

    private class RecordingWorkflowCommandRepository : WorkflowCommandRepository {
        val requests = mutableListOf<WorkflowCommandRequest>()

        override fun request(request: WorkflowCommandRequest): WorkflowCommandReceipt {
            requests += request
            return WorkflowCommandReceipt(accepted = true, duplicate = false)
        }
    }
}
