package actor.starintel.quasar

import actor.starintel.android.model.AgentTurnRequest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(QuasarDesign.background)
        }
        viewport = FrameLayout(this)
        shell.addView(
            viewport,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        shell.addView(bottomNavigation(), QuasarDesign.match())
        setContentView(shell)
        showHome()
    }

    override fun onDestroy() {
        if (::viewport.isInitialized) runCatching { localRuntime.close() }
        super.onDestroy()
    }

    private fun showHome() {
        val body = screen(
            eyebrow = "Local-first intelligence",
            title = "Command deck",
            description = "Investigate locally, project the truth, and sync only through typed StarIntel operations.",
        )

        val lisp = localRuntime.lispStatus()
        val runtimeCard = QuasarDesign.card(this, if (lisp.available) QuasarDesign.lime else QuasarDesign.amber)
        runtimeCard.addView(sectionHeader("LOCAL CORE", if (lisp.available) "READY" else "BOOTSTRAP"))
        runtimeCard.addView(QuasarDesign.title(this, "Common Lisp → Tek9 → actors", 20f), QuasarDesign.match(top = dp(12)))
        runtimeCard.addView(
            QuasarDesign.body(
                this,
                if (lisp.available) {
                    "ECL is embedded for this ABI. Actor mailboxes can commit documents, relations, facts, and outbox intents atomically."
                } else {
                    "The Android library and closed ECL/Tek9 protocol are installed. This APK still needs the ABI-specific native ECL bridge."
                },
            ),
            QuasarDesign.match(top = dp(8)),
        )
        body.addView(runtimeCard, QuasarDesign.match(top = dp(20)))

        val metricRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val documents = metric("DOCUMENTS", "—", QuasarDesign.cyan)
        val targets = metric("TARGETS", "—", QuasarDesign.pink)
        val server = metric("SERVER", if (config.isConfigured()) "SYNC" else "OFF", QuasarDesign.amber)
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
                server.findViewWithTag<TextView>(VALUE_TAG).text = "LIVE"
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
            Route("Flow studio", "Build and run bounded Morrison-style workflows", QuasarDesign.cyan) {
                startActivity(Intent(this, actor.starintel.quasar.workflows.FlowStudioActivity::class.java))
            },
            Route("Logic studio", "Edit private Lisp, Prolog, and expert nodes", QuasarDesign.lime) {
                startActivity(Intent(this, actor.starintel.quasar.ide.LogicStudioActivity::class.java))
            },
            Route("Reasoning agent", "Run a bounded Prolog-RLM investigation turn", QuasarDesign.pink) { showAgent() },
            Route("Actor mesh", "Inspect local manifests and actor capabilities", QuasarDesign.amber) { showActors() },
        )
        routes.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { index, route ->
                row.addView(
                    QuasarDesign.routeCard(this, route.label, route.detail, route.accent, route.action),
                    weighted(start = if (index == 0) 0 else dp(8)),
                )
            }
            if (pair.size == 1) row.addView(View(this), weighted(start = dp(8)))
            body.addView(row, QuasarDesign.match(top = dp(8)))
        }
    }

    private fun showSearch(title: String, initial: String) {
        val body = screen("Corpus", title, "Search the canonical server projection. Results remain honest about capability and connectivity.")
        val query = QuasarDesign.field(this, initial, hint = "Query documents")
        body.addView(query, QuasarDesign.match(top = dp(18)))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(QuasarDesign.action(this, "Search", primary = true) {
            results.removeAllViews()
            runApi({ client.search(query.text.toString()) }) { rows -> renderSearchResults(results, rows) }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(results, QuasarDesign.match(top = dp(12)))
    }

    private fun showFieldOps() {
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
        if (config.isConfigured()) loadGeoDocuments(field, fieldProgress, "")
        else field.setDataStatus("LOCAL / NO SERVER", ready = false)
    }

    private fun loadGeoDocuments(field: FieldOpsView, fieldProgress: ProgressBar, query: String) {
        field.setDataStatus("SYNCING", ready = false)
        fieldProgress.visibility = View.VISIBLE
        Thread {
            val result = runCatching { client.search(query.ifBlank { "*" }, limit = 200) }
            runOnUiThread {
                fieldProgress.visibility = View.GONE
                result.onSuccess { rows ->
                    val features = GeoDocumentParser.parseSearchRows(rows)
                    field.setFeatures(features)
                    field.setDataStatus("${features.size} SYNCED", ready = true)
                }.onFailure { error ->
                    field.showDataError(error.message ?: "Star server is unavailable. Cached basemap tiles may still render.")
                }
            }
        }.start()
    }

    private fun renderSearchResults(container: LinearLayout, rows: JSONArray) {
        if (rows.length() == 0) {
            container.addView(emptyState("No matching documents", "Try a dtype, identifier, name, or a broader term."))
            return
        }
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val id = resultId(row)
            val document = resultDocument(row)
            val label = document.optString("name")
                .ifBlank { document.optString("title") }
                .ifBlank { id }
            val card = QuasarDesign.card(this).apply {
                isClickable = true
                isFocusable = true
                addView(QuasarDesign.eyebrow(this@MainActivity, document.optString("dtype").ifBlank { "document" }))
                addView(QuasarDesign.title(this@MainActivity, label, 17f), QuasarDesign.match(top = dp(7)))
                addView(QuasarDesign.body(this@MainActivity, id, QuasarDesign.muted, 11f), QuasarDesign.match(top = dp(5)))
                setOnClickListener { showDocument(id) }
            }
            container.addView(card, QuasarDesign.match(top = dp(8)))
        }
    }

    private fun showDocument(id: String) {
        val body = screen("Document", id, "Canonical server record")
        val output = codeBlock("Loading…")
        body.addView(output, QuasarDesign.match(top = dp(18)))
        runApi({ client.document(id).toString(2) }) { output.text = it }
    }

    private fun showCreateDocument() {
        val body = screen("Mutation", "Add record", "The Star server remains the schema authority for connected writes.")
        val editor = QuasarDesign.field(this, "{\n  \"dtype\": \"document\",\n  \"data\": {}\n}", lines = 14)
        val status = QuasarDesign.body(this, "Ready for server validation.")
        body.addView(editor, QuasarDesign.match(top = dp(18)))
        body.addView(QuasarDesign.action(this, "Create document", primary = true) {
            runApi({ client.createDocument(JSONObject(editor.text.toString())).toString(2) }) { status.text = it }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showImport() {
        val body = screen("Ingest", "Import batch", "Paste a bounded JSON array. The server validates every StarIntel document.")
        val editor = QuasarDesign.field(this, "[]", lines = 14)
        val status = QuasarDesign.body(this, "Nothing imported yet.")
        body.addView(editor, QuasarDesign.match(top = dp(18)))
        body.addView(QuasarDesign.action(this, "Import documents", primary = true) {
            runApi({ client.bulkCreate(JSONArray(editor.text.toString())).toString(2) }) { status.text = it }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showTarget() {
        val body = screen("Orchestration", "Dispatch target", "Create one idempotent actor request against a named dataset.")
        val actor = labeledField(body, "ACTOR", "user-hunt")
        val target = labeledField(body, "TARGET", "")
        val dataset = labeledField(body, "DATASET", "investigation")
        val status = QuasarDesign.body(this, "Ready.")
        body.addView(QuasarDesign.action(this, "Dispatch", primary = true) {
            runApi({ client.createTarget(actor.text.toString(), target.text.toString(), dataset.text.toString()).toString(2) }) {
                status.text = it
            }
        }, QuasarDesign.match(top = dp(14)))
        body.addView(status, QuasarDesign.match(top = dp(10)))
    }

    private fun showAgent() {
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
            runApi({ client.prologRlmTurn(AgentTurnRequest(prompt.text.toString())) }) { result ->
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
        val body = screen("Local runtime", "Actor mesh", "Every actor is manifest-driven, serialized through a bounded mailbox, and restricted to declared effects.")
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
        val body = screen("Connection", "Settings", "Credentials are validated before replacing the working Keystore-encrypted key.")
        val server = labeledField(body, "STAR SERVER", config.serverUrl().ifBlank { "https://starintel.example" })
        val username = labeledField(body, "USERNAME", "", "username")
        val password = labeledField(body, "PASSWORD", "", "password").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val status = QuasarDesign.body(this, if (config.isConfigured()) "Encrypted credential available." else "Not configured.")
        body.addView(QuasarDesign.action(this, "Login", primary = true) {
            runApi({
                val login = client.login(server.text.toString(), username.text.toString(), password.text.toString())
                client.authContext(server.text.toString(), login.apiKey)
                login
            }) { login ->
                runCatching { config.save(server.text.toString(), login.apiKey) }
                    .onSuccess {
                        password.text.clear()
                        status.text = "Connected${login.username.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                    }
                    .onFailure { status.text = it.message ?: "Credential save failed" }
            }
        }, QuasarDesign.match(top = dp(14)))

        body.addView(QuasarDesign.eyebrow(this, "Or use an API key"), QuasarDesign.match(top = dp(24)))
        val key = QuasarDesign.field(this, hint = "star_sk_v1_…").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        body.addView(key, QuasarDesign.match(top = dp(8)))
        body.addView(QuasarDesign.action(this, "Authenticate key") {
            val presented = key.text.toString().trim()
            runApi({ client.authContext(server.text.toString(), presented) }) {
                runCatching { config.save(server.text.toString(), presented) }
                    .onSuccess { key.text.clear(); status.text = "Connected · API key authenticated" }
                    .onFailure { status.text = it.message ?: "Could not save API key" }
            }
        }, QuasarDesign.match(top = dp(10)))
        body.addView(status, QuasarDesign.match(top = dp(12)))
    }

    private fun screen(eyebrow: String, title: String, description: String): LinearLayout {
        viewport.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(40))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(QuasarDesign.eyebrow(this@MainActivity, eyebrow))
            addView(QuasarDesign.title(this@MainActivity, title), QuasarDesign.match(top = dp(5)))
        }
        header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            QuasarDesign.pill(this, if (config.isConfigured()) "CONNECTED" else "LOCAL", if (config.isConfigured()) QuasarDesign.lime else QuasarDesign.amber),
        )
        body.addView(header, QuasarDesign.match())
        body.addView(QuasarDesign.body(this, description), QuasarDesign.match(top = dp(9)))

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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(9))
            setBackgroundColor(QuasarDesign.panel)
        }
        listOf(
            "Home" to ::showHome,
            "Field" to ::showFieldOps,
            "Flows" to { startActivity(Intent(this, actor.starintel.quasar.workflows.FlowStudioActivity::class.java)) },
            "Logic" to { startActivity(Intent(this, actor.starintel.quasar.ide.LogicStudioActivity::class.java)) },
            "Settings" to ::showSettings,
        ).forEach { (label, action) ->
            navigation.addView(TextView(this).apply {
                text = label
                tag = label
                textSize = 10.5f
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
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        selectNavigation("Home")
        return navigation
    }

    private fun selectNavigation(selected: String) {
        for (index in 0 until navigation.childCount) {
            val item = navigation.getChildAt(index) as TextView
            val active = item.tag == selected
            item.setTextColor(if (active) QuasarDesign.cyan else QuasarDesign.muted)
            item.alpha = if (active) 1f else .82f
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
        return QuasarDesign.field(this, value, hint = hint).also { body.addView(it, QuasarDesign.match(top = dp(7))) }
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

    private fun <T> runApi(operation: () -> T, result: (T) -> Unit) {
        progress.visibility = View.VISIBLE
        Thread {
            val value = runCatching(operation)
            runOnUiThread {
                progress.visibility = View.GONE
                value.onSuccess(result).onFailure { error ->
                    Toast.makeText(this, error.message ?: "Request failed", Toast.LENGTH_LONG).show()
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
