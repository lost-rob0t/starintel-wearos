package actor.starintel.quasar.workflows

import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.app.Activity
import android.content.Intent
import android.widget.Toast
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class WorkflowStatusActivity : Activity() {
    private val repository by lazy { SharedPreferencesWorkflowRepository(this) }
    private val controller by lazy { BoundedWorkflowActionController(repository) }
    private var selectedId: WorkflowId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background
        selectedId = savedInstanceState?.getString("selectedWorkflow")?.let { runCatching { WorkflowId.parse(it) }.getOrNull() }
            ?: WorkflowDeepLink.parse(intent?.dataString)
    }

    override fun onResume() {
        super.onResume()
        render()
        WorkflowWidgetRenderer.updateAll(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedId = WorkflowDeepLink.parse(intent.dataString)
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selectedWorkflow", selectedId?.value)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val state = repository.state()
        val selected = selectedId?.let { id -> state.workflows.firstOrNull { it.id == id } }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(36))
            setBackgroundColor(QuasarDesign.background)
            addView(QuasarDesign.eyebrow(this@WorkflowStatusActivity, "FBP control plane"))
            addView(
                QuasarDesign.title(
                    this@WorkflowStatusActivity,
                    selected?.label ?: "Workflows",
                    28f,
                ),
                QuasarDesign.match(top = dp(9)),
            )
            addView(
                QuasarDesign.body(
                    this@WorkflowStatusActivity,
                    WorkflowSnapshotText.describe(this@WorkflowStatusActivity, state),
                    QuasarDesign.amber,
                    12f,
                ),
                QuasarDesign.match(top = dp(7)),
            )
        }

        content.addView(QuasarDesign.action(this, "Open Flow studio") {
            startActivity(Intent(this, FlowStudioActivity::class.java))
        }, QuasarDesign.match(top = dp(12)))
        content.addView(QuasarDesign.body(this,
            "Run and Stop are available. Pause and resume are not supported by the current runtime adapter. Controls queue a request for Flow studio to handle when it resumes; a cached report does not confirm execution.",
            QuasarDesign.muted, 12f), QuasarDesign.match(top = dp(8)))

        if (selected != null) {
            content.addView(detailCard(selected, state), QuasarDesign.match(top = dp(20)))
            content.addView(QuasarDesign.eyebrow(this, "All workflows"), QuasarDesign.match(top = dp(28)))
        } else {
            content.addView(
                QuasarDesign.body(this, "Typed operations only. Widget and UI controls cannot submit Lisp forms."),
                QuasarDesign.match(top = dp(13)),
            )
        }

        if (state.workflows.isEmpty()) {
            content.addView(
                QuasarDesign.card(this, QuasarDesign.amber).apply {
                    addView(QuasarDesign.title(this@WorkflowStatusActivity, "No workflow state", 18f))
                    addView(
                        QuasarDesign.body(this@WorkflowStatusActivity, "Flow studio publishes reports when workflows change. Queued controls are handled when Flow studio resumes."),
                        QuasarDesign.match(top = dp(7)),
                    )
                },
                QuasarDesign.match(top = dp(20)),
            )
        } else {
            state.workflows.forEach { workflow ->
                val row = QuasarDesign.card(this).apply {
                    isClickable = true
                    isFocusable = true
                    addView(statusLine(workflow))
                    addView(
                        QuasarDesign.body(this@WorkflowStatusActivity, lastRunText(workflow.lastRunEpochMillis), QuasarDesign.muted, 11f),
                        QuasarDesign.match(top = dp(6)),
                    )
                    setOnClickListener {
                        selectedId = workflow.id
                        render()
                    }
                }
                content.addView(row, QuasarDesign.match(top = dp(8)))
            }
        }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        })
    }

    private fun detailCard(summary: WorkflowWidgetSummary, state: WorkflowWidgetState): LinearLayout =
        QuasarDesign.card(this, statusColor(summary.status)).apply {
            addView(statusLine(summary))
            addView(
                QuasarDesign.body(this@WorkflowStatusActivity, lastRunText(summary.lastRunEpochMillis)),
                QuasarDesign.match(top = dp(8)),
            )
            summary.lastFailure?.takeIf { summary.status == WorkflowRunStatus.FAILED }?.let { failure ->
                addView(QuasarDesign.body(this@WorkflowStatusActivity, failure, QuasarDesign.coral, 12f), QuasarDesign.match(top = dp(8)))
            }
            val commands = WorkflowCommand.entries.filter {
                WorkflowCommandPolicy.isExposedByUi(it) && WorkflowSnapshotPolicy.canRequest(state, System.currentTimeMillis()) &&
                    summary.pendingCommand == null && WorkflowCommandPolicy.isAllowed(summary.status, it)
            }
            if (commands.isNotEmpty()) {
                val actions = LinearLayout(this@WorkflowStatusActivity).apply {
                    gravity = Gravity.END
                    orientation = LinearLayout.HORIZONTAL
                }
                commands.forEachIndexed { index, command ->
                    actions.addView(
                        QuasarDesign.action(this@WorkflowStatusActivity, command.name.lowercase().replaceFirstChar(Char::uppercase)) {
                            val receipt = controller.request(
                                command = command,
                                workflowId = summary.id.value,
                                requestId = UUID.randomUUID().toString(),
                                source = WorkflowCommandSource.IN_APP,
                            )
                            if (receipt.accepted) {
                                WorkflowWidgetRenderer.updateAll(this@WorkflowStatusActivity)
                            } else {
                                Toast.makeText(this@WorkflowStatusActivity, receipt.reason, Toast.LENGTH_LONG).show()
                            }
                            render()
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (index > 0) marginStart = dp(7)
                        },
                    )
                }
                addView(actions, QuasarDesign.match(top = dp(14)))
            }
        }

    private fun statusLine(summary: WorkflowWidgetSummary): LinearLayout = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        orientation = LinearLayout.HORIZONTAL
        addView(
            TextView(this@WorkflowStatusActivity).apply {
                text = summary.label
                textSize = 16f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(QuasarDesign.text)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            QuasarDesign.eyebrow(
                this@WorkflowStatusActivity,
                summary.pendingCommand?.let { "${it.name} pending" } ?: summary.status.name,
                statusColor(summary.status),
            ),
        )
    }

    private fun statusColor(status: WorkflowRunStatus): Int = when (status) {
        WorkflowRunStatus.RUNNING, WorkflowRunStatus.SUCCEEDED -> QuasarDesign.cyan
        WorkflowRunStatus.PAUSED, WorkflowRunStatus.QUEUED -> QuasarDesign.amber
        WorkflowRunStatus.FAILED -> QuasarDesign.coral
        else -> QuasarDesign.muted
    }

    private fun lastRunText(epochMillis: Long?): String = if (epochMillis == null) {
        "Never run"
    } else {
        "Last run ${DateUtils.getRelativeTimeSpanString(epochMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
    }
}
