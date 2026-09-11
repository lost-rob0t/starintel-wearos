package actor.starintel.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.tiles.TileService
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.tiles.CorpusTileService
import actor.starintel.wear.tiles.OpsTileService
import actor.starintel.wear.tiles.TargetsTileService
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ConfigActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = StarIntelRepository.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        val title = TextView(this).apply {
            text = "STARINTEL"
            textSize = 20f
            setTextColor(Color.rgb(0, 229, 255))
            gravity = Gravity.CENTER
        }
        val serverUrlLabel = TextView(this).apply {
            text = "Server URL"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val url = EditText(this).apply {
            setSingleLine(true)
            setText(repository.baseUrl())
            this.hint = "https://server.example"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val apiKeyLabel = TextView(this).apply {
            text = "Private API key"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val apiKey = EditText(this).apply {
            setSingleLine(true)
            this.hint = if (repository.hasApiKey()) {
                "Saved securely — leave blank to keep"
            } else {
                "star_sk_v1_…"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }
        val save = Button(this).apply { text = "SAVE + TEST" }
        val forgetKey = Button(this).apply {
            text = "FORGET KEY"
            isEnabled = repository.hasApiKey()
        }
        val status = TextView(this).apply {
            text = when {
                repository.baseUrl().isBlank() -> "Server not configured"
                !repository.hasApiKey() -> "API key required"
                else -> "Saved · auth ready"
            }
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        save.setOnClickListener {
            val candidate = url.text.toString().trim().trimEnd('/')
            val candidateKey = apiKey.text.toString().trim()

            if (!validServerUrl(candidate)) {
                status.text = "Use https://"
                status.setTextColor(Color.rgb(255, 128, 128))
                return@setOnClickListener
            }
            if (candidateKey.isBlank() && !repository.hasApiKey()) {
                status.text = "API key required"
                status.setTextColor(Color.rgb(255, 128, 128))
                return@setOnClickListener
            }

            repository.setBaseUrl(candidate)
            if (candidateKey.isNotBlank()) {
                repository.setApiKey(candidateKey)
                apiKey.text.clear()
                apiKey.hint = "Saved securely — leave blank to keep"
                forgetKey.isEnabled = true
            }

            status.text = "Authenticating…"
            status.setTextColor(Color.LTGRAY)
            save.isEnabled = false

            scope.launch {
                val snapshot = repository.snapshot(forceRefresh = true)
                save.isEnabled = true
                status.text = when {
                    snapshot.reachable -> "Authenticated · ${snapshot.documentsTotal} docs"
                    snapshot.error != null -> snapshot.error
                    else -> "Offline"
                }
                status.setTextColor(
                    if (snapshot.reachable) Color.rgb(0, 229, 255) else Color.rgb(255, 128, 128)
                )
                requestTileUpdates()
            }
        }

        forgetKey.setOnClickListener {
            repository.clearApiKey()
            apiKey.text.clear()
            apiKey.hint = "star_sk_v1_…"
            forgetKey.isEnabled = false
            status.text = "API key removed"
            status.setTextColor(Color.LTGRAY)
            requestTileUpdates()
        }

        root.addView(title, matchWrap())
        root.addView(serverUrlLabel, matchWrap(top = 10))
        root.addView(url, matchWrap(top = 4))
        root.addView(apiKeyLabel, matchWrap(top = 10))
        root.addView(apiKey, matchWrap(top = 4))
        root.addView(save, matchWrap(top = 8))
        root.addView(forgetKey, matchWrap(top = 4))
        root.addView(status, matchWrap(top = 8))

        setContentView(ScrollView(this).apply { addView(root) })
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

    private fun validServerUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.host != null && if (BuildConfig.DEBUG) {
            uri.scheme == "https" || uri.scheme == "http"
        } else {
            uri.scheme == "https"
        }
    }.getOrDefault(false)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
