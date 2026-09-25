package actor.starintel.android.lisp

import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

interface LispRuntime : Closeable {
    val status: LispRuntimeStatus

    fun request(operation: String, arguments: JSONObject = JSONObject()): JSONObject
}

data class LispRuntimeStatus(
    val available: Boolean,
    val implementation: String,
    val detail: String,
)

data class NativeStartResult(val available: Boolean, val detail: String)

/** The complete native surface. Intentionally smaller than the application API. */
interface NativeLispBridge {
    fun start(runtimePath: String): NativeStartResult
    fun request(request: String): String
    fun stop()
}

class LispRuntimeException(
    val code: String,
    detail: String,
) : IllegalStateException(detail.take(MAX_ERROR_CHARS)) {
    companion object {
        private const val MAX_ERROR_CHARS = 400
    }
}

class EclLispRuntime internal constructor(
    private val imagePath: String,
    private val bridge: NativeLispBridge,
) : LispRuntime {
    constructor(imagePath: String, nativeLibrary: String = "starintel_lisp") :
        this(imagePath, JniNativeLispBridge(nativeLibrary))

    private val closed = AtomicBoolean(false)
    private val started = runCatching { bridge.start(imagePath) }

    override val status: LispRuntimeStatus
        get() {
            if (closed.get()) return LispRuntimeStatus(false, "ECL", "Embedded Common Lisp runtime is closed")
            val result = started.getOrNull()
            return when {
                started.isFailure -> LispRuntimeStatus(
                    false,
                    "ECL",
                    started.exceptionOrNull()?.message?.take(400) ?: "ECL failed to start",
                )
                result?.available != true -> LispRuntimeStatus(false, "ECL", result?.detail?.take(400) ?: "ECL failed to start")
                else -> LispRuntimeStatus(true, "ECL", result.detail.take(400))
            }
        }

    /** One monitor owns request/response framing: native ECL never receives concurrent calls. */
    @Synchronized
    override fun request(operation: String, arguments: JSONObject): JSONObject {
        check(!closed.get()) { "Lisp runtime is closed" }
        check(status.available) { status.detail }
        require(operation in EXPOSED_OPERATIONS) { "Lisp operation is not exposed by the mobile control plane" }
        val request = JSONObject().put("operation", operation).put("arguments", arguments).toString()
        require(request.utf8Size() <= MAX_REQUEST_BYTES) { "Lisp request exceeds 1 MiB" }
        val response = bridge.request(request)
        require(response.utf8Size() <= MAX_RESPONSE_BYTES) { "Lisp response exceeds 2 MiB" }
        val root = runCatching { JSONObject(response) }
            .getOrElse { throw LispRuntimeException("invalid-response", "Embedded Lisp returned malformed JSON") }
        if (!root.optBoolean("ok", false)) throw projectedError(root.opt("error"))
        return root.optJSONObject("result") ?: JSONObject()
    }

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (started.getOrNull()?.available == true) runCatching { bridge.stop() }
    }

    private fun projectedError(raw: Any?): LispRuntimeException {
        val error = raw as? JSONObject
        return LispRuntimeException(
            code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: "runtime-error",
            detail = error?.optString("detail")?.takeIf { it.isNotBlank() }
                ?: raw?.toString()?.takeIf { it.isNotBlank() }
                ?: "Embedded Lisp operation failed",
        )
    }

    private fun String.utf8Size() = toByteArray(StandardCharsets.UTF_8).size

    companion object {
        private const val MAX_REQUEST_BYTES = 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        /** No eval, load, arbitrary symbol invocation, environment, or filesystem operation. */
        val EXPOSED_OPERATIONS: Set<String> = setOf(
            "runtime.ping",
            "runtime.reload-init",
            "tek9.open",
            "tek9.close",
            "tek9.document",
            "tek9.search",
            "tek9.transaction",
            "actor.dispatch",
        )
    }
}

private class JniNativeLispBridge(nativeLibrary: String) : NativeLispBridge {
    private val loaded = runCatching { System.loadLibrary(nativeLibrary) }

    override fun start(runtimePath: String): NativeStartResult {
        if (loaded.isFailure) return NativeStartResult(false, "Native ECL bridge is not packaged for this ABI")
        val detail = nativeStart(runtimePath).take(400)
        return NativeStartResult(
            available = detail == "ready",
            detail = if (detail == "ready") "Embedded Common Lisp runtime ready" else detail,
        )
    }

    override fun request(request: String): String = nativeRequest(request)
    override fun stop() = nativeStop()

    private external fun nativeStart(runtimePath: String): String
    private external fun nativeRequest(request: String): String
    private external fun nativeStop()
}
