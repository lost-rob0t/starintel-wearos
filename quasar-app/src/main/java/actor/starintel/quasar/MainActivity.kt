package actor.starintel.quasar

import actor.starintel.android.model.AgentTurnRequest
import actor.starintel.android.model.RemoteActor
import actor.starintel.quasar.field.FieldOpsView
import actor.starintel.quasar.field.GeoDocumentParser
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.os.Parcelable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var viewport: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var navigation: LinearLayout
    private val client by lazy { StarServerClient(this) }
    private val config by lazy { QuasarConfig(this) }
    private val localRuntime by lazy { LocalRuntimeController(this) }
    private val actorDefinitions by lazy { ActorDefinitionStore(this) }

    private lateinit var workspace: WorkspaceState
    private val requests = Executors.newFixedThreadPool(3)
    private var searchTask: Future<*>? = null
    private val searchGeneration = AtomicInteger()
    private val mapGeneration = AtomicInteger()
    private val searchSnapshots = mutableMapOf<String, List<DocumentRow>>()
    private var resultList: RecyclerView? = null
    private var selectedDocument: String? = null
    private var isRestoring = false
    private var serverStatus = "Not checked"
    private var authStatus = "Not checked"
    private var syncStatus = "No active transfer"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuasarDesign.configure(this)
        workspace = WorkspaceState(savedInstanceState?.getBundle("workspace"))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background

        val shell = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 600) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setBackgroundColor(QuasarDesign.background)
        }
        viewport = FrameLayout(this)
        shell.addView(
            viewport,
            if (resources.configuration.screenWidthDp >= 600) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val nav = bottomNavigation()
        if (resources.configuration.screenWidthDp >= 600) shell.addView(nav, LinearLayout.LayoutParams(dp(104), -1))
        else shell.addView(nav, QuasarDesign.match())
        QuasarDesign.install(this, shell)
        restoreRoute(workspace.route)
        if (savedInstanceState == null) handleOperatorIntent(intent)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOperatorIntent(intent)
    }

    /** Operator hub handoff: open one document, or the field map focused on a point. */
    private fun handleOperatorIntent(intent: Intent?) {
        when (intent?.action) {
            actor.starintel.android.config.OperatorContracts.ACTION_OPEN_DOCUMENT -> {
                val documentId = actor.starintel.android.config.OperatorContracts.normalizeDocumentId(
                    intent.getStringExtra(actor.starintel.android.config.OperatorContracts.EXTRA_DOCUMENT_ID),
                )
                if (documentId.isNotEmpty()) showDocument(documentId)
            }
            actor.starintel.android.config.OperatorContracts.ACTION_OPEN_MAP -> {
                val latitude = intent.getDoubleExtra(actor.starintel.android.config.OperatorContracts.EXTRA_LATITUDE, Double.NaN)
                val longitude = intent.getDoubleExtra(actor.starintel.android.config.OperatorContracts.EXTRA_LONGITUDE, Double.NaN)
                showFieldOps()
                if (latitude.isFinite() && longitude.isFinite()) {
                    val field = viewport.getChildAt(0) as? FieldOpsView
                    field?.focusOn(latitude, longitude)
                }
            }
            else -> Unit
        }
    }

    override fun onDestroy() {
        searchTask?.cancel(true)
        requests.shutdownNow()
        if (::viewport.isInitialized) runCatching { localRuntime.close() }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        rememberView()
        outState.putBundle("workspace", workspace.save())
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Uses system Back dispatcher on Android 13+")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        rememberView()
        val previous = workspace.back()
        if (previous == null) finish() else restoreRoute(previous)
    }

    private fun restoreRoute(route: String) {
        isRestoring = true
        when {
            route == "field" -> showFieldOps()
            route == "search" -> showSearch("Documents", "*")
            route == "datasets" -> showSearch("Datasets", "dtype:dataset")
            route.startsWith("document:") -> showDocument(route.substringAfter(':'))
            route.startsWith("target:") -> showTarget(route.substringAfter(':'))
            route == "actors" -> showActors()
            route == "create" -> showCreateDocument()
            route == "import" -> showImport()
            route == "agent" -> showAgent()
            route == "actor-editor" -> showActorEditor()
            route == "settings" -> showSettings()
            else -> showHome()
        }
        isRestoring = false
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun rememberView() {
        val root = viewport.getChildAt(0) ?: return
        val state = workspace.views.getBundle(workspace.route) ?: Bundle()
        // Bound editor drafts; password/key views explicitly disable saving.
        val inputs = descendants(root).filterIsInstance<EditText>().filter { it.isSaveEnabled }.toList()
        state.putStringArrayList("inputs", ArrayList(inputs.map { it.text.toString().take(16_384) }))
        state.putIntArray("selection", inputs.map { it.selectionStart.coerceAtLeast(0) }.toIntArray())
        state.putInt("scroll", (root as? ScrollView)?.scrollY ?: 0)
        resultList?.let { state.putParcelable("list", it.layoutManager?.onSaveInstanceState()) }
        state.putString("selected", selectedDocument)
        (root as? FieldOpsView)?.let { state.putBundle("field", it.saveWorkspace()) }
        workspace.views.putBundle(workspace.route, state)
    }

    private fun enterRoute(route: String) {
        if (!isRestoring) { rememberView(); workspace.enter(route) }
        workspace.route = route
        resultList = null
        selectedDocument = workspace.views.getBundle(route)?.getString("selected")
        selectNavigation(when {
            route == "field" -> "Field"
            route == "search" || route == "datasets" || route.startsWith("document:") -> "Search"
            route == "actors" || route.startsWith("target:") || route == "agent" || route == "actor-editor" -> "Actors"
            route == "settings" -> "Settings"
            else -> "Home"
        })
        viewport.post {
            if (workspace.route != route) return@post
            val state = workspace.views.getBundle(route) ?: return@post
            val root = viewport.getChildAt(0) ?: return@post
            val values = state.getStringArrayList("inputs") ?: arrayListOf()
            val selections = state.getIntArray("selection") ?: intArrayOf()
            descendants(root).filterIsInstance<EditText>().filter { it.isSaveEnabled }.forEachIndexed { i, input ->
                values.getOrNull(i)?.let { input.setText(it); input.setSelection((selections.getOrNull(i) ?: it.length).coerceAtMost(it.length)) }
            }
            (root as? ScrollView)?.scrollTo(0, state.getInt("scroll"))
        }
    }

    private fun showHome() {
        enterRoute("home")
        val body = screen(
            eyebrow = "StarIntel // Quasar",
            title = "Investigation desk",
            description = "Your corpus, field evidence, and actor work in one workspace.",
        )

        val lisp = localRuntime.lispStatus()
        val runtimeCard = QuasarDesign.card(this, if (lisp.available) QuasarDesign.lime else QuasarDesign.amber)
        runtimeCard.addView(sectionHeader("LOCAL CORE", if (lisp.available) "READY" else "BOOTSTRAP"))
        runtimeCard.addView(QuasarDesign.title(this, "On-device workspace", 20f), QuasarDesign.match(top = dp(12)))
        runtimeCard.addView(
            QuasarDesign.body(
                this,
                if (lisp.available) {
                    "Local tools are available. Connected work uses your server account and its permissions."
                } else {
                    "Local tools are unavailable on this device. Connect a server to browse records and dispatch work."
                },
            ),
            QuasarDesign.match(top = dp(8)),
        )
        body.addView(runtimeCard, QuasarDesign.match(top = dp(20)))

        val metricRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val documents = metric("DOCUMENTS", "—", QuasarDesign.cyan)
        val targets = metric("TARGETS", "—", QuasarDesign.pink)
        val server = metric("SERVER", if (config.isConfigured()) "—" else "OFF", QuasarDesign.amber)
        listOf(documents, targets, server).forEachIndexed { index, view ->
            metricRow.addView(view, weighted(start = if (index == 0) 0 else dp(7)))
        }
        body.addView(metricRow, QuasarDesign.match(top = dp(12)))
        if (config.isConfigured()) {
            runApi({ client.stats() }) { root ->
                val data = root.optJSONObject("data") ?: root
                documents.findViewWithTag<TextView>(VALUE_TAG).text =
                    data.optJSONObject("documents")?.optLong("total")?.compact() ?: "—"
                targets.findViewWithTag<TextView>(VALUE_TAG).text =
                    data.optJSONObject("targets")?.optLong("total")?.compact() ?: "—"
                server.findViewWithTag<TextView>(VALUE_TAG).text = "REPLIED"
            }
        }

        body.addView(QuasarDesign.eyebrow(this, "Workspace"), QuasarDesign.match(top = dp(28)))
        val routes = listOf(
            Route("Field map", "Map geo documents and traverse their links", QuasarDesign.amber) { showFieldOps() },
            Route("Documents", "Search and inspect structured records", QuasarDesign.pink) { showSearch("Documents", "*") },
            Route("Datasets", "Move between investigation scopes", QuasarDesign.amber) { showSearch("Datasets", "dtype:dataset") },
            Route("Targets", "Dispatch actor work with provenance", QuasarDesign.lime) { showTarget() },
            Route("Import", "Bring in bounded StarIntel JSON batches", QuasarDesign.coral) { showImport() },
            Route("Add record", "Create a schema-checked document", QuasarDesign.cyan) { showCreateDocument() },
            Route("Workflow status", "Inspect cached reports and pending controls", QuasarDesign.cyan) {
                startActivity(Intent(this, actor.starintel.quasar.workflows.WorkflowStatusActivity::class.java))
            },
            Route("Flow studio", "Build and run bounded Morrison-style workflows", QuasarDesign.cyan) {
                startActivity(Intent(this, actor.starintel.quasar.workflows.FlowStudioActivity::class.java))
            },
            Route("Logic studio", "Edit private Lisp, Prolog, and expert nodes", QuasarDesign.lime) {
                startActivity(Intent(this, actor.starintel.quasar.ide.LogicStudioActivity::class.java))
            },
            Route("Reasoning agent", "Run a bounded Prolog-RLM investigation turn", QuasarDesign.pink) { showAgent() },
            Route("Actors", "Live server fleet and local definitions", QuasarDesign.amber) { showActors() },
        )
        routes.forEach { route ->
            body.addView(QuasarDesign.routeCard(this, route.label, route.detail, route.accent, route.action), QuasarDesign.match(top = dp(8)))
        }
    }

    private fun showSearch(title: String, initial: String) {
        val route = if (title == "Datasets") "datasets" else "search"
        enterRoute(route)
        viewport.removeAllViews()
        val state = workspace.views.getBundle(route) ?: Bundle().also { workspace.views.putBundle(route, it) }
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), 0)
        }
        columns.addView(body, LinearLayout.LayoutParams(0, -1, 1f))
        val wide = resources.configuration.screenWidthDp >= 600
        val detail = if (wide) LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(emptyState("Select a document", "Keep the result list beside your evidence."))
        } else null
        detail?.let { pane -> columns.addView(ScrollView(this).apply { addView(pane) }, LinearLayout.LayoutParams(0, -1, 1f)) }
        body.addView(QuasarDesign.title(this, title))
        val query = QuasarDesign.field(this, state.getStringArrayList("inputs")?.firstOrNull() ?: initial, hint = "Query documents").apply {
            contentDescription = "Document query"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        body.addView(query, QuasarDesign.match(top = dp(8)))
        val status = QuasarDesign.body(this, "Enter a query, then Search. Up to 40 records per snapshot.").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val adapter = DocumentRows { row ->
            selectedDocument = row.id
            state.putString("selected", row.id)
            if (detail == null) showDocument(row.id) else loadDocument(detail, row.id)
        }
        adapter.selectedId = selectedDocument
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter = adapter
            itemAnimator = null
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(16))
        }
        resultList = list
        fun display(rows: List<DocumentRow>) {
            adapter.submitList(rows) {
                list.layoutManager?.onRestoreInstanceState(state.getParcelable<Parcelable>("list"))
            }
            status.text = if (rows.isEmpty()) "No matching documents. Broaden the query or change the dtype filter."
                else "${rows.size} records loaded · snapshot limited to 40 · corpus total unknown"
        }
        var restoringSearch = false
        val search = QuasarDesign.action(this, "Search", primary = true) {
            val value = query.text.toString().trim()
            if (value.isBlank() || value.length > 512) { query.error = "Enter a query of 1–512 characters"; return@action }
            state.putBoolean("searched", true)
            if (!restoringSearch) state.remove("list")
            val generation = searchGeneration.incrementAndGet()
            searchTask?.cancel(true)
            status.text = "Loading records…"
            searchTask = requests.submit {
                val response = runCatching { DocumentRow.from(client.search(value, 40), 40) }
                runOnUiThread {
                    if (isFinishing || isDestroyed || viewport.getChildAt(0) !== columns || searchGeneration.get() != generation) return@runOnUiThread
                    response.onSuccess { rows -> searchSnapshots[route] = rows; display(rows) }
                        .onFailure { status.text = "${if (adapter.itemCount > 0) "Refresh failed; previous results retained. " else "Search failed. "}${failureText(it)}" }
                }
            }
        }
        query.setOnEditorActionListener { _, _, _ -> search.performClick(); true }
        body.addView(search, QuasarDesign.match(top = dp(8)))
        body.addView(status, QuasarDesign.match(top = dp(8)))
        body.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        viewport.addView(columns, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this).apply { visibility = View.GONE }
        searchSnapshots[route]?.let(::display) ?: if (state.getBoolean("searched")) { restoringSearch = true; search.performClick(); restoringSearch = false } else Unit
        if (detail != null) selectedDocument?.let { loadDocument(detail, it) }
    }

    private fun showFieldOps() {
        enterRoute("field")
        viewport.removeAllViews()
        lateinit var field: FieldOpsView
        lateinit var fieldProgress: ProgressBar
        field = FieldOpsView(this) { query -> loadGeoDocuments(field, fieldProgress, query) }
        viewport.addView(
            field,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fieldProgress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        progress = fieldProgress
        viewport.addView(
            fieldProgress,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(10)
            },
        )
        workspace.views.getBundle("field")?.getBundle("field")?.let(field::restoreWorkspace)
        if (config.isConfigured()) loadGeoDocuments(field, fieldProgress, field.query())
        else field.setDataStatus("LOCAL / NO SERVER", ready = false)
    }

    private fun loadGeoDocuments(field: FieldOpsView, fieldProgress: ProgressBar, query: String) {
        val requestId = mapGeneration.incrementAndGet()
        field.setDataStatus("SYNCING", ready = false)
        fieldProgress.visibility = View.VISIBLE
        Thread({
            val result = runCatching { client.search(query.ifBlank { "*" }, limit = 100).let { rows -> rows.length() to GeoDocumentParser.parseSearchRows(JSONArray().apply { for (i in 0 until minOf(rows.length(), 100)) put(rows.opt(i)) }) } }
            runOnUiThread {
                // The field view is replaced on navigation and belongs to this activity instance.
                if (isFinishing || isDestroyed || viewport.getChildAt(0) !== field || mapGeneration.get() != requestId) return@runOnUiThread
                fieldProgress.visibility = View.GONE
                result.onSuccess { (recordCount, features) ->
                    field.setFeatures(features)
                    field.setDataStatus("${features.size} GEO / $recordCount RECORDS", ready = true)
                    field.setSnapshotNotice("Snapshot ≤100 records · corpus total unknown · refine query for other records")
                }.onFailure { error ->
                    field.showDataError(error.message ?: "Star server is unavailable. Cached basemap tiles may still render.")
                }
            }
        }, "quasar-geo-search").start()
    }

    private fun showDocument(id: String) {
        enterRoute("document:$id")
        val body = screen("Document", "Record details", id)
        body.addView(QuasarDesign.action(this, "Back to results") { goBack() }, QuasarDesign.match(top = dp(8)))
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(content, QuasarDesign.match(top = dp(12)))
        loadDocument(content, id)
    }

    private fun loadDocument(content: LinearLayout, id: String) {
        content.tag = id
        content.removeAllViews()
        content.addView(QuasarDesign.body(this, "Loading record…"))
        runApi({
            val root = client.document(id)
            val doc = root.optJSONObject("document") ?: root.optJSONObject("data")?.optJSONObject("document") ?: root.optJSONObject("data") ?: root
            val data = doc.optJSONObject("data") ?: doc
            Triple(doc, data, root.toString(2))
        }, onFailure = {
            if (content.tag == id) {
                content.removeAllViews()
                content.addView(emptyState("Record unavailable", failureText(it)))
                content.addView(QuasarDesign.action(this, "Retry read") { loadDocument(content, id) }, QuasarDesign.match(top = dp(8)))
            }
        }) { (doc, data, raw) ->
            if (content.tag != id) return@runApi
            content.removeAllViews()
            content.addView(QuasarDesign.eyebrow(this, doc.optString("dtype").ifBlank { "document" }))
            content.addView(QuasarDesign.title(this, data.optString("name").ifBlank { data.optString("title") }.ifBlank { id }, 22f), QuasarDesign.match(top = dp(8)))
            content.addView(QuasarDesign.body(this, id), QuasarDesign.match(top = dp(8)))
            val summary = data.optString("summary").ifBlank { data.optString("description") }.ifBlank { "No summary supplied in this record." }
            content.addView(QuasarDesign.body(this, summary, QuasarDesign.text, 16f), QuasarDesign.match(top = dp(12)))
            listOf("source", "dataset", "created_at", "updated_at", "observed_at").forEach { key ->
                val value = doc.opt(key) ?: data.opt(key)
                if (value != null && value != JSONObject.NULL) content.addView(QuasarDesign.body(this, "${key.replace('_', ' ')} · $value"), QuasarDesign.match(top = dp(8)))
            }
            val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
            content.addView(QuasarDesign.action(this, "Structured fields") {
                if (fields.childCount == 0) data.keys().asSequence().take(100).forEach { key ->
                    fields.addView(QuasarDesign.body(this, "$key\n${data.opt(key).toString().take(4096)}", QuasarDesign.text), QuasarDesign.match(top = dp(12)))
                }
                fields.visibility = if (fields.visibility == View.GONE) View.VISIBLE else View.GONE
            }, QuasarDesign.match(top = dp(16)))
            content.addView(fields)
            val rawView = codeBlock("").apply { visibility = View.GONE }
            content.addView(QuasarDesign.action(this, "Raw JSON") {
                rawView.text = raw
                rawView.visibility = if (rawView.visibility == View.GONE) View.VISIBLE else View.GONE
            }, QuasarDesign.match(top = dp(8)))
            content.addView(rawView, QuasarDesign.match(top = dp(8)))
        }
    }

    private fun showCreateDocument() {
        enterRoute("create")
        val body = screen("Mutation", "Add record", "The Star server remains the schema authority for connected writes.")
        val editor = QuasarDesign.field(this, "{\n  \"dtype\": \"document\",\n  \"data\": {}\n}", lines = 14)
        val status = QuasarDesign.body(this, "Ready for server validation.")
        body.addView(editor, QuasarDesign.match(top = dp(18)))
        addSubmission(body, "Create document", status, validate = {
            runCatching { JSONObject(editor.text.toString()) }.onFailure { editor.error = "Enter one valid JSON object" }.isSuccess
        }) {
            val source = editor.text.toString()
            submitOnce(status) { client.createDocument(JSONObject(source)) }
        }
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showImport() {
        enterRoute("import")
        val body = screen("Ingest", "Import batch", "Paste a bounded JSON array. The server validates every StarIntel document.")
        val editor = QuasarDesign.field(this, "[]", lines = 14)
        val status = QuasarDesign.body(this, "Nothing imported yet.")
        body.addView(editor, QuasarDesign.match(top = dp(18)))
        addSubmission(body, "Import documents", status, validate = {
            runCatching { JSONArray(editor.text.toString()) }.onFailure { editor.error = "Enter a JSON array of documents" }.isSuccess
        }) {
            val source = editor.text.toString()
            submitOnce(status) { client.bulkCreate(JSONArray(source)) }
        }
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showTarget(initialActor: String = "") {
        enterRoute("target:$initialActor")
        val body = screen("Orchestration", "Dispatch target", "Create one idempotent actor request against a named dataset.")
        val actor = labeledField(body, "ACTOR", initialActor)
        val target = labeledField(body, "TARGET", "")
        val dataset = labeledField(body, "DATASET", "investigation")
        val status = QuasarDesign.body(this, "Ready.")
        addSubmission(body, "Dispatch", status, validate = {
            listOf(actor, target, dataset).map { field ->
                field.text.isNotBlank().also { if (!it) field.error = "Required" }
            }.all { it }
        }) {
            val actorValue = actor.text.toString()
            val targetValue = target.text.toString()
            val datasetValue = dataset.text.toString()
            submitOnce(status, acceptanceOnly = true) { client.createTarget(actorValue, targetValue, datasetValue) }
        }
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showAgent() {
        enterRoute("agent")
        val body = screen(
            "Prolog-RLM",
            "Reasoning console",
            "Prolog owns budgets, capabilities, traces, and execution semantics. The phone receives a final answer plus reviewable typed operations.",
        )
        val policy = QuasarDesign.card(this, QuasarDesign.pink).apply {
            addView(sectionHeader("AUTHORITY", "APPROVE DIFF"))
            addView(QuasarDesign.body(this@MainActivity, "Depth 1 · 12 tool calls · 30 second wall limit · read-only capabilities by default"), QuasarDesign.match(top = dp(9)))
        }
        body.addView(policy, QuasarDesign.match(top = dp(18)))
        val prompt = QuasarDesign.field(this, lines = 7, hint = "Ask the agent about the current investigation…")
        body.addView(prompt, QuasarDesign.match(top = dp(12)))
        val output = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(QuasarDesign.action(this, "Run bounded turn", primary = true) {
            val promptValue = prompt.text.toString()
            runApi({ client.prologRlmTurn(AgentTurnRequest(promptValue)) }) { result ->
                output.removeAllViews()
                output.addView(QuasarDesign.eyebrow(this, result.status, statusColor(result.status)))
                output.addView(QuasarDesign.body(this, result.answer.ifBlank { "No final answer returned." }, QuasarDesign.text, 16f), QuasarDesign.match(top = dp(8)))
                if (result.traceId.isNotBlank()) {
                    output.addView(QuasarDesign.body(this, "Trace ${result.traceId}", QuasarDesign.muted, 11f), QuasarDesign.match(top = dp(8)))
                }
                result.operations.forEach { operation ->
                    val card = QuasarDesign.card(this, QuasarDesign.amber).apply {
                        addView(sectionHeader(operation.name, operation.authority))
                        addView(codeBlock(operation.arguments.toString(2)), QuasarDesign.match(top = dp(8)))
                    }
                    output.addView(card, QuasarDesign.match(top = dp(10)))
                }
            }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(output, QuasarDesign.match(top = dp(18)))
    }

    private fun showActors() {
        enterRoute("actors")
        val body = screen("StarIntel // Fleet", "Actors", "Server actors remain private. This view shows the registry your account can read; execution is authorized on the server.")
        val remote = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(QuasarDesign.eyebrow(this, "Server fleet"), QuasarDesign.match(top = dp(18)))
        body.addView(remote, QuasarDesign.match(top = dp(8)))
        if (config.isConfigured()) {
            remote.addView(QuasarDesign.body(this, "Loading authorized registry…"))
            runApi({ client.actors() }, onFailure = {
                remote.removeAllViews()
                remote.addView(emptyState("Registry unavailable", "Check your connection and actors:read permission, then refresh."))
            }) { rows ->
                remote.removeAllViews()
                for (index in 0 until rows.length()) {
                    val actor = rows.optJSONObject(index)?.let { RemoteActor.fromJson(it) } ?: continue
                    remote.addView(QuasarDesign.card(this).apply {
                        addView(sectionHeader(actor.name, actor.status))
                        addView(QuasarDesign.action(this@MainActivity, "Select ${actor.name}") { showTarget(actor.name) }, QuasarDesign.match(top = dp(8)))
                        addView(QuasarDesign.body(this@MainActivity, actor.uri, size = 12f), QuasarDesign.match(top = dp(8)))
                        addView(QuasarDesign.body(this@MainActivity, actor.accepts.joinToString(prefix = "Targets · ").ifBlank { "No target contract declared" }, size = 12f), QuasarDesign.match(top = dp(8)))
                    }, QuasarDesign.match(top = dp(8)))
                }
                if (remote.childCount == 0) remote.addView(emptyState("No visible actors", "Your server has no operator-visible actor deployments."))
            }
        } else remote.addView(emptyState("Connect your server", "Authenticate in Settings to load the private fleet."))
        body.addView(QuasarDesign.action(this, "Refresh actors") { showActors() }, QuasarDesign.match(top = dp(8)))
        body.addView(QuasarDesign.action(this, "Dispatch a target") { showTarget() }, QuasarDesign.match(top = dp(8)))
        body.addView(QuasarDesign.eyebrow(this, "On-device tools"), QuasarDesign.match(top = dp(24)))
        val lisp = localRuntime.lispStatus()
        val tek9 = localRuntime.tek9Status()
        val status = QuasarDesign.card(this, if (lisp.available && tek9.available) QuasarDesign.lime else QuasarDesign.amber).apply {
            addView(sectionHeader("RUNTIME", if (lisp.available && tek9.available) "READY" else "PARTIAL"))
            addView(runtimeLine("COMMON LISP", lisp.implementation, lisp.available), QuasarDesign.match(top = dp(12)))
            addView(runtimeLine("TEK9", tek9.detail, tek9.available), QuasarDesign.match(top = dp(9)))
            addView(runtimeLine("MAILBOXES", "Bounded + serial per actor", true), QuasarDesign.match(top = dp(9)))
        }
        body.addView(status, QuasarDesign.match(top = dp(18)))
        body.addView(QuasarDesign.eyebrow(this, "Definitions"), QuasarDesign.match(top = dp(26)))
        runCatching { actorDefinitions.manifests() }
            .onSuccess { manifests ->
                manifests.forEach { manifest ->
                    val card = QuasarDesign.card(this).apply {
                        addView(sectionHeader(manifest.name, "v${manifest.version}"))
                        addView(QuasarDesign.body(this@MainActivity, manifest.description), QuasarDesign.match(top = dp(8)))
                        addView(QuasarDesign.body(this@MainActivity, manifest.accepts.joinToString(prefix = "Accepts · ").ifBlank { "Accepts · any dtype" }, QuasarDesign.cyan, 11f), QuasarDesign.match(top = dp(10)))
                        addView(QuasarDesign.body(this@MainActivity, manifest.capabilities.joinToString { it.wireName }, QuasarDesign.muted, 11f), QuasarDesign.match(top = dp(5)))
                    }
                    body.addView(card, QuasarDesign.match(top = dp(8)))
                }
            }
            .onFailure { body.addView(emptyState("Manifest error", it.message ?: "Could not load actors"), QuasarDesign.match(top = dp(8))) }

        body.addView(QuasarDesign.action(this, "Define local actor") { showActorEditor() }, QuasarDesign.match(top = dp(14)))
    }

    private fun showActorEditor() {
        enterRoute("actor-editor")
        val body = screen("Actor config", "Define local actor", "The manifest describes input types and capabilities. Executable Lisp must already exist in the trusted mobile image.")
        val template = """
            {
              "id": "local.my-actor",
              "name": "My actor",
              "version": "1",
              "description": "Explain its bounded local job.",
              "entrypoint": "MY.ACTORS:HANDLE",
              "accepts": ["person"],
              "capabilities": ["document.read"],
              "default_config": {}
            }
        """.trimIndent()
        val editor = QuasarDesign.field(this, template, lines = 17)
        val status = QuasarDesign.body(this, "Manifest not saved.")
        body.addView(editor, QuasarDesign.match(top = dp(18)))
        body.addView(QuasarDesign.action(this, "Validate and save", primary = true) {
            runCatching { actorDefinitions.saveCustom(editor.text.toString()) }
                .onSuccess { status.text = "Saved ${it.id}. Restarting the trusted Lisp image is required before first execution." }
                .onFailure { status.text = it.message ?: "Manifest validation failed" }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showSettings() {
        enterRoute("settings")
        val body = screen("Connection", "Settings", "Credentials are validated before replacing the working Keystore-encrypted key.")
        val connection = QuasarDesign.body(this, connectionText()).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(connection, QuasarDesign.match(top = dp(12)))
        body.addView(QuasarDesign.action(this, "Check connection") {
            runApi({
                val health = runCatching { client.health() }
                val auth = runCatching { client.authContext(config.serverUrl(), config.apiKey().orEmpty()) }
                health to auth
            }) { (health, auth) ->
                serverStatus = if (health.isSuccess) "Responded to health check" else "Health check failed"
                authStatus = if (auth.isSuccess) "Validated now" else failureText(auth.exceptionOrNull()!!)
                connection.text = connectionText()
            }
        }, QuasarDesign.match(top = dp(8)))
        val server = labeledField(body, "STAR SERVER", config.serverUrl().ifBlank { "https://starintel.example" })
        val username = labeledField(body, "USERNAME", "", "username")
        val password = labeledField(body, "PASSWORD", "", "password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val status = QuasarDesign.body(this, if (config.isConfigured()) "Encrypted credential available." else "Not configured.")
        body.addView(QuasarDesign.action(this, "Login", primary = true) {
            val origin = server.text.toString()
            val name = username.text.toString()
            val presentedPassword = password.text.toString()
            runApi({
                val login = client.login(origin, name, presentedPassword)
                client.authContext(origin, login.apiKey)
                login
            }) { login ->
                runCatching { config.save(origin, login.apiKey) }
                    .onSuccess {
                        password.text.clear()
                        status.text = "API key authenticated${login.username.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                    }
                    .onFailure { status.text = it.message ?: "Credential save failed" }
            }
        }, QuasarDesign.match(top = dp(14)))

        body.addView(QuasarDesign.eyebrow(this, "Or use an API key"), QuasarDesign.match(top = dp(24)))
        val key = QuasarDesign.field(this, hint = "star_sk_v1_…").apply {
            contentDescription = "API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        body.addView(key, QuasarDesign.match(top = dp(8)))
        body.addView(QuasarDesign.action(this, "Authenticate key") {
            val origin = server.text.toString()
            val presented = key.text.toString().trim()
            runApi({ client.authContext(origin, presented) }) {
                runCatching { config.save(origin, presented) }
                    .onSuccess { key.text.clear(); status.text = "API key authenticated · saved securely" }
                    .onFailure { status.text = it.message ?: "Could not save API key" }
            }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(status, QuasarDesign.match(top = dp(12)))
    }

    private fun screen(eyebrow: String, title: String, description: String): LinearLayout {
        viewport.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(QuasarDesign.eyebrow(this@MainActivity, eyebrow))
            addView(QuasarDesign.title(this@MainActivity, title), QuasarDesign.match(top = dp(5)))
        }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(header, QuasarDesign.match())
        body.addView(QuasarDesign.body(this, description), QuasarDesign.match(top = dp(8)))

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        viewport.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        viewport.addView(
            progress,
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) },
        )
        return body
    }

    private fun bottomNavigation(): View {
        navigation = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp >= 600) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(9))
            setBackgroundColor(QuasarDesign.panel)
        }
        listOf(
            "Home" to ::showHome,
            "Field" to ::showFieldOps,
            "Actors" to ::showActors,
            "Search" to { showSearch("Documents", "*") },
            "Settings" to ::showSettings,
        ).forEach { (label, action) ->
            navigation.addView(TextView(this).apply {
                text = label
                tag = label
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(13), dp(2), dp(13))
                minHeight = dp(48)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectNavigation(label)
                    action()
                }
            }, if (resources.configuration.screenWidthDp >= 600) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        selectNavigation("Home")
        return navigation
    }

    private fun selectNavigation(selected: String) {
        for (index in 0 until navigation.childCount) {
            val item = navigation.getChildAt(index) as TextView
            val active = item.tag == selected
            item.setTextColor(if (active) QuasarDesign.cyan else QuasarDesign.muted)
            item.isSelected = active
            item.contentDescription = "${item.tag}${if (active) ", selected" else ""}"
            item.setBackgroundColor(if (active) QuasarDesign.panelRaised else QuasarDesign.panel)
        }
    }

    private fun connectionText(): String {
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val network = if (manager.activeNetwork == null) "No active network" else "Network available; server not implied"
        return "Network · $network\nServer · $serverStatus\nAuthentication · $authStatus\nSynchronization · $syncStatus"
    }

    private fun failureText(error: Throwable): String = when ((error as? actor.starintel.android.api.StarHttpFailure)?.status) {
        401 -> "Authentication expired or rejected. Authenticate in Settings."
        403 -> "Permission denied. Check account scopes in Settings."
        404, 405 -> "This operation is unavailable on this server."
        else -> if (error is IllegalArgumentException) error.message ?: "Check the input."
            else "Connection or server failure. Check Settings, then retry the read."
    }

    private fun addSubmission(body: LinearLayout, label: String, status: TextView, validate: () -> Boolean, submit: () -> Unit) {
        val state = workspace.views.getBundle(workspace.route) ?: Bundle().also { workspace.views.putBundle(workspace.route, it) }
        val button = QuasarDesign.action(this, label, primary = true) {
            if (!state.getBoolean("attempted") && validate()) {
                state.putBoolean("attempted", true)
                submit()
            }
        }
        button.isEnabled = !state.getBoolean("attempted")
        if (!button.isEnabled) status.text = state.getString("receipt") ?: "Submission attempted. Outcome unknown; inspect server records before creating another request."
        body.addView(button, QuasarDesign.match(top = dp(12)))
        body.addView(QuasarDesign.action(this, "Start a new submission") {
            android.app.AlertDialog.Builder(this).setTitle("Create another request?")
                .setMessage("An earlier request may have been accepted. Check server records first; a new submission can create another mutation.")
                .setNegativeButton("Keep current", null)
                .setPositiveButton("New submission") { _, _ ->
                    state.remove("attempted"); state.remove("receipt"); button.isEnabled = true; status.text = "Ready for a new submission."
                }.show()
        }, QuasarDesign.match(top = dp(8)))
    }

    private fun submitOnce(status: TextView, acceptanceOnly: Boolean = false, operation: () -> JSONObject) {
        val state = workspace.views.getBundle(workspace.route)!!
        status.text = "Submitting once…"
        syncStatus = "Submission in progress"
        runApi(operation, onFailure = {
            syncStatus = "Submission outcome unknown"
            val message = "${failureText(it)} Submission not retried. Inspect server records before starting another."
            state.putString("receipt", message)
            status.text = message
        }) { receipt ->
            syncStatus = "Server responded; no background sync"
            val message = if (acceptanceOnly) "Accepted by server. Execution and result status are not tracked here.\n${receipt.toString(2)}"
                else "Server response\n${receipt.toString(2)}"
            state.putString("receipt", message.take(8192))
            status.text = message
        }
    }

    private fun metric(label: String, value: String, accent: Int) = QuasarDesign.card(this, QuasarDesign.border).apply {
        setPadding(dp(12), dp(13), dp(12), dp(13))
        addView(QuasarDesign.eyebrow(this@MainActivity, label, accent))
        addView(QuasarDesign.title(this@MainActivity, value, 22f).apply { tag = VALUE_TAG }, QuasarDesign.match(top = dp(5)))
    }

    private fun sectionHeader(label: String, state: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(QuasarDesign.eyebrow(this@MainActivity, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(QuasarDesign.pill(this@MainActivity, state, statusColor(state)))
    }

    private fun runtimeLine(label: String, detail: String, ready: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(QuasarDesign.eyebrow(this@MainActivity, label, if (ready) QuasarDesign.lime else QuasarDesign.amber))
        addView(QuasarDesign.body(this@MainActivity, detail, QuasarDesign.text, 13f), QuasarDesign.match(top = dp(3)))
    }

    private fun labeledField(
        body: LinearLayout,
        label: String,
        value: String,
        hint: String = "",
    ): EditText {
        body.addView(QuasarDesign.eyebrow(this, label), QuasarDesign.match(top = dp(18)))
        return QuasarDesign.field(this, value, hint = hint).also {
            it.contentDescription = label
            body.addView(it, QuasarDesign.match(top = dp(7)))
        }
    }

    private fun codeBlock(value: String) = QuasarDesign.body(this, value, QuasarDesign.text, 12f).apply {
        typeface = Typeface.MONOSPACE
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(QuasarDesign.panelRaised)
    }

    private fun emptyState(title: String, detail: String) = QuasarDesign.card(this).apply {
        addView(QuasarDesign.title(this@MainActivity, title, 18f))
        addView(QuasarDesign.body(this@MainActivity, detail), QuasarDesign.match(top = dp(7)))
    }

    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginStart = start
    }

    private fun statusColor(status: String): Int = when (status.lowercase()) {
        "ok", "completed", "success" -> QuasarDesign.lime
        "blocked", "denied", "error", "failed" -> QuasarDesign.coral
        else -> QuasarDesign.amber
    }

    private fun <T> runApi(
        operation: () -> T,
        onFailure: (Throwable) -> Unit = { Toast.makeText(this, "Request failed. Check connection, permissions, and server capability.", Toast.LENGTH_LONG).show() },
        result: (T) -> Unit,
    ) {
        val requestedProgress = progress
        val generation = viewport.getChildAt(0)
        generation?.let { root -> descendants(root).filter { it.isClickable && it !is EditText }.forEach { it.isEnabled = false } }
        requestedProgress.visibility = View.VISIBLE
        Thread {
            val value = runCatching(operation)
            runOnUiThread {
                if (isFinishing || isDestroyed || viewport.getChildAt(0) !== generation) return@runOnUiThread
                requestedProgress.visibility = View.GONE
                generation?.let { root -> descendants(root).filter { it.isClickable && it !is EditText }.forEach { it.isEnabled = true } }
                value.onSuccess(result).onFailure(onFailure)
                if (workspace.views.getBundle(workspace.route)?.getBoolean("attempted") == true) {
                    generation?.let { root -> descendants(root).filterIsInstance<TextView>().filter { it.text.toString() in listOf("Dispatch", "Import documents", "Create document") }.forEach { it.isEnabled = false } }
                }
            }
        }.start()
    }

    private data class Route(
        val label: String,
        val detail: String,
        val accent: Int,
        val action: () -> Unit,
    )

    companion object {
        private const val VALUE_TAG = "metric-value"
    }
}

private fun Long.compact(): String = when {
    this >= 1_000_000_000 -> String.format("%.1fB", this / 1_000_000_000.0)
    this >= 1_000_000 -> String.format("%.1fM", this / 1_000_000.0)
    this >= 1_000 -> String.format("%.1fK", this / 1_000.0)
    else -> toString()
}
