package actor.starintel.mobile

import actor.starintel.update.PackageTransferProtocol
import actor.starintel.update.UpdateArtifact
import actor.starintel.update.UpdateFeed
import actor.starintel.update.UpdateManifest
import actor.starintel.update.UpdateSources
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import java.util.ArrayDeque

class WatchPackagesActivity : Activity(), MessageClient.OnMessageReceivedListener {
    private lateinit var receiverStatus: TextView
    private lateinit var sourceStatus: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var neon: Button
    private lateinit var command: Button
    private lateinit var terminal: Button
    private lateinit var wear: Button
    private lateinit var all: Button

    private val prefs by lazy { getSharedPreferences(UpdateManagerActivity.PREFS, MODE_PRIVATE) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val transferClient by lazy { PackageTransferClient(this) }

    private var node: Node? = null
    private var manifest: UpdateManifest? = null
    private var busy = false
    private var activeTransferId: String? = null
    private val queue = ArrayDeque<UpdateArtifact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(32))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL · COMPANION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.1f
            setTextColor(CYAN)
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Watch apps"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, matchWrap(top = 3))
        root.addView(TextView(this).apply {
            text = "Download on the phone, verify, then stream to the paired watch. When the watch is nearby the Wear OS Data Layer normally transfers over Bluetooth."
            textSize = 14f
            setTextColor(MUTED)
        }, matchWrap(top = 8))

        receiverStatus = TextView(this).apply { textSize = 14f; setTextColor(MUTED) }
        root.addView(receiverStatus, matchWrap(top = 22))
        sourceStatus = TextView(this).apply { textSize = 13f; setTextColor(CYAN) }
        root.addView(sourceStatus, matchWrap(top = 8))

        root.addView(Button(this).apply {
            text = "REFRESH WATCH + CATALOG"
            setOnClickListener { refreshAll() }
        }, matchWrap(top = 12))

        neon = packageButton("INSTALL NEON") { installIds(listOf("watchface-neon")) }
        command = packageButton("INSTALL COMMAND") { installIds(listOf("watchface-command")) }
        terminal = packageButton("INSTALL TERMINAL") { installIds(listOf("watchface-terminal")) }
        wear = packageButton("UPDATE STARINTEL WEAR") { installIds(listOf("wear")) }
        all = packageButton("INSTALL / UPDATE ALL") { installAll() }
        root.addView(neon, matchWrap(top = 20))
        root.addView(command, matchWrap(top = 6))
        root.addView(terminal, matchWrap(top = 6))
        root.addView(wear, matchWrap(top = 6))
        root.addView(all, matchWrap(top = 12))

