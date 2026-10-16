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
                Ui.click("Documents")
                scenario.onActivity { activity ->
                    all(activity.window.decorView).filterIsInstance<TextView>()
                        .single { it.text.toString() == "Search" && it.tag != "Search" }.performClick()
                }
                Ui.waitText("Fixture record 0"); Ui.capture("$label-search")
                Ui.click("Fixture record 0")
                Ui.waitText("Synthetic field exercise"); Ui.capture("$label-document")
                Ui.click("Field"); Ui.waitText("Quasar tactical")
                // Wait for the snapshot, rather than screenshotting the in-flight spinner.
                val end = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < end) {
                    var loading = true
                    scenario.onActivity { activity -> loading = all(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "SYNCING" } }
                    if (!loading) break
                    Thread.sleep(100)
                }
                Ui.capture("$label-field")
                Ui.click("Settings"); Ui.waitText("Credentials are validated"); Ui.capture("$label-settings")
            }
        }
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
