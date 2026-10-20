package actor.starintel.android.api

import java.util.BitSet

/** UI-thread confined, three-page window. Older pages are evicted; Restart returns to page one.
 * Request identity rejects late callbacks and double completion. Failed loads never change current.
 */
class SearchPager(val capacity: Int = 3) {
    init { require(capacity in 1..3) }
    class Request internal constructor(val generation: Long, val query: String, val cursor: String?, val number: Long)
    data class Entry(val query: String, val cursor: String?, val number: Long, val page: SearchPage)
    private val pages = ArrayList<Entry>()
    private var position = -1
    private var generation = 0L
    private var pending: Request? = null
    // Fixed-size cycle guard, independent of corpus size. A collision stops traversal safely.
    private val seen = BitSet(8 * 1024 * 1024)
    val current: Entry? get() = pages.getOrNull(position)
    val cachedPages: Int get() = pages.size
    val canPrevious: Boolean get() = position > 0
    val canNext: Boolean get() = current?.page?.let { it.nextCursor != null && it.hasMore != false } == true

    fun start(query: String, cursor: String? = null, number: Long = 1): Request {
        require(query.isNotBlank() && query.length <= 512 && number >= 1)
        SearchPage.validateCursor(cursor)
        return Request(++generation, query, cursor, number).also { pending = it }
    }
    fun next(): Request? {
        val entry = current ?: return null
        if (!canNext || pending != null) return null
        return start(entry.query, entry.page.nextCursor, entry.number + 1)
    }
    fun previous(): Entry? {
        if (!canPrevious) return null
        cancel()
        position--
        return current
    }
    fun forwardCached(): Entry? {
        if (position + 1 >= pages.size) return null
        cancel()
        position++
        return current
    }
    fun cancel() { pending = null; generation++ }
    fun fail(request: Request): Boolean {
        if (pending != request) return false
        pending = null
        return true
    }
    fun complete(request: Request, page: SearchPage): Boolean {
        if (pending != request) return false
        val advancing = current?.let { request.query == it.query && request.number == it.number + 1 && request.cursor == it.page.nextCursor } == true
        SearchPage.validateCursor(page.nextCursor)
        require(page.rows.length() <= 100) { "Page exceeded row bound" }
        if (page.nextCursor != null) {
            require(page.nextCursor != request.cursor) { "Search bookmark did not advance" }
            require(!advancing || !slots(page.nextCursor).all { seen[it] }) { "Repeated bookmark or cycle guard collision; restart search" }
        }
        if (!advancing) { pages.clear(); position = -1; seen.clear() }
        else while (pages.size > position + 1) pages.removeAt(pages.lastIndex)
        request.cursor?.let { slots(it).forEach(seen::set) }
        page.nextCursor?.let { slots(it).forEach(seen::set) }
        pages.add(Entry(request.query, request.cursor, request.number, page))
        while (pages.size > capacity) pages.removeAt(0)
        position = pages.lastIndex
        pending = null
        return true
    }
    private fun slots(cursor: String): List<Int> {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(cursor.toByteArray(Charsets.UTF_8))
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return List(3) { buffer.int and (8 * 1024 * 1024 - 1) }
    }
}
