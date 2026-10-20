package actor.starintel.quasar

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** A server can commit the POST before returning malformed JSON with a successful status. */
class MutationReceiptJourneyTest {
    @Test fun malformedSuccessfulCreateAndPasteResponsesCannotReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mutations = AtomicInteger()
        val server = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 10000
                    val input = it.getInputStream().bufferedReader()
                    val request = input.readLine()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { check(input.read() >= 0) } // Fixture input is ASCII.
                    val post = request.startsWith("POST /api/v1/documents")
                    if (post) mutations.incrementAndGet() // Model a committed mutation before a broken receipt.
                    val response = if (post) "malformed-successful-receipt" else "{}"
                    it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray())
                }
            }
        }
        try {
            QuasarConfig(context).save("http://127.0.0.1:${server.localPort}", "star_sk_v1_receipt_fixture")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for ((index, route) in listOf(Triple("showCreateDocument", "create", "Create document"), Triple("showImport", "import", "Import documents")).withIndex()) {
                    scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod(route.first).apply { isAccessible = true; invoke(activity) } }
                    await { var ready = false; scenario.onActivity { ready = binding(it).ready }; ready }
                    scenario.onActivity { activity ->
                        fields(activity.window.decorView).single().setText("not-json")
                        button(activity, route.third).performClick()
                    }
                    await { var unsent = false; scenario.onActivity { unsent = !state(it, route.second).getBoolean("attempted") }; unsent }
                    assertEquals(index, mutations.get())
                    scenario.onActivity { activity ->
                        fields(activity.window.decorView).single().setText(if (route.second == "create") "{}" else "[{}]")
                        button(activity, route.third).performClick()
                    }
                    await { var unknown = false; scenario.onActivity {
                        val saved = state(it, route.second)
                        unknown = saved.getBoolean("attempted") && saved.getString("receipt").orEmpty().contains("Submission outcome unknown")
                    }; unknown }
                    assertEquals(index + 1, mutations.get())
                    scenario.onActivity { button(it, route.third).performClick() }
                    scenario.recreate()
                    scenario.onActivity {
                        assertTrue(state(it, route.second).getBoolean("attempted"))
                        assertFalse(button(it, route.third).isEnabled)
                        button(it, route.third).performClick()
                    }
                    SystemClock.sleep(250)
                    assertEquals("Malformed 2xx must never release the retry guard", index + 1, mutations.get())
                }
            }
        } finally { server.close(); worker.shutdownNow() }
    }
    private fun binding(activity: MainActivity) = MainActivity::class.java.getDeclaredMethod("getDraftFields").apply { isAccessible = true }.invoke(activity) as WorkspaceDraftFields
    private fun state(activity: MainActivity, route: String): Bundle {
        val workspace = MainActivity::class.java.getDeclaredField("workspace").apply { isAccessible = true }.get(activity) as WorkspaceState
        return workspace.views.getBundle(route)!!
    }
    private fun button(activity: MainActivity, label: String) = views(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == label }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun fields(root: View) = views(root).filterIsInstance<EditText>()
    private fun await(predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 10000
        while (!predicate() && SystemClock.uptimeMillis() < end) SystemClock.sleep(25)
        assertTrue("Timed out waiting for submission state", predicate())
    }
}
