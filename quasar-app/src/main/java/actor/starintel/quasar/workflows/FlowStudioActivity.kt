package actor.starintel.quasar.workflows

import actor.starintel.android.fbp.FlowGraph
import actor.starintel.android.fbp.FlowRunHandle
import actor.starintel.android.fbp.FlowRunStatus
import actor.starintel.quasar.LocalRuntimeController
import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.UUID
import org.json.JSONObject

class FlowStudioActivity : Activity() {
    private val runtime by lazy { LocalRuntimeController(this) }
    private val definitions by lazy { FlowDefinitionStore(File(filesDir, "workflows")) }
    private val projection by lazy { SharedPreferencesWorkflowRepository(this) }
    private lateinit var editor: EditText
    private lateinit var status: TextView
    private var active: FlowRunHandle? = null
    private var activeGraphId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background
        render()
    }

    override fun onResume() {
        super.onResume()
        consumeWidgetCommands()
    }

    override fun onDestroy() {
        active?.cancel()
        runtime.close()
        super.onDestroy()
    }

    private fun render() {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(36))
            setBackgroundColor(QuasarDesign.background)
            addView(QuasarDesign.eyebrow(this@FlowStudioActivity, "J. Paul Morrison FBP"))
            addView(QuasarDesign.title(this@FlowStudioActivity, "Flow studio", 29f), QuasarDesign.match(top = dp(6)))
            addView(
                QuasarDesign.body(
                    this@FlowStudioActivity,
                    "Processes communicate only through named ports, bounded connections, and information packets. Graphs are closed data; host code owns capabilities.",
                ),
                QuasarDesign.match(top = dp(8)),
            )
        }
        body.addView(runtimeCard(), QuasarDesign.match(top = dp(18)))
        body.addView(QuasarDesign.eyebrow(this, "GRAPH SOURCE"), QuasarDesign.match(top = dp(22)))
        editor = QuasarDesign.field(this, initialSource(), lines = 24).apply {
            typeface = Typeface.MONOSPACE
            contentDescription = "FBP graph JSON editor"
        }
        body.addView(editor, QuasarDesign.match(top = dp(7)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(action("Validate + save") { validateAndSave() }, weight())
            addView(action("Run") { runGraph() }, weight(dp(7)))
            addView(action("Stop") { stopGraph() }, weight(dp(7)))
        }
        body.addView(actions, QuasarDesign.match(top = dp(10)))
        status = QuasarDesign.body(this, "Ready. ${definitions.list().size} saved workflow(s).", QuasarDesign.muted, 12f)
        body.addView(status, QuasarDesign.match(top = dp(10)))
        body.addView(nodeCatalog(), QuasarDesign.match(top = dp(22)))
        body.addView(
            QuasarDesign.action(this, "Open workflow monitor") {
                startActivity(android.content.Intent(this, WorkflowStatusActivity::class.java))
            },
            QuasarDesign.match(top = dp(14)),
        )
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun runtimeCard(): LinearLayout {
        val lisp = runtime.lispStatus()
        val tek9 = runtime.tek9Status()
        return QuasarDesign.card(this, if (lisp.available && tek9.available) QuasarDesign.lime else QuasarDesign.amber).apply {
            addView(QuasarDesign.eyebrow(this@FlowStudioActivity, "CONTROL PLANE", if (lisp.available) QuasarDesign.lime else QuasarDesign.amber))
            addView(QuasarDesign.title(this@FlowStudioActivity, "Common Lisp host · Kotlin FBP projection", 18f), QuasarDesign.match(top = dp(7)))
            addView(
                QuasarDesign.body(
                    this@FlowStudioActivity,
                    "${lisp.detail} · ${tek9.detail}",
                    QuasarDesign.muted,
                    11f,
                ),
                QuasarDesign.match(top = dp(6)),
            )
        }
    }

    private fun nodeCatalog(): LinearLayout = QuasarDesign.card(this, QuasarDesign.cyan).apply {
        addView(QuasarDesign.eyebrow(this@FlowStudioActivity, "DOCUMENT PIPELINE"))
        addView(QuasarDesign.title(this@FlowStudioActivity, "Tek9 query → Star ingest", 18f), QuasarDesign.match(top = dp(7)))
        addView(
            QuasarDesign.body(
                this@FlowStudioActivity,
                "starintel.tek9-expert-query/v1 searches the existing database. starintel.websocket-document/v1 emits each result over one authenticated, bounded WebSocket; Star owns validation, tenancy, persistence, routing, and acknowledgements.",
                QuasarDesign.text,
                12f,
            ),
            QuasarDesign.match(top = dp(7)),
        )
    }

    private fun validateAndSave(): FlowGraph? = runCatching {
        val candidate = FlowGraph.fromJson(JSONObject(editor.text.toString()))
        val errors = runtime.validateFlow(candidate)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val graph = definitions.save(candidate.toJson().toString())
        editor.setText(graph.toJson().toString(2))
        status.text = "VALID · ${graph.id} · ${graph.processes.size} processes · ${graph.connections.size} connections"
        status.setTextColor(QuasarDesign.lime)
        publish(graph.id, WorkflowRunStatus.IDLE)
        graph
    }.getOrElse { error ->
        status.text = "INVALID · ${error.message ?: "Graph validation failed"}"
        status.setTextColor(QuasarDesign.coral)
        null
    }

    private fun runGraph() {
        val graph = validateAndSave() ?: return
        active?.cancel()
        activeGraphId = graph.id
        active = runCatching { runtime.startFlow(graph) }.getOrElse { error ->
            status.text = "BLOCKED · ${error.message ?: "Runtime unavailable"}"
            status.setTextColor(QuasarDesign.coral)
            publish(graph.id, WorkflowRunStatus.FAILED, error.message)
            return
        }
        status.text = "RUNNING · ${graph.id}"
        status.setTextColor(QuasarDesign.cyan)
        publish(graph.id, WorkflowRunStatus.RUNNING)
        active?.completion?.whenComplete { snapshot, error ->
            runOnUiThread {
                val finalStatus = when {
                    error != null -> FlowRunStatus.FAILED
                    snapshot != null -> snapshot.status
                    else -> FlowRunStatus.FAILED
                }
                status.text = "${finalStatus.name} · ${graph.id}${snapshot?.failure?.let { " · $it" }.orEmpty()}"
                status.setTextColor(if (finalStatus == FlowRunStatus.COMPLETED) QuasarDesign.lime else QuasarDesign.coral)
                publish(
                    graph.id,
                    when (finalStatus) {
                        FlowRunStatus.COMPLETED -> WorkflowRunStatus.SUCCEEDED
                        FlowRunStatus.CANCELLED -> WorkflowRunStatus.STOPPED
                        else -> WorkflowRunStatus.FAILED
                    },
                    snapshot?.failure ?: error?.message,
                )
                active = null
            }
        }
    }

    private fun stopGraph() {
        val id = activeGraphId ?: return
        if (active?.cancel() == true) {
            status.text = "STOPPING · $id"
            status.setTextColor(QuasarDesign.amber)
            publish(id, WorkflowRunStatus.STOPPED)
        }
    }

    private fun consumeWidgetCommands() {
        projection.pendingCommands().forEach { request ->
            if (request.workflowId.value !in definitions.list()) return@forEach
            when (request.command) {
                WorkflowCommand.RUN, WorkflowCommand.RESUME -> {
                    if (::editor.isInitialized) {
                        editor.setText(definitions.read(request.workflowId.value))
                        runGraph()
                    }
                }
                WorkflowCommand.PAUSE, WorkflowCommand.STOP -> stopGraph()
            }
            projection.acknowledge(request.requestId)
        }
    }

    private fun publish(id: String, runStatus: WorkflowRunStatus, failure: String? = null) {
        val existing = projection.state().workflows.associateBy { it.id.value }.toMutableMap()
        existing[id] = WorkflowWidgetSummary.create(
            id = id,
            label = id.replace('-', ' ').replaceFirstChar(Char::uppercase),
            status = runStatus,
            lastRunEpochMillis = if (runStatus == WorkflowRunStatus.IDLE) null else System.currentTimeMillis(),
            lastFailure = failure,
        )
        projection.publish(
            WorkflowWidgetState(
                connectivity = if (runtime.lispStatus().available) WorkflowConnectivity.ONLINE else WorkflowConnectivity.OFFLINE,
                workflows = existing.values.sortedBy { it.label },
                updatedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        WorkflowWidgetRenderer.updateAll(this)
    }

    private fun action(label: String, onClick: () -> Unit): TextView = QuasarDesign.action(this, label, onClick = onClick)

    private fun weight(start: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = start
    }

    private fun initialSource(): String = definitions.list().firstOrNull()?.let(definitions::read) ?: DEFAULT_GRAPH

    companion object {
        private val DEFAULT_GRAPH = JSONObject(
            """
            {
              "schema":"starintel.fbp.graph/v1",
              "id":"geo-document-stream",
              "processes":[
                {
                  "id":"expert",
                  "component":"starintel.tek9-expert-query/v1",
                  "config":{
                    "query":"dtype:geo",
                    "max_results":40,
                    "max_alerts":0,
                    "where":{"severity":"high"},
                    "alert":{"channel":"field-ops"}
                  }
                },
                {
                  "id":"star_ingest",
                  "component":"starintel.websocket-document/v1",
                  "config":{"max_documents":40}
                }
              ],
              "connections":[
                {
                  "source":{"process":"expert","port":"RESULT"},
                  "target":{"process":"star_ingest","port":"IN"},
                  "capacity":8
                }
              ],
              "iips":[
                {
                  "target":{"process":"expert","port":"TRIGGER"},
                  "value":{"reason":"manual"}
                }
              ]
            }
            """.trimIndent(),
        ).toString(2)
    }
}
