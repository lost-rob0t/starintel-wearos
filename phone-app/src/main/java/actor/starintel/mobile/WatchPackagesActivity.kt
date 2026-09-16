package actor.starintel.mobile

import actor.starintel.update.PackageTransferProtocol
import actor.starintel.update.UpdateArtifact
import actor.starintel.update.UpdateFeed
import actor.starintel.update.UpdateManifest
import actor.starintel.update.UpdateSources
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private lateinit var overall: ProgressBar
    private lateinit var items: LinearLayout
    private lateinit var neon: Button
    private lateinit var command: Button
    private lateinit var terminal: Button
    private lateinit var wear: Button
    private lateinit var all: Button
    private lateinit var cancel: Button

    private val prefs by lazy { getSharedPreferences(UpdateManagerActivity.PREFS, MODE_PRIVATE) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(this) }
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val transferClient by lazy { PackageTransferClient(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var node: Node? = null
    private var manifest: UpdateManifest? = null
    private var busy = false
    private var activeTransferId: String? = null
    private var activeArtifact: UpdateArtifact? = null
    private var completed = 0
    private var total = 0
    private var recoveryQueries = 0
    private val attempts = mutableMapOf<String, Int>()
    private val failures = mutableListOf<String>()
    private val queue = ArrayDeque<UpdateArtifact>()
    private val rows = linkedMapOf<String, PackageProgressRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(36))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(eyebrow("STARINTEL · COMPANION"), matchWrap())
        root.addView(title("Install on watch"), matchWrap(top = 3))
        root.addView(body("Choose one package or run the full queue. Every package shows download, Bluetooth transfer, verification, approval, and install progress."), matchWrap(top = 8))

        receiverStatus = statusCard("Checking watch transport…")
        root.addView(receiverStatus, matchWrap(top = 22))
        sourceStatus = TextView(this).apply { textSize = 13f; setTextColor(CYAN) }
        root.addView(sourceStatus, matchWrap(top = 8))
        root.addView(actionButton("REFRESH WATCH + CATALOG") { refreshAll() }, matchWrap(top = 12))

        neon = actionButton("INSTALL NEON") { installIds(listOf("watchface-neon")) }
        command = actionButton("INSTALL COMMAND") { installIds(listOf("watchface-command")) }
        terminal = actionButton("INSTALL TERMINAL") { installIds(listOf("watchface-terminal")) }
        wear = actionButton("UPDATE STARINTEL WEAR") { installIds(listOf("wear")) }
        all = actionButton("INSTALL / UPDATE ALL") { installAll() }
        root.addView(neon, matchWrap(top = 20))
        root.addView(command, matchWrap(top = 6))
        root.addView(terminal, matchWrap(top = 6))
        root.addView(wear, matchWrap(top = 6))
        root.addView(all, matchWrap(top = 12))

        overall = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 1_000
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
        }
        root.addView(overall, matchWrap(top = 18))
        items = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(items, matchWrap(top = 8))

        status = statusCard("Waiting for watch and catalog.").apply { gravity = Gravity.CENTER }
        root.addView(status, matchWrap(top = 12))
        cancel = actionButton("CANCEL QUEUE") { cancelQueue() }.apply { visibility = View.GONE }
        root.addView(cancel, matchWrap(top = 8))
        root.addView(body("Keep the watch awake while approval is requested. If an acknowledgement is lost, Companion asks the watch for its saved state, retries once, then continues the remaining queue with a failure summary."), matchWrap(top = 18))

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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != PackageTransferProtocol.STATUS_PATH) return
        val expected = activeTransferId ?: return
        if (messageEvent.sourceNodeId != node?.id) return
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
                    receiverStatus.text = "OFFLINE\nPackage receiver not reachable"
                    receiverStatus.setTextColor(WARNING)
                    status.text = "Bootstrap StarIntel Wear once, reconnect the watch, then refresh."
                } else {
                    receiverStatus.text = if (found.isNearby) {
                        "READY · BLUETOOTH\n${found.displayName} is nearby"
                    } else {
                        "READY · REMOTE\n${found.displayName} is reachable; Bluetooth is not confirmed"
                    }
                    receiverStatus.setTextColor(if (found.isNearby) CYAN else AMBER)
                    setButtonsEnabled(manifest != null && !busy)
                }
            }
            .addOnFailureListener {
                receiverStatus.text = "TRANSPORT ERROR\nWear OS Data Layer unavailable"
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
                    if (node != null) status.text = "Ready. Choose a package or install the full queue."
                } }
                .onFailure { error -> runOnUiThread {
                    sourceStatus.text = "SOURCE · ${UpdateSources.label(source)} · unavailable"
                    showError("Catalog failed: ${error.message}")
                } }
        }.start()
    }

    private fun installIds(ids: List<String>) {
        val value = manifest ?: return
        startQueue(ids.map { value.artifact(it) })
    }

    private fun installAll() {
        manifest?.wearArtifacts()?.let(::startQueue)
    }

    private fun startQueue(selected: List<UpdateArtifact>) {
        if (busy || selected.isEmpty()) return
        if (node == null) return showError("Watch package receiver is not reachable.")
        val ordered = selected.sortedWith(compareBy<UpdateArtifact> { it.installOrder }.thenBy { it.id })
        queue.clear()
        queue.addAll(ordered)
        rows.clear()
        items.removeAllViews()
        ordered.forEach { artifact ->
            val row = PackageProgressRow(artifact)
            rows[artifact.id] = row
            items.addView(row.root, matchWrap(top = 6))
        }
        failures.clear()
        attempts.clear()
        completed = 0
        total = ordered.size
        busy = true
        overall.visibility = View.VISIBLE
        overall.progress = 0
        cancel.visibility = View.VISIBLE
        setButtonsEnabled(false)
        status.setTextColor(MUTED)
        sendNext()
    }

    private fun sendNext() {
        cancelWatchdog()
        activeTransferId = null
        activeArtifact = queue.pollFirst()
        val artifact = activeArtifact
        if (artifact == null) {
            val summary = if (failures.isEmpty()) {
                "All $total selected packages installed."
            } else {
                "Installed ${total - failures.size} of $total. Failed: ${failures.joinToString()}."
            }
            finishQueue(failures.isEmpty(), summary)
            return
        }
        updateRow(artifact.id, InstallStage.DOWNLOADING, 0, "Downloading + verifying")
        renderOverall()
        Thread {
            runCatching { UpdateFeed.download(this, artifact) }
                .onSuccess { apk -> runOnUiThread {
                    if (busy && activeArtifact?.id == artifact.id) {
                        val target = node
                        val value = manifest
                        when {
                            target == null -> failCurrent("Watch disconnected")
                            value == null -> failCurrent("Catalog unavailable")
                            else -> {
                                val transferId = transferClient.newTransferId()
                                activeTransferId = transferId
                                recoveryQueries = 0
                                updateRow(artifact.id, InstallStage.TRANSFERRING, 0, "Sending over Wear Data Layer")
                                armWatchdog(TRANSFER_TIMEOUT_MS)
                                transferClient.send(
                                    transferId = transferId,
                                    nodeId = target.id,
                                    artifact = artifact,
                                    versionCode = value.versionCode,
                                    apk = apk,
                                    onProgress = { percent ->
                                        if (activeTransferId == transferId) {
                                            updateRow(artifact.id, InstallStage.TRANSFERRING, percent, "$percent% transferred")
                                            renderOverall()
                                            armWatchdog(TRANSFER_TIMEOUT_MS)
                                        }
                                    },
                                    onReady = { id ->
                                        if (activeTransferId == id) {
                                            updateRow(artifact.id, InstallStage.VERIFYING, -1, "Watch received stream; verifying")
                                            armWatchdog(VERIFY_TIMEOUT_MS)
                                        }
                                    },
                                    onFailure = { id, error ->
                                        if (activeTransferId == id) retryOrFail("Transfer failed: ${error.message}")
                                    },
                                )
                            }
                        }
                    }
                } }
                .onFailure { error -> runOnUiThread { failCurrent("Download failed: ${error.message}") } }
        }.start()
    }

    private fun handleStatus(update: PackageTransferProtocol.Status) {
        val artifact = activeArtifact ?: return
        recoveryQueries = 0
        when (update.state) {
            PackageTransferProtocol.Status.RECEIVING -> {
                updateRow(artifact.id, InstallStage.TRANSFERRING, rows[artifact.id]?.progress ?: 0, "Watch is receiving")
                armWatchdog(TRANSFER_TIMEOUT_MS)
            }
            PackageTransferProtocol.Status.VERIFYING -> {
                updateRow(artifact.id, InstallStage.VERIFYING, -1, "Checksum + package verification")
                armWatchdog(VERIFY_TIMEOUT_MS)
            }
            PackageTransferProtocol.Status.WAITING_USER -> {
                updateRow(artifact.id, InstallStage.WAITING_USER, -1, update.detail ?: "Approve on watch")
                armWatchdog(APPROVAL_TIMEOUT_MS)
            }
            PackageTransferProtocol.Status.INSTALLING -> {
                updateRow(artifact.id, InstallStage.INSTALLING, -1, "Installing on watch")
                armWatchdog(INSTALL_TIMEOUT_MS)
            }
            PackageTransferProtocol.Status.SUCCESS -> {
                cancelWatchdog()
                updateRow(artifact.id, InstallStage.INSTALLED, 100, "Installed")
                activeTransferId = null
                completed += 1
                renderOverall()
                handler.postDelayed({ sendNext() }, NEXT_ITEM_DELAY_MS)
            }
            PackageTransferProtocol.Status.FAILURE -> failCurrent(update.detail ?: "Watch rejected package")
        }
    }

    private fun armWatchdog(delay: Long) {
        cancelWatchdog()
        handler.postDelayed(watchdog, delay)
    }

    private fun cancelWatchdog() {
        handler.removeCallbacks(watchdog)
    }

    private val watchdog = Runnable {
        val transferId = activeTransferId ?: return@Runnable
        val target = node ?: return@Runnable retryOrFail("Watch disconnected")
        val artifact = activeArtifact ?: return@Runnable
        if (recoveryQueries == 0) {
            recoveryQueries = 1
            updateRow(artifact.id, InstallStage.RECOVERING, -1, "No update; asking watch for saved state")
            messageClient.sendMessage(
                target.id,
                PackageTransferProtocol.STATUS_QUERY_PATH,
                PackageTransferProtocol.statusQuery(transferId),
            )
            armWatchdog(STATUS_QUERY_TIMEOUT_MS)
        } else {
            retryOrFail("Watch stopped acknowledging this package")
        }
    }

    private fun retryOrFail(message: String) {
        val artifact = activeArtifact ?: return
        val count = attempts.getOrDefault(artifact.id, 0)
        if (count < MAX_RETRIES) {
            attempts[artifact.id] = count + 1
            cancelWatchdog()
            activeTransferId = null
            updateRow(artifact.id, InstallStage.RECOVERING, -1, "$message · retrying once")
            queue.addFirst(artifact)
            activeArtifact = null
            handler.postDelayed({ sendNext() }, RETRY_DELAY_MS)
        } else {
            failCurrent("$message · retry exhausted")
        }
    }

    private fun failCurrent(message: String) {
        val artifact = activeArtifact ?: return finishQueue(false, message)
        cancelWatchdog()
        activeTransferId = null
        updateRow(artifact.id, InstallStage.FAILED, 100, message)
        failures += artifact.id
        completed += 1
        renderOverall()
        activeArtifact = null
        handler.postDelayed({ sendNext() }, NEXT_ITEM_DELAY_MS)
    }

    private fun cancelQueue() {
        if (!busy) return
        cancelWatchdog()
        queue.forEach { updateRow(it.id, InstallStage.CANCELLED, 0, "Cancelled") }
        finishQueue(false, "Queue cancelled. The active Android package install may still finish on the watch.")
    }

    private fun finishQueue(ok: Boolean, message: String) {
        busy = false
        activeTransferId = null
        activeArtifact = null
        queue.clear()
        cancelWatchdog()
        cancel.visibility = View.GONE
        overall.progress = if (total == 0) 0 else 1_000
        status.text = message
        status.setTextColor(if (ok) CYAN else WARNING)
        setButtonsEnabled(node != null && manifest != null)
    }

    private fun updateRow(id: String, stage: InstallStage, progress: Int, detail: String) {
        rows[id]?.render(stage, progress, detail)
        status.text = "${labelFor(id)} · $detail"
        status.setTextColor(if (stage == InstallStage.WAITING_USER) AMBER else if (stage == InstallStage.FAILED) WARNING else MUTED)
    }

    private fun renderOverall() {
        val activeProgress = rows[activeArtifact?.id]?.progress?.coerceIn(0, 100) ?: 0
        overall.progress = if (total == 0) 0 else (((completed * 100 + activeProgress) * 1_000) / (total * 100))
    }

    private fun showError(message: String) {
        status.text = message
        status.setTextColor(WARNING)
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        if (!::neon.isInitialized) return
        listOf(neon, command, terminal, wear, all).forEach { it.isEnabled = enabled }
    }

    private fun labelFor(id: String): String = id.removePrefix("watchface-").replace('-', ' ').uppercase()

    private inner class PackageProgressRow(artifact: UpdateArtifact) {
        var progress: Int = 0
            private set
        private val state = TextView(this@WatchPackagesActivity).apply { textSize = 12f; setTextColor(MUTED) }
        private val bar = ProgressBar(this@WatchPackagesActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(CYAN)
        }
        val root = LinearLayout(this@WatchPackagesActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(CARD, dp(14), STROKE)
            addView(TextView(this@WatchPackagesActivity).apply {
                text = labelFor(artifact.id)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            })
            addView(state, matchWrap(top = 3))
            addView(bar, matchWrap(top = 7))
        }

        init { render(InstallStage.QUEUED, 0, "Queued") }

        fun render(stage: InstallStage, value: Int, detail: String) {
            progress = if (value < 0) progress else value.coerceIn(0, 100)
            state.text = "${stage.label} · $detail"
            state.setTextColor(when (stage) {
                InstallStage.INSTALLED -> CYAN
                InstallStage.WAITING_USER, InstallStage.RECOVERING -> AMBER
                InstallStage.FAILED, InstallStage.CANCELLED -> WARNING
                else -> MUTED
            })
            bar.isIndeterminate = value < 0
            if (value >= 0) bar.progress = progress
            bar.progressTintList = android.content.res.ColorStateList.valueOf(
                when (stage) {
                    InstallStage.FAILED, InstallStage.CANCELLED -> WARNING
                    InstallStage.WAITING_USER, InstallStage.RECOVERING -> AMBER
                    else -> CYAN
                },
            )
        }
    }

    private enum class InstallStage(val label: String) {
        QUEUED("QUEUED"), DOWNLOADING("DOWNLOAD"), TRANSFERRING("TRANSFER"), VERIFYING("VERIFY"),
        WAITING_USER("ACTION NEEDED"), INSTALLING("INSTALL"), RECOVERING("RECOVER"),
        INSTALLED("DONE"), FAILED("FAILED"), CANCELLED("CANCELLED"),
    }

    private fun eyebrow(value: String) = TextView(this).apply {
        text = value; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = .1f; setTextColor(CYAN)
    }
    private fun title(value: String) = TextView(this).apply {
        text = value; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
    }
    private fun body(value: String) = TextView(this).apply {
        text = value; textSize = 14f; setTextColor(MUTED); setLineSpacing(0f, 1.18f)
    }
    private fun statusCard(value: String) = body(value).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(CARD, dp(14), STROKE)
    }
    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(CARD)
        setOnClickListener { action() }
    }
    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke)
    }
    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_RETRIES = 1
        private const val TRANSFER_TIMEOUT_MS = 45_000L
        private const val VERIFY_TIMEOUT_MS = 45_000L
        private const val APPROVAL_TIMEOUT_MS = 5 * 60_000L
        private const val INSTALL_TIMEOUT_MS = 2 * 60_000L
        private const val STATUS_QUERY_TIMEOUT_MS = 15_000L
        private const val RETRY_DELAY_MS = 1_200L
        private const val NEXT_ITEM_DELAY_MS = 700L
        private val BACKGROUND = Color.BLACK
        private val CARD = Color.rgb(12, 16, 22)
        private val STROKE = Color.rgb(45, 59, 72)
        private val CYAN = Color.rgb(0, 229, 255)
        private val AMBER = Color.rgb(255, 190, 70)
        private val MUTED = Color.rgb(183, 194, 207)
        private val WARNING = Color.rgb(255, 112, 120)
    }
}
