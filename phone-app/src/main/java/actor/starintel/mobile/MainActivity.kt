package actor.starintel.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.util.UUID

class MainActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private lateinit var serverUrl: EditText
    private lateinit var apiKey: EditText
    private lateinit var send: Button
    private lateinit var status: TextView
    private var pendingNonce: String? = null

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        val title = TextView(this).apply {
            text = "STARINTEL"
            textSize = 28f
            setTextColor(Color.rgb(0, 229, 255))
        }
        val subtitle = TextView(this).apply {
            text = "Configure your paired Wear OS watch"
            textSize = 15f
            setTextColor(Color.LTGRAY)
        }
        val serverLabel = label("Server URL")
        serverUrl = EditText(this).apply {
            setSingleLine(true)
            hint = "https://starintel.example"
            setText(prefs.getString(KEY_SERVER_URL, "").orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val keyLabel = label("Private StarIntel API key")
        apiKey = EditText(this).apply {
            setSingleLine(true)
            hint = "star_sk_v1_…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        send = Button(this).apply {
            text = "SEND TO WATCH"
            setOnClickListener { sendConfiguration() }
        }
        status = TextView(this).apply {
            text = "The key stays in memory on the phone and is sent only to the paired StarIntel watch."
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        root.addView(title, matchWrap())
        root.addView(subtitle, matchWrap(top = 4))
        root.addView(serverLabel, matchWrap(top = 28))
        root.addView(serverUrl, matchWrap(top = 4))
        root.addView(keyLabel, matchWrap(top = 18))
        root.addView(apiKey, matchWrap(top = 4))
        root.addView(send, matchWrap(top = 22))
        root.addView(status, matchWrap(top = 18))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onStart() {
        super.onStart()
        messageClient.addListener(this)
    }

    override fun onStop() {
        messageClient.removeListener(this)
        super.onStop()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != CompanionProtocol.ACK_PATH) return
        val ack = runCatching { DataMap.fromByteArray(messageEvent.data) }.getOrNull() ?: return
        val nonce = ack.getString("nonce") ?: return
        if (nonce != pendingNonce) return

        val ok = ack.getBoolean("ok", false)
        val detail = ack.getString("detail") ?: if (ok) "Configured" else "Configuration failed"
        runOnUiThread {
            pendingNonce = null
            send.isEnabled = true
            status.text = detail
            status.setTextColor(if (ok) Color.rgb(0, 229, 255) else Color.rgb(255, 128, 128))
        }
    }

    private fun sendConfiguration() {
        val normalizedUrl = CompanionProtocol.normalizeServerUrl(serverUrl.text.toString())
        val key = apiKey.text.toString().trim()
        if (normalizedUrl == null) {
            fail("Enter a valid http:// or https:// StarIntel URL")
            return
        }
        if (!CompanionProtocol.validApiKey(key)) {
            fail("Enter a valid star_sk_v1_… API key")
            return
        }

        send.isEnabled = false
        status.text = "Looking for your StarIntel watch…"
        status.setTextColor(Color.LTGRAY)

        capabilityClient
            .getCapability(CompanionProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val node = capability.nodes
                    .sortedWith(compareByDescending<com.google.android.gms.wearable.Node> { it.isNearby }.thenBy { it.displayName })
                    .firstOrNull()
                if (node == null) {
                    send.isEnabled = true
                    fail("No paired watch with StarIntel installed is reachable")
                    return@addOnSuccessListener
                }

                val nonce = UUID.randomUUID().toString()
                pendingNonce = nonce
                val payload = DataMap().apply {
                    putInt("version", CompanionProtocol.VERSION)
                    putString("server_url", normalizedUrl)
                    putString("api_key", key)
                    putString("nonce", nonce)
                }.toByteArray()

                messageClient.sendMessage(node.id, CompanionProtocol.CONFIG_PATH, payload)
                    .addOnSuccessListener {
                        prefs.edit().putString(KEY_SERVER_URL, normalizedUrl).apply()
                        apiKey.text?.clear()
                        status.text = "Sent to ${node.displayName}; waiting for authenticated check…"
                        status.setTextColor(Color.LTGRAY)
                    }
                    .addOnFailureListener { failure ->
                        pendingNonce = null
                        send.isEnabled = true
                        fail("Could not send to watch: ${failure.javaClass.simpleName}")
                    }
            }
            .addOnFailureListener { failure ->
                send.isEnabled = true
                fail("Could not find paired watch: ${failure.javaClass.simpleName}")
            }
    }

    private fun fail(message: String) {
        status.text = message
        status.setTextColor(Color.rgb(255, 128, 128))
    }

    private fun label(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(Color.LTGRAY)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "starintel_companion"
        private const val KEY_SERVER_URL = "server_url"
    }
}
