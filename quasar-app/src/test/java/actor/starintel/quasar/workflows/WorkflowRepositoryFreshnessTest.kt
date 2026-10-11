package actor.starintel.quasar.workflows

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

/** Exercises repository serialization/transactions with an in-memory preferences interface.
 * Android framework persistence and activity lifecycle still require instrumentation. */
class WorkflowRepositoryFreshnessTest {
    private var now = 1_800_000_000_000L
    private val repository = SharedPreferencesWorkflowRepository(memoryPreferences(), { now })
    private fun snapshot(time: Long? = now, online: Boolean = true) = WorkflowWidgetState(
        if (online) WorkflowConnectivity.ONLINE else WorkflowConnectivity.OFFLINE,
        listOf(WorkflowWidgetSummary.create("daily", "Daily", WorkflowRunStatus.IDLE)), time)
    private fun request(token: String = "request_token_123456") = WorkflowCommandRequest(
        WorkflowId.parse("daily"), WorkflowCommand.RUN, WorkflowCommandSource.HOME_WIDGET, token)

    @Test fun enqueuePreservesObservationAndPendingReplayDoesNotDuplicate() {
        val observed = now - 1000
        assertTrue(repository.publish(snapshot(observed)))
        assertTrue(repository.request(request()).accepted)
        assertEquals(observed, repository.state().updatedAtEpochMillis)
        now += WorkflowSnapshotPolicy.MAX_AGE_MILLIS
        assertTrue(repository.request(request()).duplicate)
        assertEquals(1, repository.pendingCommands().size)
        assertEquals(WorkflowRunStatus.IDLE, repository.state().workflows.single().status)
    }

    @Test fun staleMissingFutureAndOfflineReportsRejectWithoutWritingOutbox() {
        for (state in listOf(snapshot(null), snapshot(now + 1),
            snapshot(now - WorkflowSnapshotPolicy.MAX_AGE_MILLIS - 1), snapshot(online = false))) {
            repository.publish(state)
            assertFalse(repository.request(request()).accepted)
            assertTrue(repository.pendingCommands().isEmpty())
            assertEquals(state, repository.state())
        }
    }

    @Test fun publicationCannotPermitAnotherCommandWhileOutboxStillContainsWorkflow() {
        repository.publish(snapshot())
        repository.request(request())
        repository.publish(snapshot())
        assertFalse(repository.request(request("request_token_654321")).accepted)
        assertEquals(1, repository.pendingCommands().size)
        assertTrue(repository.acknowledge(request().requestId))
        assertFalse(repository.acknowledge(request().requestId))
        assertTrue(repository.pendingCommands().isEmpty())
        assertTrue(repository.request(request("request_token_654321")).accepted)
    }

    @Test fun acknowledgementAloneDoesNotClearPendingObservation() {
        repository.publish(snapshot())
        repository.request(request())
        repository.acknowledge(request().requestId)
        assertFalse(repository.request(request("request_token_654321")).accepted)
        now += WorkflowSnapshotPolicy.MAX_AGE_MILLIS + 1
        repository.publish(snapshot(now - WorkflowSnapshotPolicy.MAX_AGE_MILLIS - 1))
        assertFalse(repository.request(request()).accepted)
    }

    private fun memoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, String?>()
        val changes = mutableMapOf<String, String?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { changes[args!![0] as String] = args[1] as String?; proxy }
                "commit" -> { values.putAll(changes); changes.clear(); true }
                else -> error("Unexpected editor method ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values.getOrDefault(args!![0] as String, args[1] as String?)
                "edit" -> editor
                else -> error("Unexpected preferences method ${method.name}")
            }
        } as SharedPreferences
    }
}
