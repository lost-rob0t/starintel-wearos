package actor.starintel.mobile

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import actor.starintel.testing.NativeUi as Ui
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperatorJourneyTest {
    @Test fun pairingAndCatalogNavigationRemainReachable() {
        ActivityScenario.launch(HomeActivity::class.java).use {
            Ui.waitText("Your field kit"); Ui.capture("companion-home")
            Ui.click("Connect watch"); Ui.waitText("STARINTEL"); Ui.capture("companion-connection")
            Ui.device.pressBack(); Ui.waitText("Your field kit")
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Phone packages")
            Ui.click("Phone packages"); Ui.waitText("Phone"); Ui.capture("companion-packages")
        }
    }
}