        progress = ProgressBar(this).apply { visibility = View.GONE; isIndeterminate = true }
        root.addView(progress, centeredWrap(top = 14))
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setLineSpacing(0f, 1.15f)
        }
        root.addView(status, matchWrap(top = 12))
        root.addView(TextView(this).apply {
            text = "First-time bootstrap: the watch must already have a StarIntel Wear build containing the package receiver. After that, installs and updates do not need VPN or ADB. Wear OS may still ask you to approve package installation."
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
        }, matchWrap(top = 18))

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        setButtonsEnabled(false)
    }

    override fun onStart() {
        super.onStart()
        messageClient.addListener(this)
        refreshAll()
    }

    override fun onStop() {
        messageClient.removeListener(this)
        super.onStop()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PackageTransferProtocol.STATUS_PATH) return
        val expected = activeTransferId ?: return
        val update = runCatching { PackageTransferProtocol.Status.parse(messageEvent.data) }.getOrNull() ?: return
        if (update.transferId != expected) return
        runOnUiThread { handleStatus(update) }
    }

    private fun refreshAll() {
        if (busy) return
        refreshReceiver()
        refreshCatalog()
    }

    private fun refreshReceiver() {
        receiverStatus.text = "Checking package receiver…"
        receiverStatus.setTextColor(MUTED)
        node = null
        setButtonsEnabled(false)
        capabilityClient.getCapability(PackageTransferProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                val found = capability.nodes
                    .sortedWith(compareByDescending<Node> { it.isNearby }.thenBy { it.displayName })
                    .firstOrNull()
                node = found
                if (found == null) {
                    receiverStatus.text = "○  Package receiver not reachable"
                    receiverStatus.setTextColor(WARNING)
                    status.text = "Install/bootstrap the current StarIntel Wear APK once, then reopen this screen."
                } else {
                    receiverStatus.text = if (found.isNearby) {
                        "●  ${found.displayName} · nearby / Bluetooth preferred"
                    } else {
                        "●  ${found.displayName} · reachable"
                    }
                    receiverStatus.setTextColor(CYAN)
                    setButtonsEnabled(manifest != null && !busy)
                }
            }
            .addOnFailureListener {
                receiverStatus.text = "○  Wear OS transport unavailable"
                receiverStatus.setTextColor(WARNING)
            }
    }

    private fun refreshCatalog() {
        val source = prefs.getString(UpdateManagerActivity.KEY_SOURCE, UpdateSources.MASTER) ?: UpdateSources.MASTER
        sourceStatus.text = "SOURCE · ${UpdateSources.label(source)}"
        manifest = null
        setButtonsEnabled(false)
        Thread {
            runCatching { UpdateFeed.fetch(source) }
                .onSuccess { value -> runOnUiThread {
                    manifest = value
                    sourceStatus.text = "SOURCE · ${UpdateSources.label(source)} · ${value.versionName} · ${value.commit.take(10)}"
                    setButtonsEnabled(node != null && !busy)
                    if (node != null) status.text = "Ready. Choose a package or install all."
                } }
                .onFailure { error -> runOnUiThread {
                    sourceStatus.text = "SOURCE · ${UpdateSources.label(source)} · unavailable"
                    status.text = "Catalog failed: ${error.message}"
                    status.setTextColor(WARNING)
                } }
        }.start()
    }

    private fun installIds(ids: List<String>) {
        val value = manifest ?: return
        val selected = ids.map { value.artifact(it) }
        startQueue(selected)
    }

    private fun installAll() {
        val value = manifest ?: return
        startQueue(value.wearArtifacts())
    }

    private fun startQueue(items: List<UpdateArtifact>) {
        if (busy || items.isEmpty()) return
        if (node == null) {
            status.text = "Watch package receiver is not reachable."
            status.setTextColor(WARNING)
            return
        }
        queue.clear()
        items.sortedWith(compareBy<UpdateArtifact> { it.installOrder }.thenBy { it.id }).forEach(queue::addLast)
        busy = true
        progress.visibility = View.VISIBLE
        setButtonsEnabled(false)
        status.setTextColor(MUTED)
        sendNext()
    }

    private fun sendNext() {
        val target = node
        val value = manifest
        val artifact = queue.pollFirst()
        if (target == null || value == null) {
            finishQueue(false, "Watch or catalog disappeared during install.")
            return
        }
        if (artifact == null) {
            finishQueue(true, "All selected StarIntel packages installed.")
            return
        }
        status.text = "Downloading ${artifact.id}…"
        Thread {
            runCatching { UpdateFeed.download(this, artifact) }
                .onSuccess { apk -> runOnUiThread {
                    status.text = "Transferring ${artifact.id}…"
                    val transferId = transferClient.send(
                        nodeId = target.id,
                        artifact = artifact,
                        versionCode = value.versionCode,
                        apk = apk,
                        onProgress = { percent -> status.text = "Transferring ${artifact.id} · $percent%" },
                        onReady = { id ->
                            if (activeTransferId == id) {
                                status.text = "Transferred ${artifact.id}. Waiting for watch verification…"
                            }
                        },
                        onFailure = { id, error ->
                            if (activeTransferId == id) finishQueue(false, "Transfer failed: ${error.message}")
                        },
                    )
                    activeTransferId = transferId
                } }
                .onFailure { error -> runOnUiThread { finishQueue(false, "Download failed: ${error.message}") } }
        }.start()
    }

    private fun handleStatus(update: PackageTransferProtocol.Status) {
        when (update.state) {
            PackageTransferProtocol.Status.RECEIVING -> status.text = "Watch receiving ${update.artifactId}…"
            PackageTransferProtocol.Status.VERIFYING -> status.text = "Watch verifying ${update.artifactId}…"
            PackageTransferProtocol.Status.WAITING_USER -> {
                status.text = update.detail ?: "Approve installation on the watch."
                status.setTextColor(WARNING)
            }
            PackageTransferProtocol.Status.INSTALLING -> {
                status.text = "Installing ${update.artifactId} on watch…"
                status.setTextColor(MUTED)
            }
            PackageTransferProtocol.Status.SUCCESS -> {
                activeTransferId = null
                status.text = "Installed ${update.artifactId}."
                status.setTextColor(CYAN)
                sendNext()
            }
            PackageTransferProtocol.Status.FAILURE -> {
                activeTransferId = null
                finishQueue(false, update.detail ?: "Watch rejected ${update.artifactId}.")
            }
        }
    }

    private fun finishQueue(ok: Boolean, message: String) {
        busy = false
        activeTransferId = null
        queue.clear()
        progress.visibility = View.GONE
        status.text = message
        status.setTextColor(if (ok) CYAN else WARNING)
        setButtonsEnabled(node != null && manifest != null)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (!::neon.isInitialized) return
        listOf(neon, command, terminal, wear, all).forEach { it.isEnabled = enabled }
    }

    private fun packageButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        isEnabled = false
        setOnClickListener { action() }
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun centeredWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
