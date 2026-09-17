package actor.starintel.quasar.workflows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowWidgetStateCodecTest {
    @Test
    fun roundTripPreservesOperationalState() {
        val state = WorkflowWidgetState(
            connectivity = WorkflowConnectivity.OFFLINE,
            workflows = listOf(
                WorkflowWidgetSummary.create(
                    id = "nightly-enrichment",
                    label = "Nightly enrichment",
                    status = WorkflowRunStatus.FAILED,
                    lastRunEpochMillis = 1_800_000_000_000,
                    lastFailure = "Actor timed out",
                    pendingCommand = WorkflowCommand.RUN,
                ),
            ),
        )

        assertEquals(state, WorkflowWidgetStateCodec.decode(WorkflowWidgetStateCodec.encode(state)))
    }

    @Test
    fun failureTextIsSingleLineBoundedAndDoesNotCreateAnExecutableField() {
        val summary = WorkflowWidgetSummary.create(
            id = "geo-alerts",
            label = "Geo alerts",
            status = WorkflowRunStatus.FAILED,
            lastFailure = "boom\n(eval '(delete-everything))" + "x".repeat(300),
        )
        val encoded = WorkflowWidgetStateCodec.encode(
            WorkflowWidgetState(WorkflowConnectivity.ONLINE, listOf(summary)),
        )

        assertFalse(summary.lastFailure!!.contains('\n'))
        assertTrue(summary.lastFailure!!.length <= WorkflowWidgetSummary.MAX_FAILURE_CHARS)
        assertFalse(encoded.contains("lisp", ignoreCase = true))
        assertFalse(encoded.contains("form", ignoreCase = true))
    }

    @Test
    fun malformedPayloadFallsBackToAnOfflineEmptyState() {
        val decoded = WorkflowWidgetStateCodec.decode("{broken")

        assertEquals(WorkflowConnectivity.OFFLINE, decoded.connectivity)
        assertTrue(decoded.workflows.isEmpty())
        assertNull(decoded.updatedAtEpochMillis)
    }
}
