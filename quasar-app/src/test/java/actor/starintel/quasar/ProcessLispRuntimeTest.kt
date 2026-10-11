package actor.starintel.quasar

import actor.starintel.android.lisp.LispRuntime
import actor.starintel.android.lisp.LispRuntimeStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProcessLispRuntimeTest {
    @Test fun reopeningActivityDoesNotRebootOrStopNativeCore() {
        val core = FakeRuntime()
        var starts = 0
        val owner = ProcessLispRuntime { starts++; core }
        repeat(3) {
            owner.openSession().use { session ->
                assertTrue(session.status.available)
                assertEquals("runtime.ping", session.request("runtime.ping").getString("op"))
            }
        }
        assertEquals(1, starts)
        assertEquals(0, core.stops)
        assertEquals(3, core.requests)
    }

    @Test fun closingOneActivityLeavesAnotherUsable() {
        val core = FakeRuntime()
        val owner = ProcessLispRuntime { core }
        val first = owner.openSession()
        val second = owner.openSession()
        first.close()
        first.close()
        assertFalse(first.status.available)
        assertThrows(IllegalStateException::class.java) { first.request("runtime.ping") }
        assertTrue(second.status.available)
        second.request("runtime.ping")
        assertEquals(1, core.requests)
        assertEquals(0, core.stops)
    }

    @Test fun closingUnusedSessionDoesNotStartNativeCore() {
        var starts = 0
        val owner = ProcessLispRuntime { starts++; FakeRuntime() }
        val session = owner.openSession()
        session.close()
        assertFalse(session.status.available)
        assertEquals(0, starts)
    }

    private class FakeRuntime : LispRuntime {
        var stops = 0
        var requests = 0
        override val status = LispRuntimeStatus(true, "ECL", "Test core")
        override fun request(operation: String, arguments: JSONObject): JSONObject {
            requests++
            return JSONObject().put("op", operation)
        }
        override fun close() { stops++ }
    }
}
