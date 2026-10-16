package actor.starintel.quasar

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native file-import destination. Merely opening/recreating it never starts a mutation. */
class FileImportActivity : Activity() {
    private val work by lazy { FileImportWork.get(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var receipt: TextView
    private lateinit var choose: TextView
    private lateinit var submit: TextView
    private lateinit var stop: TextView
    private var displayed: FileImportWork.Snapshot? = null
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250) // One latest snapshot, never an unbounded progress-event queue.
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        QuasarDesign.configure(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        body.addView(QuasarDesign.eyebrow(this, "INGEST"))
        body.addView(QuasarDesign.title(this, "Import a file"), QuasarDesign.match(top = dp(12)))
        body.addView(QuasarDesign.body(this, "JSON array or NDJSON · up to 1 GiB. Each request has at most 10 records and 256 KiB. Individual records must fit 128 KiB of UTF-8."), QuasarDesign.match(top = dp(12)))
        body.addView(QuasarDesign.body(this, "Server: ${QuasarConfig(this).serverUrl().take(200)}. Import verifies and freezes the server and principal. Use Settings to change them before starting."), QuasarDesign.match(top = dp(12)))
        status = QuasarDesign.status(this, "Opening saved import…")
        receipt = QuasarDesign.body(this, "No acceptance receipt yet.").apply { setTextIsSelectable(true) }
        choose = QuasarDesign.action(this, "Choose JSON / NDJSON file") {
            if (work.snapshot.canChoose) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/x-ndjson", "application/jsonl", "text/plain"))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { startActivityForResult(intent, PICK_FILE) }.onFailure { status.text = "No document picker is available." }
            }
        }
        submit = QuasarDesign.action(this, "Import file", primary = true) { work.start(); render() }
        stop = QuasarDesign.action(this, "Stop after current batch") { work.cancel(); status.text = "Stopping after the outstanding batch has a recorded outcome…" }
        body.addView(choose, QuasarDesign.match(top = dp(18)))
        body.addView(submit, QuasarDesign.match(top = dp(12)))
        body.addView(stop, QuasarDesign.match(top = dp(12)))
        body.addView(status, QuasarDesign.match(top = dp(16)))
        body.addView(receipt, QuasarDesign.match(top = dp(12)))
        body.addView(QuasarDesign.body(this, "Partial or unknown outcomes cannot be retried automatically. Keep this screen's receipt for server reconciliation. Leaving the app does not guarantee background execution."), QuasarDesign.match(top = dp(16)))
        body.addView(QuasarDesign.action(this, "Back to workspace") { finish() }, QuasarDesign.match(top = dp(18)))
        QuasarDesign.install(this, ScrollView(this).apply { addView(body) })
        render()
    }
    override fun onStart() { super.onStart(); handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE && resultCode == RESULT_OK) data?.data?.let(work::choose)
    }
    private fun render() {
        val state = work.snapshot
        if (displayed === state) return
        displayed = state
        choose.isEnabled = state.canChoose
        submit.isEnabled = state.canStart
        stop.isEnabled = state.busy
        submit.text = if ((state.result?.verifiedAccepted ?: 0) > 0) "Continue import" else "Import file"
        status.text = state.message
        val result = state.result
        receipt.text = if (result == null) "No acceptance receipt yet." else buildString {
            append("State: ${result.state}\nServer-accepted records: ${result.verifiedAccepted}")
            append("\nVerified source boundary: ${result.sourceOffset} / ${state.bytes} bytes")
            append("\nUnresolved records: ${result.unresolvedRecords}")
            if (result.lastSucceeded >= 0) append("\nLast batch: ${result.lastSucceeded} succeeded, ${result.lastFailed} failed")
            if (result.correlationId.isNotEmpty()) append("\nReceipt: ${result.correlationId}")
            if (result.jobId.isNotEmpty()) append("\nAsync job: ${result.jobId} (completion not verified)")
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private const val PICK_FILE = 126 }
}
