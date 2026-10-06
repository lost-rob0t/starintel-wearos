package actor.starintel.android.lisp

import android.content.Context
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

data class EdgeActor(
    val id: String,
    val name: String,
    val description: String,
)

data class EdgeRuntimeSnapshot(
    val available: Boolean,
    val implementation: String,
    val detail: String,
    val actors: List<EdgeActor>,
)

/**
 * Product-neutral Android owner for the immutable StarIntel Edge image.
 *
 * This class only installs trusted assets and forwards closed operations to the
 * pinned ECL/Sento runtime. Actor definitions and dispatch semantics remain in
 * starintel-edge.
 */
class ManagedEdgeRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val runtimeDirectory = File(appContext.filesDir, RUNTIME_DIRECTORY)
    private val closed = AtomicBoolean(false)
    private var runtime: EclLispRuntime? = null

    fun snapshot(): EdgeRuntimeSnapshot {
        val active = runtime()
        val status = active.status
        if (!status.available) {
            return EdgeRuntimeSnapshot(false, status.implementation, status.detail, emptyList())
        }
        active.request("runtime.ping")
        val catalog = active.request("actor.list").optJSONArray("actors") ?: JSONArray()
        return EdgeRuntimeSnapshot(true, status.implementation, status.detail, parseActorCatalog(catalog))
    }

    fun roundTrip() = runtime().request("actor.roundtrip")

    @Synchronized
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtime?.close()
        runtime = null
    }

    @Synchronized
    private fun runtime(): EclLispRuntime {
        check(!closed.get()) { "StarIntel Edge runtime is closed" }
        runtime?.let { return it }
        installAssets()
        return EclLispRuntime(runtimeDirectory.absolutePath).also { runtime = it }
    }

    private fun installAssets() {
        if (File(runtimeDirectory, "lisp/startup.lisp").isFile) return
        runtimeDirectory.deleteRecursively()
        copyAssetTree("starintel-edge", runtimeDirectory)
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = appContext.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            appContext.assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree("$assetPath/$child", File(destination, child)) }
    }

    companion object {
        private const val RUNTIME_DIRECTORY = "starintel-edge-runtime-abi1-v2"

        internal fun parseActorCatalog(values: JSONArray): List<EdgeActor> = buildList {
            for (index in 0 until values.length().coerceAtMost(64)) {
                val actor = values.optJSONObject(index) ?: continue
                val id = actor.optString("id").takeIf { it.isNotBlank() } ?: continue
                add(
                    EdgeActor(
                        id = id.take(128),
                        name = actor.optString("name").ifBlank { id }.take(160),
                        description = actor.optString("description").take(800),
                    ),
                )
            }
        }
    }
}
