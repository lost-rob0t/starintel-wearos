package actor.starintel.operator

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import actor.starintel.testing.NativeUi as Ui
import actor.starintel.testing.ContractServer
import android.view.WindowManager
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorJourneyTest {
    @Test fun authenticatedFleetAndRejectedKeyPreserveConnection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("operator_config", 0).edit().clear().commit()
        OperatorSecretStore(context).clear("star_api_key")
        ContractServer().use { backend ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                Ui.waitText("Mission control"); Ui.capture("operator-overview")
                Ui.click("Settings"); Ui.field(0, backend.origin); Ui.field(3, "star_sk_v1_fixture")
                Ui.click("Authenticate key"); Ui.waitText("Connected · key validated")
                Ui.field(3, "star_sk_v1_rejected"); Ui.click("Authenticate key"); Ui.waitText("HTTP 403")
                assertEquals("star_sk_v1_fixture", OperatorSecretStore(context).read("star_api_key"))
                Ui.click("Actors"); Ui.waitText("fixture"); Ui.waitText("ready"); Ui.capture("operator-actors")
                Ui.click("Fleet"); Ui.waitText("Quasar"); Ui.capture("operator-fleet")
                Ui.click("Overview"); Ui.waitText("142 documents")
            }
        }
    }
}
