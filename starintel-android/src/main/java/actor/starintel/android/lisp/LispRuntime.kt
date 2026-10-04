package actor.starintel.android.lisp

import actor.starintel.edge.StarIntelEdgeRuntime
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
    constructor(imagePath: String) : this(imagePath, EdgeNativeLispBridge)

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
        val request = JSONObject()
            .put("op", operation)
            .put("payload", arguments.toString())
            .toString()
        require(request.utf8Size() <= MAX_REQUEST_BYTES) { "Lisp request exceeds 1 MiB" }
        val response = bridge.request(request)
        require(response.utf8Size() <= MAX_RESPONSE_BYTES) { "Lisp response exceeds 2 MiB" }
        val root = runCatching { JSONObject(response) }
            .getOrElse { throw LispRuntimeException("invalid-response", "Embedded Lisp returned malformed JSON") }
        if (root.has("ok")) {
            if (!root.optBoolean("ok", false)) throw projectedError(root.opt("error"))
            return root.optJSONObject("result") ?: JSONObject()
        }
        if (root.optString("status") == "error") {
            throw LispRuntimeException(
                root.optString("reason", "runtime-error"),
                root.optString("reason", "Embedded runtime request failed"),
            )
        }
        return root
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
            "actor.roundtrip",
            "actor.list",
            "actor.dispatch",
            "status",
            "start",
            "suspend",
            "resume",
            "stop",
            "dispatch",
        )
    }
}

private object EdgeNativeLispBridge : NativeLispBridge {
    override fun start(runtimePath: String): NativeStartResult {
        return runCatching {
            check(StarIntelEdgeRuntime.abiVersion() == 1) { "Unsupported StarIntel Edge adapter ABI" }
            val failure = StarIntelEdgeRuntime.start(runtimePath)
            NativeStartResult(
                available = failure == null,
                detail = failure?.take(400) ?: "StarIntel Edge ECL/Sento runtime ready",
            )
        }.getOrElse { failure ->
            NativeStartResult(false, failure.message?.take(400) ?: "StarIntel Edge runtime unavailable")
        }
    }

    override fun request(request: String): String = StarIntelEdgeRuntime.request(request)
    override fun stop() = StarIntelEdgeRuntime.stop()
}
