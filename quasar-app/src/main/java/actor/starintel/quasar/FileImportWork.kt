package actor.starintel.quasar

import actor.starintel.quasar.importing.FileImportStore
import actor.starintel.quasar.importing.StreamingImport
import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Process-lifetime worker; no Activity, View or callback queues are retained. */
internal class FileImportWork private constructor(context: Context) {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val cancellation = AtomicBoolean()
    private var store: FileImportStore? = null
    data class Snapshot(
        val busy: Boolean = true,
        val selected: Boolean = false,
        val bytes: Long = 0,
        val result: StreamingImport.Result? = null,
        val message: String = "Opening saved import…",
        val storageFailed: Boolean = false,
    ) {
        val canChoose get() = !busy && !storageFailed && (result == null || FileImportStore.replaceable(result))
        val canStart get() = !busy && !storageFailed && selected && result?.state == "ready"
    }
    @Volatile var snapshot = Snapshot()
        private set

    init {
        executor.execute {
            try {
                store = FileImportStore(File(app.noBackupFilesDir, "file-import-v1"))
                refresh("Choose a JSON array or NDJSON file. Nothing is sent until you press Import.")
            } catch (_: Exception) { snapshot = Snapshot(busy = false, storageFailed = true, message = "Saved import could not be opened. Its files were preserved; no request was sent.") }
        }
    }

    @Synchronized fun choose(uri: Uri) {
        if (!snapshot.canChoose) return
        cancellation.set(false)
        snapshot = snapshot.copy(busy = true, message = "Copying file to private storage…")
        executor.execute {
            try {
                val input = app.contentResolver.openInputStream(uri) ?: error("Cannot open selected file")
                store!!.select(input, cancellation::get)
                refresh("File ready. Review the configured server before importing. Maximum file size: 1 GiB.")
            } catch (_: Exception) { refreshSafely("File could not be copied or exceeds 1 GiB. Previous import preserved; no request sent.") }
        }
    }

    @Synchronized fun start() {
        if (!snapshot.canStart) return
        cancellation.set(false)
        snapshot = snapshot.copy(busy = true, message = "Verifying the configured server and principal…")
        executor.execute {
            try {
                val source = store!!.current() ?: error("No import file")
                val session = StarServerClient(app).openBulkImportSession()
                val result = StreamingImport().run(source, store!!.checkpoint(source), session.destinationIdentity,
                    { bytes -> session.sendBatch(bytes) }, cancellation::get,
                    { receipt -> snapshot = snapshot.copy(result = receipt, message = "Importing one bounded batch at a time…") })
                snapshot = snapshot.copy(busy = false, result = result, message = when (result.state) {
                    "complete" -> "All records received successful server acceptance receipts. Indexing and execution are not verified."
                    "ready" -> "Paused at a verified boundary. Continue only when you are ready."
                    else -> "Outcome unresolved. This batch cannot be retried. Keep the receipt for server reconciliation."
                })
            } catch (_: Exception) {
                refreshSafely("Import stopped: source, destination, or receipt validation failed. Review the saved counts. No automatic retry occurred.")
            }
        }
    }

    fun cancel() { cancellation.set(true) }
    private fun refresh(message: String) {
        val source = store!!.current()
        val receipt = source?.let { StreamingImport.inspect(store!!.checkpoint(it)) }
        val restoredMessage = if (receipt?.state in listOf("blocked", "in-flight"))
            "Saved outcome is unresolved. No retry or replacement is available; keep the receipt for server reconciliation."
        else message
        snapshot = Snapshot(busy = false, selected = source != null, bytes = source?.length() ?: 0,
            result = receipt, message = restoredMessage)
    }
    private fun refreshSafely(message: String) {
        try { refresh(message) }
        catch (_: Exception) { snapshot = snapshot.copy(busy = false, storageFailed = true, message = "$message Saved state needs manual recovery; files preserved.") }
    }
    companion object {
        @Volatile private var instance: FileImportWork? = null
        fun get(context: Context): FileImportWork = instance ?: synchronized(this) {
            instance ?: FileImportWork(context).also { instance = it }
        }
    }
}
