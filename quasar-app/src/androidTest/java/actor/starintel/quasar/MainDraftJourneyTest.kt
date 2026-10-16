package actor.starintel.quasar

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class MainDraftJourneyTest {
    @Test fun oversizedPasteNavigationAndRotationPreserveDraft() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            route(scenario, "showCreateDocument")
            val source = "{\"dtype\":\"document\",\"data\":{\"text\":\"" + "x".repeat(20_000) + "\"}}"
            scenario.onActivity { activity ->
                val input = fields(activity.window.decorView).single()
                input.setText(source); input.setSelection(10)
                input.text.insert(10, "x".repeat(DraftLimits.MAX_BYTES + 1))
                assertEquals(source, input.text.toString()); assertEquals(10, input.selectionStart)
            }
            repeat(3) {
                route(scenario, "showHome")
                route(scenario, "showCreateDocument")
                scenario.onActivity { assertEquals(source, fields(it.window.decorView).single().text.toString()) }
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertEquals(source, fields(it.window.decorView).single().text.toString()) }
        }
    }

    @Test fun missingEnvelopeCannotSubmitDefaultContent() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            route(scenario, "showCreateDocument")
            scenario.onActivity { activity ->
                WorkspaceDrafts.coordinator(activity).discard("create:0")
                val bindings = MainActivity::class.java.getDeclaredMethod("getDraftFields").apply { isAccessible = true }.invoke(activity)
                val all = WorkspaceDraftFields::class.java.getDeclaredField("fields").apply { isAccessible = true }.get(bindings) as List<*>
                val field = all.single()!!
                // Corrupt saved-state fixture: a large saved field references a nonexistent envelope.
                field.javaClass.getDeclaredField("source").apply { isAccessible = true; set(field, "x".repeat(5000)) }
                field.javaClass.getDeclaredField("key").apply { isAccessible = true; set(field, "f".repeat(64)) }
                field.javaClass.getDeclaredField("dirty").apply { isAccessible = true; setBoolean(field, false) }
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 5000
            var blocked = false
            while (!blocked && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val binding = MainActivity::class.java.getDeclaredMethod("getDraftFields").apply { isAccessible = true }.invoke(activity) as WorkspaceDraftFields
                    blocked = !binding.ready
                }
                if (!blocked) SystemClock.sleep(25)
            }
            assertTrue("Restore must block submission", blocked)
            scenario.onActivity { activity ->
                allViews(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == "Create document" }.performClick()
                val workspace = MainActivity::class.java.getDeclaredField("workspace").apply { isAccessible = true }.get(activity) as WorkspaceState
                assertFalse(workspace.views.getBundle("create")!!.getBoolean("attempted"))
            }
        }
    }

    @Test fun credentialInputsNeverEnterWorkspaceSavedState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            route(scenario, "showSettings")
            scenario.onActivity { activity ->
                fields(activity.window.decorView).filter { !it.isSaveEnabled }.forEach { it.setText("never-persist-credential") }
                val state = android.os.Bundle()
                MainActivity::class.java.getDeclaredMethod("onSaveInstanceState", android.os.Bundle::class.java).apply {
                    isAccessible = true; invoke(activity, state)
                }
                val settings = state.getBundle("workspace")!!.getBundle("views")!!.getBundle("settings")!!
                assertFalse(settings.getStringArrayList("inputs").orEmpty().any { it.contains("never-persist") })
                assertEquals(fields(activity.window.decorView).count { it.isSaveEnabled }, settings.getStringArrayList("inputs")!!.size)
            }
        }
    }
    private fun route(scenario: ActivityScenario<MainActivity>, method: String) {
        scenario.onActivity { activity -> MainActivity::class.java.getDeclaredMethod(method).apply { isAccessible = true; invoke(activity) } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
    private fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
    private fun fields(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else -> emptyList()
    }
}
