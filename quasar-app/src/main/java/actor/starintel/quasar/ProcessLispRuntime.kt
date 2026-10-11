package actor.starintel.quasar

import actor.starintel.android.lisp.LispRuntime
import actor.starintel.android.lisp.LispRuntimeStatus
import org.json.JSONObject

/** Android activities are shorter lived than the process-owned embedded ECL runtime. */
internal class ProcessLispRuntime(createRuntime: () -> LispRuntime) {
    private val core by lazy(createRuntime)

    fun openSession(): LispRuntime = object : LispRuntime {
        private var closed = false

        override val status: LispRuntimeStatus
            get() = synchronized(this) {
                if (closed) LispRuntimeStatus(false, "ECL", "Local runtime session is closed")
                else core.status
            }

        @Synchronized
        override fun request(operation: String, arguments: JSONObject): JSONObject {
            check(!closed) { "Local runtime session is closed" }
            return core.request(operation, arguments)
        }

        @Synchronized
        override fun close() {
            // Release this activity's access. Android process death owns native teardown;
            // activity recreation must not shut down and reboot the shared native core.
            closed = true
        }
    }
}
