package actor.starintel.android.lisp

import actor.starintel.edge.StarIntelEdgeRuntime
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeRuntimeInstrumentedTest {
    @Test(timeout = 120_000L)
    fun bootsPinnedRuntimeAndRunsLocalSentoActorInsideArt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtimeDirectory = File(context.filesDir, "starintel-edge-instrumented")
        runtimeDirectory.deleteRecursively()
        copyAssetTree(context.assets, "starintel-edge", runtimeDirectory)

        var started = false
        try {
            assertEquals(1, StarIntelEdgeRuntime.abiVersion())
            val failure = StarIntelEdgeRuntime.start(runtimeDirectory.absolutePath)
            assertEquals(failure, null)
            started = true

            val ping = StarIntelEdgeRuntime.request("{\"op\":\"runtime.ping\"}")
            assertTrue(ping, ping.contains("\"status\":\"ok\""))
            assertTrue(ping, ping.contains("\"platform\":\"android\""))

            val actor = StarIntelEdgeRuntime.request("{\"op\":\"actor.roundtrip\"}")
            assertTrue(actor, actor.contains("\"status\":\"ok\""))
            assertTrue(actor, actor.contains("\"message\":\"android-local\""))
        } finally {
            if (started) StarIntelEdgeRuntime.stop()
            runtimeDirectory.deleteRecursively()
        }
    }

    private fun copyAssetTree(
        assets: android.content.res.AssetManager,
        assetPath: String,
        destination: File,
    ) {
        val children = assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            assets.open(assetPath).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        destination.mkdirs()
        children.forEach { child -> copyAssetTree(assets, "$assetPath/$child", File(destination, child)) }
    }
}
