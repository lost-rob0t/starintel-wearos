package actor.starintel.quasar.workflows

import actor.starintel.quasar.R
import android.content.Context
import java.text.DateFormat
import java.util.Date

internal object WorkflowSnapshotText {
    fun describe(context: Context, state: WorkflowWidgetState, now: Long = System.currentTimeMillis()): String {
        val freshness = WorkflowSnapshotPolicy.freshness(state, now)
        if (freshness == WorkflowSnapshotPolicy.Freshness.MISSING) return context.getString(R.string.workflow_snapshot_missing)
        val timestamp = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(requireNotNull(state.updatedAtEpochMillis)))
        val report = context.getString(R.string.workflow_last_report, state.connectivity.name.lowercase(), timestamp)
        val caveat = when (freshness) {
            WorkflowSnapshotPolicy.Freshness.FUTURE -> R.string.workflow_snapshot_future
            WorkflowSnapshotPolicy.Freshness.STALE -> R.string.workflow_snapshot_stale
            else -> R.string.workflow_snapshot_cached
        }
        return "$report\n${context.getString(caveat)}"
    }
}
