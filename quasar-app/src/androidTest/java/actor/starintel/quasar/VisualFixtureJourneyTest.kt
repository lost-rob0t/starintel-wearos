package actor.starintel.quasar

import actor.starintel.testing.NativeUi as Ui
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith

/** Also runs unchanged against the baseline APK source. No redesign-specific API calls. */
@RunWith(AndroidJUnit4::class)
class VisualFixtureJourneyTest {
    @Test fun captureSameSyntheticWorkstation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = InstrumentationRegistry.getArguments().getString("fixtureLabel", "default")
        WorkstationFixture().use { backend ->
            QuasarConfig(instrumentation.targetContext).save(backend.origin, "star_sk_v1_fixture")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Ui.waitText("Investigation desk"); Ui.capture("$label-home")
                var primarySearch = false
                scenario.onActivity { activity -> primarySearch = all(activity.window.decorView).any { it.tag == "Search" || it.contentDescription == "Primary destinations" } }
                Ui.click(if (primarySearch) "Search" else "Documents")
                val searchStart = android.os.SystemClock.elapsedRealtime()
                scenario.onActivity { activity ->
                    all(activity.window.decorView).filterIsInstance<TextView>()
                        .single { it.text.toString() == "Search" && it.tag != "Search" && it.tag != "current-destination" }.performClick()
                }
                reveal("Fixture record 0")
                val searchMillis = android.os.SystemClock.elapsedRealtime() - searchStart
                scenario.onActivity { activity ->
                    val nodes = all(activity.window.decorView).toList()
                    instrumentation.sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "\nFixture measurement $label: search-to-observed-result=${searchMillis}ms (includes UiAutomator polling), attachedViews=${nodes.size}, attachedRecordTitles=${nodes.filterIsInstance<TextView>().count { it.text.startsWith("Fixture record") }}, JavaHeap=${Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()} bytes. Not physical-phone performance.\n")
                    })
                }
                Ui.capture("$label-search")
                Ui.click("Fixture record 0")
                reveal("Synthetic field exercise"); Ui.capture("$label-document")
                Ui.click("Field"); Ui.waitText("Quasar tactical")
                // Wait for the snapshot, rather than screenshotting the in-flight spinner.
                val end = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < end) {
                    var loading = true
                    scenario.onActivity { activity -> loading = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "SYNCING" } }
                    if (!loading) break
                    Thread.sleep(100)
                }
                check(backend.maxRequested.get() <= 100) { "Unbounded map request" }
                Ui.capture("$label-field")
                Ui.click("Actors"); reveal("star://fixture/actor/reconnaissance"); Ui.capture("$label-actors")
                val selection = Ui.device.findObject(androidx.test.uiautomator.UiSelector().text("Select fixture-reconnaissance"))
                if (selection.exists()) selection.click() else Ui.click("Dispatch a target")
                Ui.fieldName("ACTOR", "fixture-reconnaissance")
                Ui.waitText("Dispatch target"); Ui.capture("$label-target")
                Ui.click("Settings"); Ui.waitText("Credentials are validated"); Ui.capture("$label-settings")
                val serverField = Ui.device.findObject(androidx.test.uiautomator.UiSelector().description("STAR SERVER"))
                if (!serverField.exists()) androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollDescriptionIntoView("STAR SERVER")
                serverField.click()
                var imeShown = false
                val imeDeadline = System.currentTimeMillis() + 5000
                while (!imeShown && System.currentTimeMillis() < imeDeadline) {
                    scenario.onActivity { activity -> imeShown = activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
                    if (!imeShown) Thread.sleep(100)
                }
                check(imeShown) { "Keyboard did not open for fixture" }
                // Wait for inset animation/layout to settle before recording the visible IME.
                var previousBottom = -1
                var stableFrames = 0
                val settleUntil = System.currentTimeMillis() + 5000
                while (stableFrames < 3 && System.currentTimeMillis() < settleUntil) {
                    var bottom = 0
                    scenario.onActivity { activity -> bottom = activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.ime()).bottom }
                    stableFrames = if (bottom == previousBottom && bottom > 0) stableFrames + 1 else 0
                    previousBottom = bottom
                    Thread.sleep(100)
                }
                check(stableFrames >= 3) { "Keyboard insets did not settle" }
                Ui.captureKeyboard("$label-keyboard")
                Ui.device.pressBack()
            }
            ActivityScenario.launch(actor.starintel.quasar.ide.LogicStudioActivity::class.java).use {
                Ui.waitText("Logic studio"); Ui.capture("$label-logic")
            }
            ActivityScenario.launch(actor.starintel.quasar.workflows.FlowStudioActivity::class.java).use {
                Ui.waitText("Flow studio"); Ui.capture("$label-flow")
            }
            val workflows = actor.starintel.quasar.workflows.SharedPreferencesWorkflowRepository(instrumentation.targetContext)
            workflows.publish(actor.starintel.quasar.workflows.WorkflowWidgetState(
                actor.starintel.quasar.workflows.WorkflowConnectivity.ONLINE,
                listOf(actor.starintel.quasar.workflows.WorkflowWidgetSummary.create("fixture-workflow", "Fixture field route", actor.starintel.quasar.workflows.WorkflowRunStatus.RUNNING)),
                System.currentTimeMillis(),
            ))
            ActivityScenario.launch(actor.starintel.quasar.workflows.WorkflowStatusActivity::class.java).use {
                Ui.waitText("Workflows"); Ui.capture("$label-workflows")
            }
        }
    }
    private fun reveal(text: String) {
        val selector = androidx.test.uiautomator.UiSelector().textContains(text)
        if (!Ui.device.findObject(selector).waitForExists(500)) {
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollIntoView(selector)
        }
        Ui.waitText(text)
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
