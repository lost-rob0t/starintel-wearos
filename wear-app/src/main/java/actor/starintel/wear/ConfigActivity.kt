package actor.starintel.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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
        val save = Button(this).apply { text = "SAVE + TEST" }
        val status = TextView(this).apply {
            text = if (repository.baseUrl().isBlank()) "Not configured" else "Saved"
            textSize = 12f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        save.setOnClickListener {
            val candidate = url.text.toString().trim().trimEnd('/')
            if (!validServerUrl(candidate)) {
                status.text = "Use http:// or https://"
                status.setTextColor(Color.rgb(255, 128, 128))
                return@setOnClickListener
            }

            repository.setBaseUrl(candidate)
            status.text = "Testing…"
            status.setTextColor(Color.LTGRAY)
            save.isEnabled = false

            scope.launch {
                val snapshot = repository.snapshot(forceRefresh = true)
                save.isEnabled = true
                status.text = when {
                    snapshot.reachable -> "Online · ${snapshot.documentsTotal} docs"
                    snapshot.error != null -> "Offline · ${snapshot.error}"
                    else -> "Offline"
                }
                status.setTextColor(
                    if (snapshot.reachable) Color.rgb(0, 229, 255) else Color.rgb(255, 128, 128)
                )
                requestTileUpdates()
            }
        }

        root.addView(title, matchWrap())
        root.addView(serverUrlLabel, matchWrap(top = 10))
        root.addView(url, matchWrap(top = 4))
        root.addView(save, matchWrap(top = 8))
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
        uri.host != null && (uri.scheme == "https" || uri.scheme == "http")
    }.getOrDefault(false)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
