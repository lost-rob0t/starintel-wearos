package actor.starintel.quasar.workflows

import org.junit.Assert.*
import org.junit.Test

class WorkflowSnapshotPolicyTest {
    private val now = 1_800_000_000_000L
    private fun state(time: Long?, online: Boolean = true) = WorkflowWidgetState(
        if (online) WorkflowConnectivity.ONLINE else WorkflowConnectivity.OFFLINE, emptyList(), time)

    @Test fun missingFutureAndExpiredReportsCannotAuthorizeCommands() {
        assertEquals(WorkflowSnapshotPolicy.Freshness.MISSING, WorkflowSnapshotPolicy.freshness(state(null), now))
        assertEquals(WorkflowSnapshotPolicy.Freshness.FUTURE, WorkflowSnapshotPolicy.freshness(state(now + 1), now))
        for (time in listOf(null, now + 1, now - WorkflowSnapshotPolicy.MAX_AGE_MILLIS - 1, 0L)) {
            assertFalse(WorkflowSnapshotPolicy.canRequest(state(time), now))
        }
    }

    @Test fun exactAgeBoundaryAndOfflineReportAreHandled() {
        assertTrue(WorkflowSnapshotPolicy.canRequest(state(now), now))
        assertTrue(WorkflowSnapshotPolicy.canRequest(state(now - WorkflowSnapshotPolicy.MAX_AGE_MILLIS), now))
        assertFalse(WorkflowSnapshotPolicy.canRequest(state(now, false), now))
        assertFalse(WorkflowSnapshotPolicy.canRequest(state(Long.MAX_VALUE), now))
    }
}
