package actor.starintel.wear

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.tiles.TileService
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.sync.CompanionConfigProtocol
import actor.starintel.wear.tiles.CorpusTileService
import actor.starintel.wear.tiles.OpsTileService
import actor.starintel.wear.tiles.TargetsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConfigActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val repository = StarIntelRepository.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(26), dp(22), dp(26), dp(28))
            setBackgroundColor(Color.rgb(5, 7, 10))
        }

        val title = TextView(this).apply {
            text = "STARINTEL"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
            gravity = Gravity.CENTER
        }
        val recommendation = TextView(this).apply {
            text = "Use the Android companion for easier setup. This screen is the on-watch fallback."
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        val configState = TextView(this).apply {
            text = when {
                repository.baseUrl().isBlank() -> "○  Not configured"
                !repository.hasApiKey() -> "○  API key missing"
                else -> "●  Configuration saved securely"
            }
            textSize = 12f
            setTextColor(if (repository.hasApiKey() && repository.baseUrl().isNotBlank()) CYAN else MUTED)
            gravity = Gravity.CENTER
        }

        val serverUrlLabel = label("Server origin")
        val url = EditText(this).apply {
            setSingleLine(true)
            setText(repository.baseUrl())
            hint = "https://server.example"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val apiKeyLabel = label("Private API key")
        val apiKey = EditText(this).apply {
            setSingleLine(true)
            hint = if (repository.hasApiKey()) {
                "Saved securely · leave blank to keep"
            } else {
                "star_sk_v1_…"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val save = Button(this).apply { text = "TEST + SAVE" }
        val forgetKey = Button(this).apply {
            text = "REMOVE SAVED KEY"
            isEnabled = repository.hasApiKey()
        }
        val status = TextView(this).apply {
            text = "Changes are saved only after the authenticated test succeeds."
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }

        save.setOnClickListener {
            val candidateUrl = CompanionConfigProtocol.normalizeServerUrl(
                url.text.toString(),
                allowCleartext = BuildConfig.DEBUG,
            )
            val candidateKey = apiKey.text.toString().trim()

            if (candidateUrl == null) {
                status.text = if (BuildConfig.DEBUG) "Enter a valid server origin" else "Use a valid https:// server origin"
                status.setTextColor(WARNING)
                return@setOnClickListener
            }
            if (candidateKey.isBlank() && !repository.hasApiKey()) {
                status.text = "API key required"
                status.setTextColor(WARNING)
                return@setOnClickListener
            }
            if (candidateKey.isNotBlank() && !CompanionConfigProtocol.validApiKey(candidateKey)) {
                status.text = "Invalid star_sk_v1_… API key"
                status.setTextColor(WARNING)
                return@setOnClickListener
            }

            status.text = "Testing authenticated connection…"
            status.setTextColor(MUTED)
            save.isEnabled = false
            forgetKey.isEnabled = false
            url.isEnabled = false
            apiKey.isEnabled = false

            scope.launch {
                val snapshot = if (candidateKey.isBlank()) {
                    repository.testStoredConnection(candidateUrl)
                } else {
                    repository.testConnection(candidateUrl, candidateKey)
                }

                save.isEnabled = true
                url.isEnabled = true
                apiKey.isEnabled = true
                forgetKey.isEnabled = repository.hasApiKey()

                if (snapshot.reachable) {
                    val committed = repository.commitConfiguration(
                        baseUrl = candidateUrl,
                        apiKey = candidateKey.takeIf { it.isNotBlank() },
                    )
                    if (!committed) {
                        status.text = "Could not save configuration securely"
                        status.setTextColor(WARNING)
                        return@launch
                    }

                    apiKey.text.clear()
                    apiKey.hint = "Saved securely · leave blank to keep"
                    forgetKey.isEnabled = true
                    configState.text = "●  Configuration saved securely"
                    configState.setTextColor(CYAN)
                    status.text = "Authenticated · ${snapshot.documentsTotal} docs"
                    status.setTextColor(CYAN)
                    requestTileUpdates()
                } else {
                    val code = CompanionConfigProtocol.errorCode(snapshot.error)
                    status.text = CompanionConfigProtocol.safeDetail(code)
                    status.setTextColor(WARNING)
                }
            }
        }

        forgetKey.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Remove API key?")
                .setMessage("StarIntel Tiles will stop refreshing until a new key is configured.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    val removed = runCatching {
                        repository.clearApiKey()
                        true
                    }.getOrDefault(false)
                    if (!removed) {
                        status.text = "Could not remove saved key"
                        status.setTextColor(WARNING)
                        return@setPositiveButton
                    }

                    apiKey.text.clear()
                    apiKey.hint = "star_sk_v1_…"
                    forgetKey.isEnabled = false
                    configState.text = "○  API key missing"
                    configState.setTextColor(MUTED)
                    status.text = "Saved key removed"
                    status.setTextColor(MUTED)
                    requestTileUpdates()
                }
                .show()
        }

        root.addView(title, matchWrap())
        root.addView(recommendation, matchWrap(top = 5))
        root.addView(configState, matchWrap(top = 8))
        root.addView(serverUrlLabel, matchWrap(top = 12))
        root.addView(url, matchWrap(top = 2))
        root.addView(apiKeyLabel, matchWrap(top = 8))
        root.addView(apiKey, matchWrap(top = 2))
        root.addView(save, matchWrap(top = 8))
        root.addView(forgetKey, matchWrap(top = 3))
        root.addView(status, matchWrap(top = 7))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun requestTileUpdates() {
        val updater = TileService.getUpdater(applicationContext)
        updater.requestUpdate(OpsTileService::class.java)
        updater.requestUpdate(TargetsTileService::class.java)
        updater.requestUpdate(CorpusTileService::class.java)
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 11f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
