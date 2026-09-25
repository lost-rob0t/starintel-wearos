package actor.starintel.quasar.ide

import actor.starintel.quasar.QuasarDesign
import actor.starintel.quasar.dp
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
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
    private var applyingHighlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = QuasarDesign.background
        window.navigationBarColor = QuasarDesign.background
        workspace = PrivateWorkspaceStore(File(filesDir, "logic-workspace"))
        expertStore = ExpertDefinitionStore(File(filesDir, "expert-definitions"))
        runCatching { workspace.seedDefaults() }
            .onFailure { Toast.makeText(this, it.safeMessage(), Toast.LENGTH_LONG).show() }
        renderShell()
        openLanguage(IdeLanguage.LISP)
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
        setContentView(shell)
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
            row.addView(studioButton(label, action), LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        return row
    }

    private fun openLanguage(nextLanguage: IdeLanguage) {
        captureDraft()
        language = nextLanguage
        drafts.clear()
        val buffers = runCatching { workspace.list(language) }
            .onFailure { toast(it.safeMessage()) }
            .getOrDefault(emptyList())
        buffers.forEach { drafts[it.name] = it }
        activeName = buffers.firstOrNull()?.name
        showEditor()
    }

    private fun showEditor() {
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
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(value: Editable?) {
                    if (applyingHighlight || value == null) return
                    val name = activeName ?: return
                    drafts[name] = SourceBuffer(name, language, value.toString(), dirty = true)
                    highlight(value, language)
                }
            })
        }
        editor?.let { field ->
            highlight(field.text, language)
            content.addView(field, QuasarDesign.match(top = dp(12)))
        }

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
                isClickable = true
                setOnClickListener {
                    captureDraft()
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
        captureDraft()
        val name = activeName ?: return
        val buffer = drafts[name] ?: return
        runCatching { workspace.save(buffer.copy(dirty = false)) }
            .onSuccess {
                drafts[name] = buffer.copy(dirty = false)
                toast("Saved $name atomically")
                showEditor()
            }
            .onFailure { toast(it.safeMessage()) }
    }

    private fun validateActive() {
        captureDraft()
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
                    drafts[name] = buffer
                    activeName = name
                    showEditor()
                }.onFailure { toast(it.safeMessage()) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExperts() {
        captureDraft()
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

    private fun captureDraft() {
        val name = activeName ?: return
        val field = editor ?: return
        drafts[name] = SourceBuffer(name, language, field.text.toString(), dirty = drafts[name]?.dirty == true || field.text.toString() != drafts[name]?.source)
    }

    private fun highlight(editable: Editable, targetLanguage: IdeLanguage) {
        applyingHighlight = true
        try {
            editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach(editable::removeSpan)
            LanguageRegistry.implementation(targetLanguage).tokenize(editable.toString()).forEach { token ->
                editable.setSpan(
                    ForegroundColorSpan(tokenColor(token.kind)),
                    token.start.coerceAtMost(editable.length),
                    token.endExclusive.coerceAtMost(editable.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        } finally {
            applyingHighlight = false
        }
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
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun weighted(start: Int = 0) = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = start }

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
