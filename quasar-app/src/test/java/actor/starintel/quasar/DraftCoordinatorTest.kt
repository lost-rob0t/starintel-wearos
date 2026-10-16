package actor.starintel.quasar

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException

class DraftCoordinatorTest {
    private class Store : DraftStorage {
        var writes = 0
        var fail = false
        val saved = linkedMapOf<String, String>()
        override fun save(name: String, source: String): String {
            if (fail) error("interrupted")
            writes++; saved[name] = source; return name
        }
        override fun read(key: String) = saved[key] ?: error("missing/corrupt")
    }
    @Test fun unchangedNavigationAndRepeatedFlushWriteOnce() {
        val store = Store(); val drafts = DraftCoordinator(store, 60_000)
        val version = drafts.update("route:0", "a".repeat(20_000))
        repeat(100) { assertEquals(version, drafts.update("route:0", "a".repeat(20_000))) }
        drafts.flush().get(2, TimeUnit.SECONDS)
        repeat(10) { drafts.flush().get(2, TimeUnit.SECONDS) }
        assertEquals(1, store.writes)
        assertFalse(drafts.isDirty("route:0"))
        assertEquals(version, drafts.acknowledged("route:0")!!.version)
    }
    @Test fun rapidEditsCoalesceAndRestoreUsesWorker() {
        val store = Store(); val drafts = DraftCoordinator(store, 60_000)
        repeat(100) { drafts.update("a", "$it") }
        drafts.flush().get(2, TimeUnit.SECONDS)
        assertEquals(1, store.writes)
        assertEquals("99", drafts.restore("a").get(2, TimeUnit.SECONDS))
    }
    @Test fun failedWriteKeepsAcknowledgedReferenceAndDirtyText() {
        val store = Store(); val drafts = DraftCoordinator(store, 60_000)
        drafts.update("a", "old"); drafts.flush().get(2, TimeUnit.SECONDS)
        val acknowledged = drafts.acknowledged("a")
        store.fail = true; drafts.update("a", "new")
        assertThrows(ExecutionException::class.java) { drafts.flush().get(2, TimeUnit.SECONDS) }
        assertEquals(acknowledged, drafts.acknowledged("a"))
        assertEquals("new", drafts.current("a")); assertTrue(drafts.isDirty("a"))
        assertEquals("old", store.saved["a"])
        assertThrows(IllegalStateException::class.java) { drafts.release("a") }
        store.fail = false; drafts.flush().get(2, TimeUnit.SECONDS)
        drafts.release("a"); assertNull(drafts.current("a"))
    }
    @Test fun updateDuringInFlightWriteIsNotAcknowledgedAsSaved() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val store = object : DraftStorage {
            override fun save(name: String, source: String): String {
                entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); return name
            }
            override fun read(key: String) = ""
        }
        val drafts = DraftCoordinator(store, 60_000)
        val old = drafts.update("a", "old"); val flush = drafts.flush()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        drafts.update("a", "new"); release.countDown(); flush.get(2, TimeUnit.SECONDS)
        assertEquals(old, drafts.acknowledged("a")!!.version)
        assertTrue(drafts.isDirty("a"))
        drafts.flush().get(2, TimeUnit.SECONDS); assertFalse(drafts.isDirty("a"))
    }
    @Test fun oversizedAdmissionDoesNotCopyOrReplacePriorText() {
        val drafts = DraftCoordinator(Store(), 60_000); drafts.update("a", "old")
        val huge = object : CharSequence {
            override val length = Int.MAX_VALUE
            override fun get(index: Int): Char = error("must reject length first")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("no copy")
            override fun toString(): String = error("no copy")
        }
        assertThrows(IllegalArgumentException::class.java) { drafts.update("a", huge) }
        assertEquals("old", drafts.current("a"))
        assertThrows(IllegalArgumentException::class.java) { drafts.update("a", "€".repeat(90_000)) }
    }
    @Test fun aggregateMemoryAndEntryCountAreBounded() {
        val drafts = DraftCoordinator(Store(), 60_000)
        repeat(4) { drafts.update("$it", "a".repeat(DraftLimits.MAX_BYTES)) }
        assertThrows(IllegalArgumentException::class.java) { drafts.update("extra", "a") }
        drafts.discard("0"); drafts.update("extra", "a")
        val small = DraftCoordinator(Store(), 60_000)
        repeat(32) { small.update("$it", "") }
        assertThrows(IllegalArgumentException::class.java) { small.update("extra", "") }
    }
    @Test fun missingRestoreFailsRatherThanReturningEmptyContent() {
        assertThrows(ExecutionException::class.java) {
            DraftCoordinator(Store()).restore("missing").get(2, TimeUnit.SECONDS)
        }
    }
    @Test fun replacementChecksJoinedSurrogatesAndSelectionWithoutCopying() {
        val prefix = "a".repeat(DraftLimits.MAX_BYTES - 3)
        val dest = prefix + "\uDC00"
        assertFalse(DraftLimits.replacementFits("\uD800", 0, 1, dest, prefix.length, prefix.length))
        assertTrue(DraftLimits.replacementFits("ok", 0, 2, "a".repeat(DraftLimits.MAX_BYTES), 0, DraftLimits.MAX_BYTES))
        assertFalse(DraftLimits.replacementFits("€".repeat(90_000), 0, 90_000, "old", 0, 3))
    }
    @Test fun utf8CountMatchesEncoderIncludingMalformedSurrogates() {
        listOf("ASCII", "€", "é", "😀", "\uD800", "\uDC00", "\uD800x").forEach {
            assertEquals(it.toByteArray(Charsets.UTF_8).size, DraftLimits.utf8Bytes(it))
        }
    }
}
