package actor.starintel.mobile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.UUID

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private lateinit var serverUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var send: Button
    private lateinit var status: TextView
    private lateinit var watchStatus: TextView
    private lateinit var progress: ProgressBar

    private var reachableNode: Node? = null
    private var pending: PendingRequest? = null
    private val handler = Handler(Looper.getMainLooper())

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(32))
            setBackgroundColor(BACKGROUND)
        }

        val eyebrow = TextView(this).apply {
            text = "STARINTEL · WEAR OS"
            textSize = 12f
            setTextColor(CYAN)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        }
        val title = TextView(this).apply {
            text = "Connect your watch"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Configure StarIntel on your paired Galaxy Watch without typing credentials on the watch."
            textSize = 15f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.15f)
        }

        watchStatus = TextView(this).apply {
            text = "Checking for StarIntel Wear…"
            textSize = 14f
            setTextColor(MUTED)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, dp(14), STROKE)
        }

        val serverLabel = label("Server origin")
        serverUrl = EditText(this).apply {
            setSingleLine(true)
            hint = "https://starintel.example"
            setText(prefs.getString(KEY_SERVER_URL, "").orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(HINT)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(FIELD, dp(12), STROKE)
        }

        val keyLabel = label("Private API key")
        apiKey = EditText(this).apply {
            setSingleLine(true)
            hint = "star_sk_v1_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(HINT)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(FIELD, dp(12), STROKE)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }

        val privacy = TextView(this).apply {
            text = "The API key is never saved on this phone. It is transferred once to the paired watch and stored there with Android Keystore."
            textSize = 13f
            setTextColor(MUTED)
            setLineSpacing(0f, 1.15f)
        }

        send = Button(this).apply {
            text = "SEND SECURELY TO WATCH"
            isEnabled = false
            filterTouchesWhenObscured = true
            setOnClickListener { sendConfiguration() }
        }

        progress = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }

        status = TextView(this).apply {
            text = "StarIntel Wear must be installed and reachable on the paired watch."
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.15f)
        }

        root.addView(eyebrow, matchWrap())
        root.addView(title, matchWrap(top = 4))
        root.addView(subtitle, matchWrap(top = 8))
        root.addView(watchStatus, matchWrap(top = 24))
        root.addView(serverLabel, matchWrap(top = 28))
        root.addView(serverUrl, matchWrap(top = 6))
        root.addView(keyLabel, matchWrap(top = 20))
        root.addView(apiKey, matchWrap(top = 6))
        root.addView(privacy, matchWrap(top = 10))
        root.addView(send, matchWrap(top = 24))
        root.addView(progress, centeredWrap(top = 14))
        root.addView(status, matchWrap(top = 14))

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        // Keep this listener for the activity lifetime so a short app switch does not
        // discard the acknowledgement for an already-sent credential transfer.
        messageClient.addListener(this)
    }

    override fun onStart() {
        super.onStart()
        refreshWatchState()
    }

    override fun onDestroy() {
        messageClient.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        if (::apiKey.isInitialized) apiKey.text?.clear()
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != CompanionProtocol.ACK_PATH) return
        val current = pending ?: return
        if (messageEvent.sourceNodeId != current.nodeId) return

        val ack = runCatching { DataMap.fromByteArray(messageEvent.data) }.getOrNull() ?: return
        if (ack.getInt("version", 0) != CompanionProtocol.VERSION) return
        val requestId = ack.getString("request_id") ?: return
        if (requestId != current.requestId) return

        val ok = ack.getBoolean("ok", false)
        val code = ack.getString("code")
        val detail = ack.getString("detail")

        runOnUiThread {
            if (pending?.requestId != requestId) return@runOnUiThread
            pending = null
            handler.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            setBusy(false)

            if (ok) {
                prefs.edit().putString(KEY_SERVER_URL, current.serverUrl).apply()
                serverUrl.setText(current.serverUrl)
                apiKey.text?.clear()
                showStatus(
                    CompanionProtocol.userMessage(code, detail),
                    success = true,
                )
            } else {
                showStatus(CompanionProtocol.userMessage(code, detail), success = false)
            }
        }
    }

    private fun refreshWatchState() {
        if (pending != null) return
        watchStatus.text = "Checking for StarIntel Wear…"
        watchStatus.setTextColor(MUTED)
        send.isEnabled = false

        capabilityClient
            .getCapability(CompanionProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val node = capability.nodes
                    .sortedWith(compareByDescending<Node> { it.isNearby }.thenBy { it.displayName })
                    .firstOrNull()
                reachableNode = node
                if (node == null) {
                    watchStatus.text = "○  StarIntel Wear is not reachable"
                    watchStatus.setTextColor(WARNING)
                    showStatus("Install/open StarIntel Wear on the paired watch, then return here.", success = false)
                    send.isEnabled = false
                } else {
                    watchStatus.text = "●  Connected to ${node.displayName}"
                    watchStatus.setTextColor(CYAN)
                    showStatus("Ready to send configuration securely.", success = null)
                    send.isEnabled = true
                }
            }
            .addOnFailureListener {
                reachableNode = null
                watchStatus.text = "○  Could not check paired watch"
                watchStatus.setTextColor(WARNING)
                showStatus("Wear OS connection check failed. Reopen the app and try again.", success = false)
                send.isEnabled = false
            }
    }

    private fun sendConfiguration() {
        if (pending != null) return

        val normalizedUrl = CompanionProtocol.normalizeServerUrl(
            serverUrl.text.toString(),
            allowCleartext = BuildConfig.DEBUG,
        )
        val key = apiKey.text.toString().trim()
        val node = reachableNode

        if (normalizedUrl == null) {
            showStatus(
                if (BuildConfig.DEBUG) "Enter a valid StarIntel server origin." else "Enter a valid https:// StarIntel server origin.",
                success = false,
            )
            serverUrl.requestFocus()
            return
        }
        if (!CompanionProtocol.validApiKey(key)) {
            showStatus("Enter a valid star_sk_v1_… API key with no spaces.", success = false)
            apiKey.requestFocus()
            return
        }
        if (node == null) {
            showStatus("No reachable StarIntel watch was found.", success = false)
            refreshWatchState()
            return
        }

        val requestId = UUID.randomUUID().toString()
        val payload = DataMap().apply {
            putInt("version", CompanionProtocol.VERSION)
            putString("server_url", normalizedUrl)
            putString("api_key", key)
            putString("request_id", requestId)
        }.toByteArray()

        if (payload.size > CompanionProtocol.MAX_PAYLOAD_BYTES) {
            showStatus("Configuration payload is too large.", success = false)
            return
        }

        val request = PendingRequest(requestId, node.id, normalizedUrl)
        pending = request
        setBusy(true)
        showStatus("Sending to ${node.displayName}…", success = null)

        messageClient.sendMessage(node.id, CompanionProtocol.CONFIG_PATH, payload)
            .addOnSuccessListener {
                if (pending?.requestId != requestId) return@addOnSuccessListener
                showStatus("Sent. Watch is testing the authenticated connection…", success = null)
                scheduleTimeout(requestId)
            }
            .addOnFailureListener {
                if (pending?.requestId != requestId) return@addOnFailureListener
                pending = null
                setBusy(false)
                showStatus("Could not send configuration to the watch.", success = false)
                refreshWatchState()
            }
    }

    private fun scheduleTimeout(requestId: String) {
        handler.postAtTime({
            if (pending?.requestId == requestId) {
                pending = null
                setBusy(false)
                showStatus(
                    "No confirmation arrived. The watch may have applied the configuration; check the watch before retrying.",
                    success = false,
                )
            }
        }, TIMEOUT_TOKEN, SystemClock.uptimeMillis() + CompanionProtocol.ACK_TIMEOUT_MS)
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        send.isEnabled = !busy && reachableNode != null
        serverUrl.isEnabled = !busy
        apiKey.isEnabled = !busy
    }

    private fun showStatus(message: String, success: Boolean?) {
        status.text = message
        status.setTextColor(
            when (success) {
                true -> CYAN
                false -> WARNING
                null -> MUTED
            }
        )
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 13f
        setTextColor(MUTED)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class PendingRequest(
        val requestId: String,
        val nodeId: String,
        val serverUrl: String,
    )

    companion object {
        private const val PREFS = "starintel_companion"
        private const val KEY_SERVER_URL = "server_url"
        private val TIMEOUT_TOKEN = Any()

        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CARD = Color.rgb(15, 20, 27)
        private val FIELD = Color.rgb(11, 15, 21)
        private val STROKE = Color.rgb(42, 54, 66)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val HINT = Color.rgb(111, 123, 136)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
