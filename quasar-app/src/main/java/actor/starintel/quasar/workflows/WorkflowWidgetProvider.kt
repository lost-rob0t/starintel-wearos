package actor.starintel.quasar.workflows

import actor.starintel.quasar.R
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import java.util.UUID

class WorkflowWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(
                WorkflowWidgetActions.RUN,
                WorkflowWidgetActions.PAUSE,
                WorkflowWidgetActions.RESUME,
                WorkflowWidgetActions.STOP,
            )
        ) {
            // This receiver is launcher-visible for AppWidget updates only. Commands use the
            // non-exported WorkflowWidgetActionReceiver.
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> WorkflowWidgetRenderer.update(context, appWidgetManager, id) }
    }
}

class WorkflowWidgetActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val applicationContext = context.applicationContext
        Thread({
            try {
                val repository = SharedPreferencesWorkflowRepository(applicationContext)
                BoundedWorkflowActionController(repository).route(
                    action = intent.action,
                    workflowId = intent.getStringExtra(WorkflowWidgetActions.EXTRA_WORKFLOW_ID),
                    requestId = intent.getStringExtra(WorkflowWidgetActions.EXTRA_REQUEST_ID),
                )
            } catch (_: IllegalArgumentException) {
                // Deliberately ignore malformed or unknown requests. No evaluator fallback exists.
            } finally {
                WorkflowWidgetRenderer.updateAll(applicationContext)
                pendingResult.finish()
            }
        }, "quasar-workflow-widget").start()
    }
}

