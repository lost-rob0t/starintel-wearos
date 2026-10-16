package actor.starintel.quasar.field

import java.util.concurrent.Executors

/** Two physical workers, at most [capacity] waiting URLs; only occupied slots submit executor jobs.
 * Cancelled workers retain their slot until transport actually returns.
 */
internal class TileWorkQueue(
    private val capacity: Int = 32,
    private val onSettled: () -> Unit = {},
    private val load: (String, Ticket) -> Unit,
) : AutoCloseable {
    internal class Ticket {
        @Volatile var cancelled = false
            private set
        private var abort: (() -> Unit)? = null

        @Synchronized fun attach(action: () -> Unit) {
            if (cancelled) action() else abort = action
        }
        @Synchronized fun cancel() {
            cancelled = true
            abort?.invoke()
            abort = null
        }
        @Synchronized fun detach() { abort = null }
    }

    private val executor = Executors.newFixedThreadPool(2)
    private val pending = linkedSetOf<String>()
    private val active = mutableMapOf<String, Ticket>()
    private var closed = false

    init { require(capacity > 0) }

    @Synchronized fun retain(visible: Set<String>) {
        pending.retainAll(visible)
        active.filterKeys { it !in visible }.values.forEach { it.cancel() }
    }

    @Synchronized fun request(url: String) {
        if (closed || url in active || url in pending || pending.size >= capacity) return
        pending.add(url)
        drain()
    }

    // Used for publication too: close/viewport cancellation cannot race a late result.
    @Synchronized fun publish(ticket: Ticket, action: () -> Unit) {
        if (!closed && !ticket.cancelled) action()
    }

    @Synchronized fun counts(): Pair<Int, Int> = active.size to pending.size

    private fun drain() {
        while (!closed && active.size < 2 && pending.isNotEmpty()) {
            val url = pending.first()
            pending.remove(url)
            val ticket = Ticket()
            active[url] = ticket
            executor.execute {
                try {
                    if (!ticket.cancelled) load(url, ticket)
                } finally {
                    ticket.detach()
                    synchronized(this) {
                        active.remove(url)
                        drain()
                    }
                    // A redraw must see the released slot, including when an
                    // obsolete URL became visible again before cancellation ended.
                    onSettled()
                }
            }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        pending.clear()
        active.values.forEach { it.cancel() }
        executor.shutdownNow()
    }
}

/** Insertion-ordered TTL entries: lookup/write prune expired history globally. */
internal class TileFailureCache(
    private val capacity: Int = 256,
    private val ttlMillis: Long = 30_000,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val entries = linkedMapOf<String, Long>()
    init { require(capacity > 0 && ttlMillis > 0) }
    private fun prune(time: Long) { entries.entries.removeAll { it.value <= time } }
    @Synchronized fun contains(url: String): Boolean {
        prune(now())
        return url in entries
    }
    @Synchronized fun record(url: String) {
        val time = now()
        prune(time)
        entries.remove(url)
        entries[url] = time + ttlMillis
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    @Synchronized fun remove(url: String) { entries.remove(url) }
    @Synchronized fun clear() { entries.clear() }
    @Synchronized fun size(): Int { prune(now()); return entries.size }
}

internal object TileDecodeLimits {
    // Independent of the 1 MiB compressed-body ceiling and 12 MiB bitmap LRU.
    const val MAX_DIMENSION = 1024
    fun valid(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION
}
