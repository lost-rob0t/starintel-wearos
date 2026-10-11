package actor.starintel.quasar

import actor.starintel.android.config.OperatorContracts
import actor.starintel.testing.NativeUi as Ui
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

/** Part of the single Quasar journey expected by test-native-journeys.sh. */
internal object MapSyncJourney {
    fun verifyLifecycle() {
        verifyResponse(status = 200, destination = "Settings")
        verifyResponse(status = 503, destination = "recreate")
        verifyResponse(status = 200, destination = "Field")
        verifyResponse(status = 503, destination = "Field")
    }

    private fun verifyResponse(status: Int, destination: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        DelayedSearchServer(status).use { server ->
            // Synthetic local fixture credential; the preceding journey covers authentication.
            QuasarConfig(context).save(server.origin, "star_sk_v1_fixture")
            val intent = Intent(context, MainActivity::class.java)
                .setAction(OperatorContracts.ACTION_OPEN_MAP)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                assertTrue("Map search did not start", server.requested.await(10, TimeUnit.SECONDS))
                val request = Thread.getAllStackTraces().keys.single { it.name == "quasar-geo-search" }
                lateinit var oldStatus: TextView
                scenario.onActivity { activity ->
                    oldStatus = descendants(activity.window.decorView)
                        .filterIsInstance<TextView>().single { it.text.toString() == "SYNCING" }
                }
                when (destination) {
                    "Settings" -> Ui.click("Settings")
                    "recreate" -> scenario.recreate()
                }
                if (destination != "Field") {
                    instrumentation.runOnMainSync { assertFalse(oldStatus.isAttachedToWindow) }
                }
                server.release.countDown()
                request.join(10_000)
                assertFalse("Map request did not finish", request.isAlive)
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    val expected = if (destination != "Field") "SYNCING"
                        else if (status == 200) "0 SYNCED" else "DATA DEGRADED"
                    assertEquals("Late response must not mutate a retired field view", expected, oldStatus.text.toString())
                }
                when (destination) {
                    "Settings" -> Ui.waitText("Credentials are validated")
                    "recreate" -> Ui.waitText("Investigation desk")
                }
                Ui.capture("quasar-map-response-$destination-$status")
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    /** Holds the real HTTP response until the UI has navigated away or recreated. */
    private class DelayedSearchServer(private val status: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        val origin = "http://127.0.0.1:${server.localPort}"
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val worker = Thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(" ")?.get(1) ?: return@use
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume GET headers. */ }
                        val search = path.startsWith("/api/v1/documents/search?")
                        if (search) {
                            requested.countDown()
                            check(release.await(20, TimeUnit.SECONDS)) { "Search response was not released" }
                        }
                        val code = if (search) status else 200
                        val body = if (search && status == 200) "{\"rows\":[]}" else "{}"
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().write(
                            "HTTP/1.1 $code Result\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(),
                        )
                        socket.getOutputStream().write(bytes)
                    }
                } catch (failure: Exception) {
                    if (!server.isClosed) throw failure
                }
            }
        }.apply { name = "quasar-delayed-search-fixture"; start() }

        override fun close() {
            release.countDown()
            server.close()
            worker.join(3_000)
        }
    }
}
