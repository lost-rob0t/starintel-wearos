package actor.starintel.quasar.ide

import actor.starintel.quasar.DraftReference
import actor.starintel.quasar.WorkspaceDrafts
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class LogicStudioActivity : Activity() {
    private lateinit var workspace: PrivateWorkspaceStore
    private lateinit var expertStore: ExpertDefinitionStore
    private lateinit var content: LinearLayout
    private var language = IdeLanguage.LISP
    private val drafts = linkedMapOf<String, SourceBuffer>()
    private var activeName: String? = null
    private var editor: EditText? = null
    private val draftStore by lazy { WorkspaceDrafts.coordinator(applicationContext) }
    private val draftReferences = linkedMapOf<String, String>()
    private val handler = Handler(Looper.getMainLooper())
    private var editorChanged = false
    private var restoringDrafts = false
    private var draftStatus: TextView? = null
    private var restoredCursor = 0
    private var restoreFailures = emptySet<String>()
    private var restoreReviewRequired = false
    private var unreviewedRestore = false
    private val capturePending = Runnable { if (captureDraft()) flushDrafts() }
    private fun draftName(name: String, mode: IdeLanguage = language) = "logic:${mode.name}:$name"


    override fun onCreate(savedInstanceState: Bundle?) {
        QuasarDesign.configure(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background
        workspace = PrivateWorkspaceStore(File(filesDir, "logic-workspace"))
        expertStore = ExpertDefinitionStore(File(filesDir, "expert-definitions"))
        runCatching { workspace.seedDefaults() }
            .onFailure { Toast.makeText(this, it.safeMessage(), Toast.LENGTH_LONG).show() }
        renderShell()
        val restoredLanguage = savedInstanceState?.getString("language")?.let { runCatching { IdeLanguage.valueOf(it) }.getOrNull() } ?: IdeLanguage.LISP
        language = restoredLanguage
        activeName = savedInstanceState?.getString("activeName")
        restoredCursor = savedInstanceState?.getInt("cursor") ?: 0
        savedInstanceState?.getBundle("drafts")?.let { saved ->
            saved.keySet().take(32).forEach { name -> saved.getString(name)?.let { key ->
                draftReferences[if (name.startsWith("logic:")) name else draftName(name)] = key
            } }
        }
        unreviewedRestore = savedInstanceState?.getBoolean("draftReviewRequired") == true
        restoreReviewRequired = savedInstanceState?.getBoolean("draftsPending") == true
        restoreDrafts(restoreReviewRequired)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        captureDraft()
        outState.putString("language", language.name)
        outState.putString("activeName", activeName)
        outState.putInt("cursor", editor?.selectionStart ?: restoredCursor)
        outState.putBoolean("draftReviewRequired", unreviewedRestore)
        outState.putBoolean("draftsPending", restoreReviewRequired || editorChanged || draftReferences.keys.any { draftStore.isDirty(it) })
        outState.putBundle("drafts", Bundle().apply {
            draftReferences.forEach { (name, key) -> putString(name, key) }
        })
        flushDrafts()
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        captureDraft()
        flushDrafts()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(capturePending)
        super.onDestroy()
    }

    private fun restoreDrafts(pending: Boolean) {
        // Rotation uses process-local snapshots synchronously; process death uses worker reads.
        if (draftReferences.keys.all { draftStore.current(it) != null }) {
            if (unreviewedRestore) { restoringDrafts = true; showRestoreGate(); return }
            restoringDrafts = false
            restoreReviewRequired = false
            openLanguage(language)
            editor?.setSelection(restoredCursor.coerceIn(0, editor?.length() ?: 0))
            return
        }
        restoringDrafts = true
        content.removeAllViews()
        content.addView(QuasarDesign.body(this, "Restoring encrypted drafts…"))
        val weak = WeakReference(this)
        val results = draftReferences.map { (name, key) ->
            name to runCatching { draftStore.restoreInto(name, key) }
                .getOrElse { CompletableFuture<String>().apply { completeExceptionally(it) } }
        }
        CompletableFuture.allOf(*results.map { it.second }.toTypedArray()).whenComplete { _, _ ->
            weak.get()?.runOnUiThread {
                val activity = weak.get() ?: return@runOnUiThread
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                activity.restoreFailures = results.filter { it.second.isCompletedExceptionally }.map { it.first }.toSet()
                if (activity.restoreFailures.isNotEmpty() || pending) activity.showRestoreGate()
                else {
                    activity.restoringDrafts = false
                    activity.openLanguage(activity.language)
                    activity.editor?.setSelection(activity.restoredCursor.coerceIn(0, activity.editor?.length() ?: 0))
                }
            }
        }
    }

    private fun showRestoreGate() {
        unreviewedRestore = true
        content.removeAllViews()
        content.addView(QuasarDesign.body(this, if (restoreFailures.isEmpty())
            "Restored the last saved draft. A save was pending at shutdown; review this version before saving or using it."
            else "Saved draft could not be read. Editing and saving are blocked until you retry or explicitly discard the unavailable draft."))
        if (restoreFailures.isNotEmpty()) content.addView(QuasarDesign.action(this, "Retry draft restore") { restoreDrafts(true) })
        content.addView(QuasarDesign.action(this, if (restoreFailures.isEmpty()) "Review restored drafts" else "Discard unavailable drafts") {
            restoreFailures.forEach { draftReferences.remove(it); draftStore.discard(it) }
            restoreFailures = emptySet()
            restoreReviewRequired = false
            unreviewedRestore = false
            restoringDrafts = false
            openLanguage(language)
        })
    }

    private fun flushDrafts() {
        if (draftReferences.isEmpty()) return
        val weak = WeakReference(this)
        draftStore.flush().whenComplete { _, failure ->
            weak.get()?.runOnUiThread {
                val activity = weak.get() ?: return@runOnUiThread
                if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                activity.draftStatus?.text = if (failure != null) "Draft save failed; keep this app open and retry."
                    else if (activity.editorChanged || activity.draftReferences.keys.any { activity.draftStore.isDirty(it) }) "Draft save pending"
                    else "Draft saved privately"
            }
        }
    }

    private fun renderShell() {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(QuasarDesign.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(14))
            setBackgroundColor(QuasarDesign.panel)
            addView(QuasarDesign.eyebrow(this@LogicStudioActivity, "Private control plane"))
            addView(QuasarDesign.title(this@LogicStudioActivity, "Logic studio", 27f), QuasarDesign.match(top = dp(4)))
            addView(
                QuasarDesign.body(
                    this@LogicStudioActivity,
                    "Writable app-private sources · atomic saves · validation before runtime handoff",
                    QuasarDesign.muted,
                    12f,
                ),
                QuasarDesign.match(top = dp(6)),
            )
        }
        shell.addView(header, QuasarDesign.match())
        shell.addView(modeBar(), QuasarDesign.match())
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }
        shell.addView(
            ScrollView(this).apply { isFillViewport = true; addView(content) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        QuasarDesign.install(this, shell)
    }

    private fun modeBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(QuasarDesign.panelRaised)
        }
        listOf(
            "Lisp" to { openLanguage(IdeLanguage.LISP) },
            "Prolog" to { openLanguage(IdeLanguage.PROLOG) },
            "Experts" to ::showExperts,
        ).forEach { (label, action) ->
            row.addView(studioButton(label, action), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        return row
    }

    private fun openLanguage(nextLanguage: IdeLanguage) {
        if (restoringDrafts || !captureDraft()) return
        val previousName = activeName
        editor = null
        language = nextLanguage
        drafts.clear()
        val buffers = runCatching { workspace.list(language) }
            .onFailure { toast(it.safeMessage()) }
            .getOrDefault(emptyList())
        buffers.forEach { drafts[it.name] = it }
        draftReferences.keys.filter { it.startsWith("logic:${language.name}:") }.forEach { id ->
            draftStore.current(id)?.let { source ->
                val name = id.substringAfterLast(':')
                drafts[name] = SourceBuffer(name, language, source, dirty = true)
            }
        }
        activeName = previousName?.takeIf { it in drafts } ?: drafts.keys.firstOrNull()
        showEditor()
    }

    private fun showEditor() {
        handler.removeCallbacks(capturePending)
        editorChanged = false
        editor = null
        content.removeAllViews()
        content.addView(sectionLine(language.displayName, "APP PRIVATE"))
        content.addView(
            QuasarDesign.body(
                this,
                if (language == IdeLanguage.LISP) {
                    "init.lisp is the writable Common Lisp control-plane configuration. Source is never exported or logged."
                } else {
                    "Edit reviewable facts and rules. Validation is structural; execution belongs to the configured Prolog runtime."
                },
            ),
            QuasarDesign.match(top = dp(8)),
        )
        content.addView(tabStrip(), QuasarDesign.match(top = dp(14)))

        val source = activeName?.let(drafts::get)
        if (source == null) {
            content.addView(QuasarDesign.body(this, "No buffer. Create one to begin."), QuasarDesign.match(top = dp(18)))
            content.addView(QuasarDesign.action(this, "New buffer", primary = true, onClick = ::promptNewBuffer), QuasarDesign.match(top = dp(12)))
            return
        }

        editor = QuasarDesign.field(this, source.source, lines = 18).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setHorizontallyScrolling(true)
        }
        editor?.let { field ->
            val identity = draftName(source.name)
            field.filters = field.filters + InputFilter { insertion, start, end, destination, dstart, dend ->
                val length = destination.length.toLong() - (dend - dstart) + end - start
                if (length <= Int.MAX_VALUE && draftStore.canAcceptLength(identity, length.toInt())) null else {
                    field.error = "Open drafts exceed the storage memory limit; save or discard another draft"
                    destination.subSequence(dstart, dend)
                }
            }
            SourceEditorAnalysis(field, language, ::tokenColor,
                rejected = { toast("Source limit is 256 KiB; edit was not inserted") },
                changed = {
                    activeName?.let { name -> drafts[name]?.let { drafts[name] = it.copy(dirty = true) } }
                    editorChanged = true
                    draftStatus?.text = "Draft save pending"
                    handler.removeCallbacks(capturePending)
                    handler.postDelayed(capturePending, 300)
                },
            )
            content.addView(field, QuasarDesign.match(top = dp(12)))
        }

        draftStatus = QuasarDesign.body(this, "Drafts save privately after editing pauses", QuasarDesign.muted, 12f)
        content.addView(draftStatus, QuasarDesign.match(top = dp(8)))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(studioButton("Save") { saveActive() }, weighted())
        actions.addView(studioButton("Validate") { validateActive() }, weighted(dp(7)))
        actions.addView(studioButton("New") { promptNewBuffer() }, weighted(dp(7)))
        content.addView(actions, QuasarDesign.match(top = dp(10)))
        content.addView(searchPanel(), QuasarDesign.match(top = dp(16)))
        renderDiagnostics(LanguageRegistry.implementation(language).validate(source.source))
    }

    private fun tabStrip(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        drafts.values.forEach { buffer ->
            val selected = buffer.name == activeName
            row.addView(TextView(this).apply {
                text = if (buffer.dirty) "${buffer.name} •" else buffer.name
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(if (selected) QuasarDesign.background else QuasarDesign.text)
                setBackgroundColor(if (selected) QuasarDesign.cyan else QuasarDesign.panelRaised)
                setPadding(dp(13), dp(11), dp(13), dp(11))
                minHeight = dp(48)
                isFocusable = true
                isSelected = selected
                isClickable = true
                setOnClickListener {
                    if (!captureDraft()) return@setOnClickListener
                    activeName = buffer.name
                    showEditor()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun searchPanel(): View {
        val panel = QuasarDesign.card(this).apply { addView(QuasarDesign.eyebrow(this@LogicStudioActivity, "Search buffers")) }
        val query = QuasarDesign.field(this, hint = "Symbol, predicate, or literal")
        val output = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(query, QuasarDesign.match(top = dp(8)))
        panel.addView(QuasarDesign.action(this, "Find") {
            captureDraft()
            output.removeAllViews()
            val matches = SourceSearch.find(drafts.values.toList(), query.text.toString(), limit = 100)
            if (matches.isEmpty()) {
                output.addView(QuasarDesign.body(this, "No matches.", QuasarDesign.muted, 12f))
            } else {
                matches.forEach { match ->
                    output.addView(QuasarDesign.body(this, "${match.sourceName}:${match.line}:${match.column}", QuasarDesign.cyan, 12f))
                }
            }
        }, QuasarDesign.match(top = dp(8)))
        panel.addView(output, QuasarDesign.match(top = dp(8)))
        return panel
    }

    private fun saveActive() {
        if (restoringDrafts || !captureDraft()) return
        val name = activeName ?: return
        val buffer = drafts[name] ?: return
        runCatching { workspace.save(buffer.copy(dirty = false)) }
            .onSuccess {
                drafts[name] = buffer.copy(dirty = false)
                draftReferences.remove(draftName(name))
                draftStore.discard(draftName(name))
                toast("Saved $name atomically")
                showEditor()
            }
            .onFailure { toast(it.safeMessage()) }
    }

    private fun validateActive() {
        if (restoringDrafts || !captureDraft()) return
        val source = activeName?.let(drafts::get)?.source.orEmpty()
        val diagnostics = LanguageRegistry.implementation(language).validate(source)
        renderDiagnostics(diagnostics)
        toast(if (diagnostics.none { it.severity == DiagnosticSeverity.ERROR }) "Validation clean" else "Validation found blocking diagnostics")
    }

    private fun renderDiagnostics(diagnostics: List<Diagnostic>) {
        val existing = content.findViewWithTag<LinearLayout>(DIAGNOSTICS_TAG)
        if (existing != null) content.removeView(existing)
        val panel = QuasarDesign.card(this, if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) QuasarDesign.coral else QuasarDesign.lime).apply {
            tag = DIAGNOSTICS_TAG
            addView(sectionLine("Diagnostics", if (diagnostics.isEmpty()) "CLEAN" else diagnostics.size.toString()))
            if (diagnostics.isEmpty()) addView(QuasarDesign.body(this@LogicStudioActivity, "No structural diagnostics.", QuasarDesign.muted, 12f), QuasarDesign.match(top = dp(7)))
            diagnostics.take(25).forEach { diagnostic ->
                val color = if (diagnostic.severity == DiagnosticSeverity.ERROR) QuasarDesign.coral else QuasarDesign.amber
                addView(
                    QuasarDesign.body(this@LogicStudioActivity, "${diagnostic.line}:${diagnostic.column}  ${diagnostic.code}  ${diagnostic.message}", color, 12f),
                    QuasarDesign.match(top = dp(7)),
                )
            }
        }
        content.addView(panel, QuasarDesign.match(top = dp(14)))
    }

    private fun promptNewBuffer() {
        if (restoringDrafts || !captureDraft()) return
        val input = QuasarDesign.field(this, hint = if (language == IdeLanguage.LISP) "policy.lisp" else "rules.pl")
        AlertDialog.Builder(this)
            .setTitle("New ${language.displayName} buffer")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                val buffer = SourceBuffer(name, language, if (language == IdeLanguage.LISP) "; New private source\n" else "% New private source\n", dirty = true)
                runCatching {
                    if (!name.endsWith(".${language.extension}")) throw WorkspaceException("Use a .${language.extension} filename")
                    if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(name)) throw WorkspaceException("Invalid source name")
                    require(draftStore.canAcceptLength(draftName(name), buffer.source.length)) { "Draft limit reached" }
                    draftStore.update(draftName(name), buffer.source)
                    draftReferences[draftName(name)] = DraftReference.key(draftName(name))
                    drafts[name] = buffer
                    activeName = name
                    showEditor()
                }.onFailure { toast(it.safeMessage()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExperts() {
        if (restoringDrafts || !captureDraft()) return
        activeName = null
        editor = null
        content.removeAllViews()
        content.addView(sectionLine("Expert systems", "FBP READY"))
        content.addView(
            QuasarDesign.body(
                this,
                "Define schemas, evidence, rules, typed arguments, activation phrases, a bounded document query, and an alert contract. Saving emits a neutral starintel.expert node descriptor; workflow execution remains with FBP.",
            ),
            QuasarDesign.match(top = dp(8)),
        )
        runCatching { expertStore.list() }.getOrDefault(emptyList()).forEach { expert ->
            content.addView(QuasarDesign.card(this, QuasarDesign.border).apply {
                addView(sectionLine(expert.name, expert.id))
                addView(QuasarDesign.body(this@LogicStudioActivity, expert.description, QuasarDesign.muted, 12f), QuasarDesign.match(top = dp(7)))
                setOnClickListener { showExpertEditor(expert) }
                isClickable = true
            }, QuasarDesign.match(top = dp(8)))
        }
        content.addView(QuasarDesign.action(this, "New expert", primary = true) { showExpertEditor(null) }, QuasarDesign.match(top = dp(14)))
    }

    private fun showExpertEditor(existing: ExpertDefinition?) {
        content.removeAllViews()
        content.addView(sectionLine(existing?.name ?: "New expert", "EXPERT NODE"))
        val id = expertField("ID", existing?.id.orEmpty(), "geo-risk")
        val name = expertField("NAME", existing?.name.orEmpty(), "Geo risk")
        val description = expertField("DESCRIPTION", existing?.description.orEmpty(), "Explain the expert's bounded purpose", 3)
        val schemas = expertField("SCHEMAS · ONE PREDICATE PER LINE", existing?.schemas?.joinToString("\n").orEmpty(), "geo_document(id, latitude, longitude, confidence)", 4)
        val facts = expertField("FACTS · ONE PER LINE", existing?.facts?.joinToString("\n").orEmpty(), "minimum_confidence(0.8).", 5)
        val rules = expertField("RULES · ONE PER LINE", existing?.rules?.joinToString("\n").orEmpty(), "candidate(Id) :- geo_document(Id, _, _, C), C >= 0.8.", 7)
        val arguments = expertField("ARGUMENTS · NAME:TYPE:REQUIRED", existing?.arguments?.joinToString("\n") { "${it.name}:${it.type}:${it.required}" }.orEmpty(), "document_id:document-id:true", 4)
        val activation = expertField("ACTIVATION PHRASES", existing?.activation?.joinToString("\n").orEmpty(), "check geo risk", 3)
        val collection = expertField("QUERY COLLECTION", existing?.query?.collection.orEmpty(), "documents")
        val query = expertField("BOUNDED DOCUMENT QUERY", existing?.query?.expression.orEmpty(), "dtype:geo AND confidence:[0.8 TO *]", 3)
        val limit = expertField("QUERY LIMIT · 1–500", existing?.query?.limit?.toString() ?: "50", "50")
        val alertCondition = expertField("ALERT CONDITION · BLANK DISABLES", existing?.alert?.condition.orEmpty(), "candidate(DocumentId)")
        val alertSeverity = expertField("ALERT SEVERITY", existing?.alert?.severity ?: "medium", "info | low | medium | high | critical")
        val alertMessage = expertField("ALERT MESSAGE", existing?.alert?.message.orEmpty(), "High-confidence match", 2)
        val status = QuasarDesign.body(this, "Not validated.", QuasarDesign.muted, 12f)
        val preview = QuasarDesign.body(this, "", QuasarDesign.text, 11f).apply { typeface = Typeface.MONOSPACE }
        content.addView(QuasarDesign.action(this, "Validate, save, and build node", primary = true) {
            runCatching {
                val definition = ExpertDefinition(
                    id = id.text.toString().trim(),
                    name = name.text.toString().trim(),
                    description = description.text.toString().trim(),
                    schemas = schemas.nonBlankLines(),
                    facts = facts.nonBlankLines(),
                    rules = rules.nonBlankLines(),
                    arguments = arguments.nonBlankLines().map { value ->
                        val parts = value.split(':', limit = 3)
                        if (parts.size != 3) throw WorkspaceException("Arguments use name:type:required")
                        val required = when (parts[2].trim()) {
                            "true" -> true
                            "false" -> false
                            else -> throw WorkspaceException("Argument required flag must be true or false")
                        }
                        ExpertArgument(parts[0].trim(), parts[1].trim(), required)
                    },
                    activation = activation.nonBlankLines(),
                    query = ExpertQuery(
                        collection.text.toString().trim(),
                        query.text.toString().trim(),
                        limit.text.toString().toIntOrNull() ?: throw WorkspaceException("Query limit must be a number"),
                    ),
                    alert = ExpertAlert(
                        enabled = alertCondition.text.isNotBlank(),
                        condition = alertCondition.text.toString().trim(),
                        severity = alertSeverity.text.toString().trim().lowercase(),
                        message = alertMessage.text.toString().trim(),
                    ),
                )
                val diagnostics = ExpertValidator.validate(definition)
                if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
                    throw WorkspaceException(diagnostics.filter { it.severity == DiagnosticSeverity.ERROR }.joinToString { it.message })
                }
                expertStore.save(definition)
                ExpertFbpAdapter.toJson(ExpertFbpAdapter.toNode(definition))
            }.onSuccess { descriptor ->
                status.text = "Saved in private storage. Descriptor is ready for an FBP graph."
                status.setTextColor(QuasarDesign.lime)
                preview.text = descriptor
            }.onFailure { error ->
                status.text = error.safeMessage()
                status.setTextColor(QuasarDesign.coral)
                preview.text = ""
            }
        }, QuasarDesign.match(top = dp(16)))
        content.addView(status, QuasarDesign.match(top = dp(9)))
        content.addView(preview, QuasarDesign.match(top = dp(10)))
        content.addView(QuasarDesign.action(this, "Back to experts", onClick = ::showExperts), QuasarDesign.match(top = dp(12)))
    }

    private fun expertField(label: String, value: String, hint: String, lines: Int = 1): EditText {
        content.addView(QuasarDesign.eyebrow(this, label), QuasarDesign.match(top = dp(15)))
        return QuasarDesign.field(this, value, lines, hint).also { content.addView(it, QuasarDesign.match(top = dp(6))) }
    }

    private fun captureDraft(): Boolean {
        handler.removeCallbacks(capturePending)
        if (restoringDrafts || !editorChanged) return true
        val name = activeName ?: return true
        val field = editor ?: return true
        return runCatching {
            val id = draftName(name)
            draftStore.update(id, field.text)
            val source = checkNotNull(draftStore.current(id))
            drafts[name] = SourceBuffer(name, language, source, dirty = true)
            draftReferences[id] = DraftReference.key(id)
            editorChanged = false
        }.fold({ true }, {
            field.error = "Draft was not captured. Keep this editor open; save or discard another draft."
            false
        })
    }

    private fun tokenColor(kind: TokenKind): Int = when (kind) {
        TokenKind.COMMENT -> QuasarDesign.muted
        TokenKind.KEYWORD -> QuasarDesign.pink
        TokenKind.VARIABLE -> QuasarDesign.amber
        TokenKind.NUMBER -> QuasarDesign.lime
        TokenKind.STRING -> QuasarDesign.amber
        TokenKind.OPERATOR -> QuasarDesign.coral
        TokenKind.SYMBOL -> QuasarDesign.cyan
        TokenKind.PUNCTUATION -> QuasarDesign.text
    }

    private fun sectionLine(label: String, state: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(QuasarDesign.eyebrow(this@LogicStudioActivity, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(QuasarDesign.pill(this@LogicStudioActivity, state, QuasarDesign.cyan))
    }

    private fun studioButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(QuasarDesign.text)
        setBackgroundColor(QuasarDesign.panelRaised)
        minHeight = dp(48)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { if (!restoringDrafts) action() }
    }

    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = start }

    private fun EditText.nonBlankLines(): List<String> = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun Throwable.safeMessage(): String = when (this) {
        is WorkspaceException -> message?.take(240) ?: "Validation failed"
        else -> "Private workspace operation failed"
    }

    companion object {
        private const val DIAGNOSTICS_TAG = "logic-diagnostics"
    }
}