internal object WorkflowWidgetRenderer {
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, WorkflowWidgetProvider::class.java))
        ids.forEach { update(context, manager, it) }
    }

    fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val state = SharedPreferencesWorkflowRepository(context).state()
        val workflows = state.workflows.sortedWith(
            compareBy<WorkflowWidgetSummary> { statusRank(it) }.thenBy { it.label.lowercase() },
        )
        val active = workflows.firstOrNull()
        val views = RemoteViews(context.packageName, R.layout.widget_workflow_control)
        views.setTextViewText(
            R.id.workflow_widget_connectivity,
            context.getString(
                if (state.connectivity == WorkflowConnectivity.ONLINE) R.string.workflow_online
                else R.string.workflow_offline,
            ),
        )
        views.setTextColor(
            R.id.workflow_widget_connectivity,
            context.getColor(
                if (state.connectivity == WorkflowConnectivity.ONLINE) R.color.quasar_widget_cyan
                else R.color.quasar_widget_gold,
            ),
        )
        views.setOnClickPendingIntent(
            R.id.workflow_widget_header,
            statusPendingIntent(context, workflowId = null, widgetId = widgetId),
        )
        views.setViewVisibility(R.id.workflow_widget_empty, if (active == null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.workflow_widget_active, if (active == null) View.GONE else View.VISIBLE)

        if (active != null) {
            bindActive(context, views, widgetId, active)
        }
        bindSecondary(context, views, R.id.workflow_widget_row_one, workflows.getOrNull(1), widgetId)
        bindSecondary(context, views, R.id.workflow_widget_row_two, workflows.getOrNull(2), widgetId)
        manager.updateAppWidget(widgetId, views)
    }

    private fun bindActive(
        context: Context,
        views: RemoteViews,
        widgetId: Int,
        summary: WorkflowWidgetSummary,
    ) {
        views.setTextViewText(R.id.workflow_widget_active_name, summary.label)
        views.setTextViewText(
            R.id.workflow_widget_active_status,
            summary.pendingCommand?.let { "${it.name} PENDING" } ?: summary.status.name,
        )
        views.setTextColor(R.id.workflow_widget_active_status, context.getColor(statusColor(summary.status)))
        views.setTextViewText(R.id.workflow_widget_last_run, lastRunText(summary.lastRunEpochMillis))
        val failure = summary.lastFailure.takeIf { summary.status == WorkflowRunStatus.FAILED }
        views.setViewVisibility(R.id.workflow_widget_failure, if (failure == null) View.GONE else View.VISIBLE)
        views.setTextViewText(R.id.workflow_widget_failure, failure.orEmpty())
        views.setOnClickPendingIntent(
            R.id.workflow_widget_active_name,
            statusPendingIntent(context, summary.id, widgetId),
        )

        val controls = mapOf(
            R.id.workflow_widget_run to WorkflowCommand.RUN,
            R.id.workflow_widget_pause to WorkflowCommand.PAUSE,
            R.id.workflow_widget_resume to WorkflowCommand.RESUME,
            R.id.workflow_widget_stop to WorkflowCommand.STOP,
        )
        controls.forEach { (viewId, command) ->
            val visible = summary.pendingCommand == null && WorkflowCommandPolicy.isAllowed(summary.status, command)
            views.setViewVisibility(viewId, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                views.setOnClickPendingIntent(
                    viewId,
                    commandPendingIntent(context, widgetId, summary.id, command),
                )
            }
        }
    }

    private fun bindSecondary(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        summary: WorkflowWidgetSummary?,
        widgetId: Int,
    ) {
        views.setViewVisibility(viewId, if (summary == null) View.GONE else View.VISIBLE)
        if (summary == null) return
        val pending = summary.pendingCommand?.let { " · ${it.name.lowercase()} pending" }.orEmpty()
        views.setTextViewText(viewId, "${summary.label}   ${summary.status.name.lowercase()}$pending")
        views.setOnClickPendingIntent(viewId, statusPendingIntent(context, summary.id, widgetId))
    }

    private fun commandPendingIntent(
        context: Context,
        widgetId: Int,
        workflowId: WorkflowId,
        command: WorkflowCommand,
    ): PendingIntent {
        val requestId = UUID.randomUUID().toString()
        val intent = Intent(context, WorkflowWidgetActionReceiver::class.java).apply {
            action = WorkflowWidgetActions.actionFor(command)
            data = Uri.parse("quasar-widget-action://${command.name.lowercase()}/${workflowId.value}/$widgetId/$requestId")
            putExtra(WorkflowWidgetActions.EXTRA_WORKFLOW_ID, workflowId.value)
            putExtra(WorkflowWidgetActions.EXTRA_REQUEST_ID, requestId)
        }
        return PendingIntent.getBroadcast(
            context,
            stableRequestCode(widgetId, workflowId, command.ordinal),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun statusPendingIntent(context: Context, workflowId: WorkflowId?, widgetId: Int): PendingIntent {
        val intent = Intent(context, WorkflowStatusActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            workflowId?.let { data = Uri.parse(WorkflowDeepLink.forWorkflow(it)) }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            workflowId?.let { stableRequestCode(widgetId, it, 97) } ?: (widgetId * 31 + 113),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stableRequestCode(widgetId: Int, workflowId: WorkflowId, discriminator: Int): Int =
        31 * (31 * widgetId + workflowId.value.hashCode()) + discriminator

    private fun statusRank(summary: WorkflowWidgetSummary): Int = when {
        summary.pendingCommand != null -> 0
        summary.status == WorkflowRunStatus.RUNNING -> 1
        summary.status == WorkflowRunStatus.PAUSED -> 2
        summary.status == WorkflowRunStatus.QUEUED -> 3
        summary.status == WorkflowRunStatus.FAILED -> 4
        else -> 5
    }

    private fun statusColor(status: WorkflowRunStatus): Int = when (status) {
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.SUCCEEDED -> R.color.quasar_widget_cyan
        WorkflowRunStatus.QUEUED, WorkflowRunStatus.PAUSED -> R.color.quasar_widget_gold
        WorkflowRunStatus.FAILED -> R.color.quasar_widget_failure
        else -> R.color.quasar_widget_muted
    }

    private fun lastRunText(epochMillis: Long?): CharSequence = if (epochMillis == null) {
        "Never run"
    } else {
        "Last run ${DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    }
}
