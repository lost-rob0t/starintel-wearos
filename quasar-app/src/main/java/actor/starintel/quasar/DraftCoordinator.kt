package actor.starintel.quasar

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal interface DraftStorage {
    fun save(name: String, source: String): String
    fun read(key: String): String
}

/** Process-owned, never Activity-owned. Call update on the editor thread after an accepted edit.
 * Receipt means AtomicFile.finishWrite completed. Pending edits are not process-death durable.
 * Failures retain dirty content and are retried only by an explicit flush or a new edit.
 */
internal class DraftCoordinator(
    private val storage: DraftStorage,
    private val debounceMillis: Long = 300,
    private val worker: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "quasar-drafts").apply { isDaemon = true }
    },
) {
    data class Receipt(val key: String, val version: Long)
    private data class Entry(val source: String, val version: Long, var receipt: Receipt?, var failure: Exception? = null)
    private val entries = linkedMapOf<String, Entry>()
    private var version = 0L
    private var memoryBytes = 0
    private var scheduled: ScheduledFuture<*>? = null
    private var flushing: CompletableFuture<Map<String, Receipt>>? = null
    private var reads = 0
    init { require(debounceMillis >= 0); worker.removeOnCancelPolicy = true }

    @Synchronized fun update(name: String, source: CharSequence): Long {
        require(name.length in 1..512)
        DraftLimits.utf8Bytes(source)
        val previous = entries[name]
        if (previous != null && previous.source.contentEquals(source)) return previous.version
        require(previous != null || entries.size < DraftLimits.MAX_ENTRIES) { "Too many open drafts" }
        val projected = memoryBytes - (previous?.source?.length ?: 0) * 2 + source.length * 2
        require(projected <= DraftLimits.MAX_MEMORY_BYTES) { "Open drafts exceed memory limit" }
        val entry = Entry(source.toString(), ++version, previous?.receipt)
        entries[name] = entry
        memoryBytes = projected
        schedule(if (flushing != null) 0 else debounceMillis)
        return entry.version
    }

    @Synchronized fun current(name: String): String? = entries[name]?.source
    @Synchronized fun acknowledged(name: String): Receipt? = entries[name]?.receipt
    @Synchronized fun failure(name: String): Exception? = entries[name]?.failure
    @Synchronized fun isDirty(name: String): Boolean = entries[name]?.let { it.version != it.receipt?.version } ?: false

    /** No waiting or disk work on the caller. Concurrent lifecycle requests share one barrier. */
    @Synchronized fun flush(): CompletableFuture<Map<String, Receipt>> {
        flushing?.let { return it }
        return CompletableFuture<Map<String, Receipt>>().also {
            flushing = it
            schedule(0)
        }
    }

    @Synchronized private fun schedule(delay: Long) {
        scheduled?.cancel(false)
        scheduled = worker.schedule({ persist() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun persist() {
        val snapshot: Map<String, Entry>
        val barrier: CompletableFuture<Map<String, Receipt>>?
        synchronized(this) {
            scheduled = null
            snapshot = entries.filterValues { it.version != it.receipt?.version }.toMap()
            barrier = flushing
            flushing = null
        }
        var failure: Exception? = null
        for ((name, entry) in snapshot) {
            try {
                val receipt = Receipt(storage.save(name, entry.source), entry.version)
                synchronized(this) { entries[name]?.let { it.receipt = receipt; it.failure = null } }
            } catch (error: Exception) {
                failure = error
                synchronized(this) { entries[name]?.failure = error }
            }
        }
        if (failure != null) barrier?.completeExceptionally(failure)
        else barrier?.complete(synchronized(this) { entries.mapNotNull { (name, entry) ->
            entry.receipt?.let { name to it }
        }.toMap() })
    }

    /** Errors propagate, never become an empty draft suitable for accidental submission. */
    @Synchronized fun restore(key: String): CompletableFuture<String> {
        require(reads < DraftLimits.MAX_ENTRIES) { "Too many pending draft reads" }
        reads++
        val result = CompletableFuture<String>()
        worker.execute {
            try {
                val source = storage.read(key)
                DraftLimits.utf8Bytes(source)
                result.complete(source)
            } catch (error: Exception) { result.completeExceptionally(error) }
            finally { synchronized(this) { reads-- } }
        }
        return result
    }

    /** Caller must explicitly discard or first await flush. Never evicts dirty text. */
    @Synchronized fun release(name: String) {
        check(!isDirty(name)) { "Flush or explicitly discard the unsaved draft first" }
        discard(name)
    }
    @Synchronized fun discard(name: String) {
        entries.remove(name)?.let { memoryBytes -= it.source.length * 2 }
    }
}
