package actor.starintel.android.lisp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EclLispRuntimeTest {
    @Test
    fun `projects native startup failure without making a request`() {
        val bridge = RecordingBridge(startResult = NativeStartResult(false, "ECL image missing"))
        val runtime = EclLispRuntime("/runtime", bridge)

        assertFalse(runtime.status.available)
        assertEquals("ECL image missing", runtime.status.detail)
        expectFailure("ECL image missing") { runtime.request("runtime.ping") }
        assertEquals(0, bridge.requests.size)
    }

    @Test
    fun `rejects operations outside the closed API before JNI`() {
        val bridge = RecordingBridge()
        val runtime = EclLispRuntime("/runtime", bridge)

        expectFailure("not exposed") { runtime.request("cl.eval", JSONObject().put("source", "(uiop:run-program \"id\")")) }
        assertTrue(bridge.requests.isEmpty())
    }

    @Test
    fun `projects structured errors and truncates untrusted detail`() {
        val bridge = RecordingBridge(response = JSONObject()
            .put("ok", false)
            .put("error", JSONObject().put("code", "invalid-config").put("detail", "x".repeat(800)))
            .toString())
        val runtime = EclLispRuntime("/runtime", bridge)

        try {
            runtime.request("runtime.reload-init")
            fail("Expected LispRuntimeException")
        } catch (error: LispRuntimeException) {
            assertEquals("invalid-config", error.code)
            assertEquals(400, error.message!!.length)
        }
    }

    @Test
    fun `serializes requests across callers`() {
        val bridge = BlockingBridge()
        val runtime = EclLispRuntime("/runtime", bridge)
        val pool = Executors.newFixedThreadPool(4)
        repeat(8) { pool.submit { runtime.request("runtime.ping") } }
        pool.shutdown()

        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        assertEquals(1, bridge.maximumConcurrent.get())
    }

    @Test
    fun `close is idempotent and blocks later requests`() {
        val bridge = RecordingBridge()
        val runtime = EclLispRuntime("/runtime", bridge)

        runtime.close()
        runtime.close()

        assertEquals(1, bridge.stopCount)
        expectFailure("closed") { runtime.request("runtime.ping") }
    }

    private fun expectFailure(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing $fragment")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains(fragment, ignoreCase = true))
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains(fragment, ignoreCase = true))
        }
    }

    private class RecordingBridge(
        private val startResult: NativeStartResult = NativeStartResult(true, "ready"),
        private val response: String = "{\"ok\":true,\"result\":{}}",
    ) : NativeLispBridge {
        val requests = mutableListOf<String>()
        var stopCount = 0

        override fun start(runtimePath: String) = startResult
        override fun request(request: String): String = response.also { requests += request }
        override fun stop() { stopCount++ }
    }

    private class BlockingBridge : NativeLispBridge {
        val maximumConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override fun start(runtimePath: String) = NativeStartResult(true, "ready")
        override fun request(request: String): String {
            val now = active.incrementAndGet()
            maximumConcurrent.updateAndGet { old -> maxOf(old, now) }
            CountDownLatch(1).await(10, TimeUnit.MILLISECONDS)
            active.decrementAndGet()
            return "{\"ok\":true,\"result\":{}}"
        }
        override fun stop() = Unit
    }
}
