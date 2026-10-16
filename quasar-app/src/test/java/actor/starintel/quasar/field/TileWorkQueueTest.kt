package actor.starintel.quasar.field

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TileWorkQueueTest {
    private fun CountDownLatch.awaitTest() = assertTrue("Timed out", await(5, TimeUnit.SECONDS))

    @Test fun rapidPanBoundsPhysicalWorkAndDropsOldQueue() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val latest = CountDownLatch(1)
        val cancelled = AtomicInteger()
        val requests = CopyOnWriteArrayList<String>()
        val queue = TileWorkQueue { url, ticket ->
            requests.add(url)
            if (url.startsWith("old")) {
                ticket.attach { cancelled.incrementAndGet() }
                started.countDown()
                release.await(5, TimeUnit.SECONDS) // transport deliberately slow to cancel
            } else latest.countDown()
        }
        try {
            repeat(2) { queue.request("old$it") }
            started.awaitTest()
            repeat(1_000) { pan ->
                val visible = (0..99).map { "$pan/$it" }.toSet()
                queue.retain(visible)
                visible.forEach(queue::request)
                assertEquals(2 to 32, queue.counts())
            }
            assertEquals(2, requests.size)
            assertEquals(2, cancelled.get())
            queue.retain(setOf("latest"))
            queue.request("latest")
            release.countDown()
            latest.awaitTest()
            assertEquals(setOf("old0", "old1", "latest"), requests.toSet())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun closeCancelsTransportRejectsLatePublicationAndFurtherRequests() {
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val publications = AtomicInteger()
        val aborts = AtomicInteger()
        lateinit var queue: TileWorkQueue
        queue = TileWorkQueue { _, ticket ->
            ticket.attach { aborts.incrementAndGet(); release.countDown() }
            started.countDown()
            try { release.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
            queue.publish(ticket) { publications.incrementAndGet() }
            finished.countDown()
        }
        queue.request("active")
        started.awaitTest()
        queue.close()
        queue.close()
        queue.request("after-close")
        finished.awaitTest()
        assertEquals(1, aborts.get())
        assertEquals(0, publications.get())
        assertEquals(0, queue.counts().second)
    }

    @Test fun obsoleteFailureDoesNotPoisonRetryCache() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failures = TileFailureCache()
        lateinit var queue: TileWorkQueue
        queue = TileWorkQueue { url, ticket ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            queue.publish(ticket) { failures.record(url) }
            finished.countDown()
        }
        try {
            queue.request("obsolete")
            started.awaitTest()
            queue.retain(emptySet())
            release.countDown()
            finished.awaitTest()
            assertFalse(failures.contains("obsolete"))
        } finally { release.countDown(); queue.close() }
    }

    @Test fun cancellationBeforeTransportAttachStillAborts() {
        val ticket = TileWorkQueue.Ticket()
        ticket.cancel()
        val calls = AtomicInteger()
        ticket.attach { calls.incrementAndGet() }
        assertEquals(1, calls.get())
        assertTrue(ticket.cancelled)
    }

    @Test fun duplicateVisibleRequestsAreCoalesced() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queue = TileWorkQueue { _, _ -> started.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            queue.request("same")
            started.awaitTest()
            repeat(100) { queue.retain(setOf("same")); queue.request("same") }
            assertEquals(1 to 0, queue.counts())
        } finally { release.countDown(); queue.close() }
    }

    @Test fun failuresAreBoundedExpireGloballyAndCanRetry() {
        var time = 0L
        val failures = TileFailureCache(now = { time })
        repeat(10_000) { failures.record("$it"); assertTrue(failures.size() <= 256) }
        assertFalse(failures.contains("0"))
        assertTrue(failures.contains("9999"))
        time = 30_000
        assertFalse(failures.contains("never-seen"))
        assertEquals(0, failures.size())
        failures.record("retry")
        assertTrue(failures.contains("retry"))
        failures.remove("retry")
        assertFalse(failures.contains("retry"))
        failures.record("close")
        failures.clear()
        assertEquals(0, failures.size())
    }

    @Test fun dimensionLimitRejectsMalformedAndOversizedBounds() {
        assertFalse(TileDecodeLimits.valid(-1, -1))
        assertFalse(TileDecodeLimits.valid(0, 256))
        assertFalse(TileDecodeLimits.valid(256, Int.MAX_VALUE))
        assertFalse(TileDecodeLimits.valid(1025, 1))
        assertTrue(TileDecodeLimits.valid(256, 256))
        assertTrue(TileDecodeLimits.valid(1024, 1024))
    }
}
