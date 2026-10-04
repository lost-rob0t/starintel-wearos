package actor.starintel.quasar

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import actor.starintel.testing.NativeUi as Ui
import actor.starintel.testing.ContractServer
import android.view.WindowManager
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorJourneyTest {
    @Test fun authenticateReadRegistryAndDispatchTarget() {
        ContractServer().use { backend ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                Ui.waitText("Investigation desk"); Ui.capture("quasar-home")
                Ui.click("Settings"); Ui.fieldName("STAR SERVER", backend.origin)
                Ui.fieldName("API key", "star_sk_v1_fixture"); Ui.click("Authenticate key"); Ui.waitText("API key authenticated")
                Ui.click("Actors"); Ui.waitText("fixture"); Ui.capture("quasar-actors")
                Ui.click("Dispatch a target"); Ui.fieldName("ACTOR", "fixture"); Ui.fieldName("TARGET", "sample"); Ui.fieldName("DATASET", "test")
                Ui.click("Dispatch"); Ui.waitText("target-fixture")
                assertEquals(1, backend.targets.get())
            }
        }
    }
}
