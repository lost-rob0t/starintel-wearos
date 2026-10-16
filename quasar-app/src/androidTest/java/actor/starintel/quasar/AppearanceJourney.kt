package actor.starintel.quasar

import actor.starintel.testing.NativeUi as Ui
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

internal object AppearanceJourney {
    fun verify() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = QuasarAppearance(context)
        assertEquals("starintel", store.theme)
        assertEquals("dark", store.mode)
        fun openAppearance() {
            Ui.click("Settings")
            val item = Ui.device.findObject(androidx.test.uiautomator.UiSelector().descriptionStartsWith("Appearance & themes"))
            if (!item.exists()) androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollToBeginning(10)
            assertTrue(item.waitForExists(5000)); item.click(); Ui.waitText("Make it yours")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Ui.waitText("Investigation desk"); Ui.capture("quasar-theme-starintel")
            openAppearance()
            fun click(label: String) = scenario.onActivity { activity -> nodes(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == label }.performClick() }
            fun awaitAccent(expected: Int) {
                val deadline = System.currentTimeMillis() + 5000
                var matched = false
                while (!matched && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { matched = QuasarDesign.cyan == expected }
                    if (!matched) Thread.sleep(50)
                }
                assertTrue("Applied theme must reach the recreated native activity", matched)
            }
            click("Synthwave · pink / electric blue"); click("Light"); click("Apply theme")
            awaitAccent(QuasarPalette.synthwave(false).accent)
            Ui.waitText("Make it yours")
            assertEquals("synthwave", store.theme); assertEquals("light", store.mode)
            scenario.onActivity { assertEquals(QuasarPalette.synthwave(false).accent, QuasarDesign.cyan); assertFalse(QuasarDesign.isDark) }
            scenario.recreate(); Ui.waitText("Make it yours")
            Ui.device.pressBack(); Ui.waitText("Settings"); Ui.click("Home"); Ui.waitText("Investigation desk")
            Ui.capture("quasar-theme-synthwave-light")
            openAppearance()
            click("Custom · your saved colors"); click("Dark")
            scenario.onActivity { activity ->
                val inputs = nodes(activity.window.decorView).filterIsInstance<EditText>().toList()
                inputs.single { it.contentDescription == "Theme name" }.setText("Fixture amber")
                inputs.single { it.contentDescription == "dark Primary accent color" }.setText("#FFD477")
            }
            click("Apply theme"); Ui.waitText("Make it yours")
            awaitAccent(QuasarPalette.hex("#FFD477"))
            assertEquals("custom", store.theme); assertEquals("dark", store.mode)
            assertEquals("Fixture amber", store.custom().getString("name"))
            assertEquals(QuasarPalette.hex("#FFD477"), QuasarDesign.cyan)
            scenario.recreate(); Ui.waitText("Make it yours")
            assertEquals(QuasarPalette.hex("#FFD477"), QuasarDesign.cyan)
            androidx.test.uiautomator.UiScrollable(androidx.test.uiautomator.UiSelector().scrollable(true)).scrollDescriptionIntoView("dark Primary accent color")
            Ui.capture("quasar-theme-custom-editor")
            Ui.device.pressBack(); Ui.waitText("Settings"); Ui.click("Home"); Ui.waitText("Investigation desk")
            Ui.capture("quasar-theme-custom")
        }
        // A fresh activity reads persisted preferences; no in-memory-only theme.
        ActivityScenario.launch(MainActivity::class.java).use {
            Ui.waitText("Investigation desk"); assertEquals(QuasarPalette.hex("#FFD477"), QuasarDesign.cyan)
        }
        store.select("starintel", "dark")
    }
    private fun nodes(view: View): Sequence<View> = sequence {
        yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(nodes(view.getChildAt(i)))
    }
}
