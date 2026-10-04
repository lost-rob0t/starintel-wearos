package actor.starintel.quasar

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private val client by lazy { StarServerClient(this) }
    private val config by lazy { QuasarConfig(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(40))
            setBackgroundColor(BACKGROUND)
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        showHome()
    }

    private fun shell(screenTitle: String, home: Boolean = true) {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = "QUASAR · NATIVE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = .14f
            setTextColor(CYAN)
        }, matchWrap())
        title = TextView(this).apply {
            text = screenTitle
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(title, matchWrap(top = 3))
        if (home) root.addView(button("← HOME") { showHome() }, matchWrap(top = 12))
        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, centeredWrap(top = 10))
    }

    private fun showHome() {
        shell("StarIntel workspace", home = false)
        root.addView(copy(if (config.isConfigured()) "Connected configuration is available." else "Configure the Star server to begin."), matchWrap(top = 8))
        val routes = listOf(
            "HOME / STATS" to { showJson("Home", { client.stats() }) },
            "GRAPHS" to { showSearch("Graphs", "relation") },
            "DATASETS" to { showSearch("Datasets", "dtype:dataset") },
            "DOCUMENTS" to { showSearch("Documents", "*") },
            "ADD DOCUMENT" to { showCreateDocument() },
            "AGENTS" to { showSearch("Agents", "dtype:agent") },
            "ACTORS" to { showSearch("Actors", "dtype:actor") },
            "IMPORT" to { showImport() },
            "TARGETS" to { showTarget() },
            "SETTINGS" to { showSettings() },
        )
        routes.forEachIndexed { index, (label, action) -> root.addView(button(label, action), matchWrap(top = if (index == 0) 22 else 7)) }
        root.addView(copy("Native routes mirror Quasar: dashboard, graphs, datasets, documents, creation, agents, actors, import, and settings. Server operations use the discovered/current Star API."), matchWrap(top = 18))
    }

    private fun showJson(screen: String, operation: () -> JSONObject) {
        shell(screen)
        val output = copy("Loading…")
        root.addView(output, matchWrap(top = 14))
        runApi({ operation().toString(2) }) { result -> output.text = result }
    }

    private fun showSearch(screen: String, initial: String) {
        shell(screen)
        val query = field(initial, singleLine = true)
        root.addView(query, matchWrap(top = 14))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(button("SEARCH") {
            results.removeAllViews()
            runApi({ client.search(query.text.toString()) }) { value ->
                val rows = value as JSONArray
                if (rows.length() == 0) results.addView(copy("No results."))
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val id = resultId(row)
                    val document = resultDocument(row)
                    val label = document.optString("name")
                        .ifBlank { document.optString("title") }
                        .ifBlank { id }
                    results.addView(button("${document.optString("dtype").ifBlank { "document" }} · $label") {
                        showDocument(id)
                    }, matchWrap(top = 6))
                }
            }
        }, matchWrap(top = 8))
        root.addView(results, matchWrap(top = 12))
    }

    private fun showDocument(id: String) {
        shell("Document")
        root.addView(copy(id), matchWrap(top = 10))
        val output = copy("Loading…")
        output.typeface = Typeface.MONOSPACE
        root.addView(output, matchWrap(top = 8))
        runApi({ client.document(id).toString(2) }) { output.text = it }
    }

    private fun showCreateDocument() {
        shell("Add document")
        val editor = field("{\n  \"dtype\": \"document\",\n  \"data\": {}\n}", lines = 14)
        root.addView(editor, matchWrap(top = 14))
        val status = copy("Validated by the current Star server schema.")
        root.addView(button("CREATE") {
            runApi({ client.createDocument(JSONObject(editor.text.toString())).toString(2) }) { status.text = it }
        }, matchWrap(top = 8))
        root.addView(status, matchWrap(top = 8))
    }

    private fun showImport() {
        shell("Import")
        val editor = field("[]", lines = 14)
        root.addView(copy("Paste a bounded JSON array of StarIntel documents."), matchWrap(top = 10))
        root.addView(editor, matchWrap(top = 8))
        val status = copy("")
        root.addView(button("IMPORT BATCH") {
            runApi({ client.bulkCreate(JSONArray(editor.text.toString())).toString(2) }) { status.text = it }
        }, matchWrap(top = 8))
        root.addView(status, matchWrap(top = 8))
    }

    private fun showTarget() {
        shell("Create target")
        val actor = field("user-hunt", singleLine = true)
        val target = field("target", singleLine = true)
        val dataset = field("investigation", singleLine = true)
        listOf("ACTOR" to actor, "TARGET" to target, "DATASET" to dataset).forEach { (label, input) ->
            root.addView(copy(label), matchWrap(top = 10))
            root.addView(input, matchWrap(top = 3))
        }
        val status = copy("")
        root.addView(button("DISPATCH") {
            runApi({ client.createTarget(actor.text.toString(), target.text.toString(), dataset.text.toString()).toString(2) }) {
                status.text = it
            }
        }, matchWrap(top = 10))
        root.addView(status, matchWrap(top = 8))
    }

    private fun showSettings() {
        shell("Settings")
        val server = field(config.serverUrl().ifBlank { "https://starintel.example" }, singleLine = true)
        root.addView(copy("STAR SERVER URL"), matchWrap(top = 12))
        root.addView(server, matchWrap(top = 3))

        root.addView(copy("USERNAME + PASSWORD"), matchWrap(top = 16))
        val username = field("", singleLine = true)
        username.hint = "username"
        val password = field("", singleLine = true).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(username, matchWrap(top = 4))
        root.addView(password, matchWrap(top = 5))

        val status = copy(if (config.isConfigured()) "A Keystore-encrypted key is stored." else "Not configured.")
        root.addView(button("LOGIN") {
            runApi({
                val login = client.login(server.text.toString(), username.text.toString(), password.text.toString())
                client.authContext(server.text.toString(), login.apiKey)
                login
            }) { login ->
                runCatching { config.save(server.text.toString(), login.apiKey) }
                    .onSuccess {
                    password.text.clear()
                    status.text = buildString {
                        append("Connected")
                        if (login.username.isNotBlank()) append(" · ").append(login.username)
                        if (login.mustChangePassword) append(" · password change required")
                    }
                    }
                    .onFailure { status.text = it.message ?: "Login validation failed" }
            }
        }, matchWrap(top = 10))

        root.addView(copy("OR API KEY"), matchWrap(top = 18))
        val key = field("", singleLine = true).apply {
            hint = "star_sk_v1_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key, matchWrap(top = 4))
        root.addView(button("USE API KEY") {
            val presented = key.text.toString().trim()
            runApi({ client.authContext(server.text.toString(), presented) }) {
                runCatching { config.save(server.text.toString(), presented) }
                    .onSuccess {
                        key.text.clear()
                        status.text = "Connected · API key authenticated"
                    }
                    .onFailure { status.text = it.message ?: "Could not save API key" }
            }
        }, matchWrap(top = 8))
        root.addView(status, matchWrap(top = 8))
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

    private fun field(value: String, singleLine: Boolean = false, lines: Int = 1) = EditText(this).apply {
        setText(value)
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        setBackgroundColor(SURFACE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setSingleLine(singleLine)
        if (!singleLine) { minLines = lines; gravity = Gravity.TOP; typeface = Typeface.MONOSPACE }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun copy(value: String) = TextView(this).apply { text = value; textSize = 13f; setTextColor(MUTED); setTextIsSelectable(true) }
    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val SURFACE = Color.rgb(15, 20, 27)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
    }
}
