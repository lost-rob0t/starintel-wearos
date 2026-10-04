package actor.starintel.quasar.workflows

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkflowDeepLinkTest {
    @Test
    fun roundTripsOnlyThePrivateWorkflowRoute() {
        val uri = WorkflowDeepLink.forWorkflow(WorkflowId.parse("geo-alerts.v2"))

        assertEquals("quasar://workflow/geo-alerts.v2", uri)
        assertEquals(WorkflowId.parse("geo-alerts.v2"), WorkflowDeepLink.parse(uri))
    }

    @Test
    fun rejectsForeignHostsQueriesFragmentsAndExtraSegments() {
        assertNull(WorkflowDeepLink.parse("https://workflow/geo-alerts"))
        assertNull(WorkflowDeepLink.parse("quasar://evil/geo-alerts"))
        assertNull(WorkflowDeepLink.parse("quasar://workflow/geo-alerts/extra"))
        assertNull(WorkflowDeepLink.parse("quasar://workflow/geo-alerts?lisp=eval"))
        assertNull(WorkflowDeepLink.parse("quasar://workflow/geo-alerts#eval"))
        assertNull(WorkflowDeepLink.parse("quasar://workflow/%28eval%29"))
    }
}
