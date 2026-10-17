package actor.starintel.quasar

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Synthetic picker result + real HTTP/Keystore/private-file/activity lifecycle on an emulator. */
class FileImportJourneyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun stageRotateImportAndLeavePartialReceipt() {
        Fixture().use { server ->
            QuasarConfig(context).save(server.origin, "star_sk_v1_import_fixture")
            val input = File(context.cacheDir, "import-fixture.json").apply { writeText("[" + List(12) { "{\"n\":$it}" }.joinToString(",") + "]") }
            val monitor = instrumentation.addMonitor(FileImportActivity::class.java.name, null, false)
            ActivityScenario.launch(MainActivity::class.java).use { main ->
                main.onActivity { activity -> MainActivity::class.java.getDeclaredMethod("showImport").apply { isAccessible = true; invoke(activity) } }
                await {
                    var enabled = false
                    main.onActivity { activity ->
                        val binding = MainActivity::class.java.getDeclaredMethod("getDraftFields").apply { isAccessible = true }.invoke(activity) as WorkspaceDraftFields
                        enabled = binding.ready
                    }; enabled
                }
                main.onActivity {
                    val editor = views(it.window.decorView).filterIsInstance<android.widget.EditText>().single()
                    assertEquals(android.text.InputFilter::class.java, editor.filters.javaClass.componentType)
                    assertTrue(editor.filters.any { filter -> filter is actor.starintel.quasar.importing.Utf8ImportFilter })
                    click(it, "Import JSON / NDJSON file")
                }
                val launched = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertNotNull("Main import route must open the native file destination", launched)
                instrumentation.runOnMainSync { launched.finish() }
            }
            instrumentation.removeMonitor(monitor)
            ActivityScenario.launch(FileImportActivity::class.java).use { scenario ->
                await { !FileImportWork.get(context).snapshot.busy }
                pick(scenario, input)
                await { FileImportWork.get(context).snapshot.canStart }
                assertEquals(0, server.calls.get())
                scenario.recreate(); assertEquals(0, server.calls.get())
                scenario.onActivity { click(it, "Import file") }
                assertTrue(server.arrived.await(5, TimeUnit.SECONDS))
                scenario.recreate(); assertEquals(1, server.calls.get())
                server.release.countDown()
                await { FileImportWork.get(context).snapshot.result?.state == "complete" }
                assertEquals(12, FileImportWork.get(context).snapshot.result!!.verifiedAccepted)
                assertEquals(2, server.calls.get())
                scenario.recreate(); assertEquals(2, server.calls.get())
                input.writeText("[{},{}]"); server.partial = true
                pick(scenario, input); await { FileImportWork.get(context).snapshot.canStart }
                scenario.onActivity { click(it, "Import file") }
                await { FileImportWork.get(context).snapshot.result?.state == "blocked" }
                assertEquals(3, server.calls.get())
                assertEquals(2, FileImportWork.get(context).snapshot.result!!.unresolvedRecords)
                scenario.recreate()
                assertFalse(FileImportWork.get(context).snapshot.canStart)
                assertFalse(FileImportWork.get(context).snapshot.canChoose)
                assertEquals(3, server.calls.get())
            }
        }
    }

    /** Run separately after am force-stop: loading receipt must not need a live fixture/server. */
    @Test fun restartShowsPartialReceiptWithoutMutation() {
        ActivityScenario.launch(FileImportActivity::class.java).use { scenario ->
            await { !FileImportWork.get(context).snapshot.busy }
            val state = FileImportWork.get(context).snapshot
            assertEquals("blocked", state.result!!.state)
            assertEquals("partial-fixture", state.result!!.correlationId)
            assertEquals(2, state.result!!.unresolvedRecords)
            assertFalse(state.canStart); assertFalse(state.canChoose)
            scenario.recreate()
            scenario.onActivity { activity ->
                assertFalse(views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Import file" }.isEnabled)
            }
        }
    }

    private fun pick(scenario: ActivityScenario<FileImportActivity>, file: File) {
        val result = Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file)))
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT), result, true)
        scenario.onActivity { click(it, "Choose JSON / NDJSON file") }
        await { monitor.hits == 1 }
        instrumentation.removeMonitor(monitor)
    }
    private fun click(activity: Activity, text: String) {
        views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == text }.performClick()
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!condition() && SystemClock.uptimeMillis() < end) SystemClock.sleep(25)
        assertTrue("Timed out waiting for import state", condition())
    }
    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0)
        private val worker = Executors.newSingleThreadExecutor()
        val calls = AtomicInteger()
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var partial = false
        val origin = "http://127.0.0.1:${server.localPort}"
        init { worker.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 10000
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1]
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length); var offset = 0
                    while (offset < length) { val count = input.read(body, offset, length - offset); check(count > 0); offset += count }
                    val payload = when (path) {
                        "/auth/context" -> """{"principal_id":"fixture","principal_type":"user"}"""
                        "/api/v1/documents/bulk" -> {
                            val count = JSONArray(String(body)).length()
                            if (calls.incrementAndGet() == 1) { arrived.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
                            if (partial) """{"total":$count,"succeeded":1,"failed":1,"correlation_id":"partial-fixture"}"""
                            else """{"total":$count,"succeeded":$count,"failed":0,"correlation_id":"full-fixture"}"""
                        }
                        "/api/v1/stats" -> """{"data":{"documents":{"total":0}}}"""
                        else -> """{"status":"ok"}"""
                    }
                    val response = payload.toByteArray()
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    it.getOutputStream().write(response)
                }
            }
        } }
        override fun close() { release.countDown(); server.close(); worker.shutdownNow() }
    }
}
