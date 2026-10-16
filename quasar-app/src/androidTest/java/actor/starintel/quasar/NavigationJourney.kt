package actor.starintel.quasar

import actor.starintel.testing.NativeUi as Ui
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*

internal object NavigationJourney {
    fun verify() {
        WorkstationFixture().use { backend ->
            QuasarConfig(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
                .save(backend.origin, "star_sk_v1_fixture")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Ui.waitText("Investigation desk")
                fun expand() {
                    Ui.device.findObject(androidx.test.uiautomator.UiSelector()
                        .descriptionMatches("Open navigation|Expand navigation")).click()
                    Ui.waitText("Your workspace")
                }
                expand(); Ui.capture("quasar-navigation-open")
                Ui.click("Search"); Ui.waitText("Documents")
                scenario.onActivity { activity ->
                    val nodes = all(activity.window.decorView).toList()
                    assertFalse(nodes.filterIsInstance<WorkspaceNavigation>().single().isExpanded)
                    nodes.filterIsInstance<EditText>().single().setText("navigation-fixture")
                }
                expand()
                scenario.recreate(); Ui.waitText("Your workspace")
                scenario.onActivity { activity ->
                    val nav = all(activity.window.decorView).filterIsInstance<WorkspaceNavigation>().single()
                    assertTrue("Open drawer survives recreation", nav.isExpanded)
                    assertEquals("search", nav.selectedDestination)
                }
                Ui.capture("quasar-navigation-restored")
                Ui.device.pressBack()
                Ui.device.waitForIdle()
                scenario.onActivity { activity ->
                    val nodes = all(activity.window.decorView).toList()
                    assertFalse("Back closes the drawer first", nodes.filterIsInstance<WorkspaceNavigation>().single().isExpanded)
                    assertEquals("navigation-fixture", nodes.filterIsInstance<EditText>().single().text.toString())
                }
                expand(); Ui.click("Search") // Current destination only collapses; no route reset.
                Ui.device.waitForIdle()
                scenario.onActivity { activity ->
                    assertEquals("navigation-fixture", all(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
                }
                expand(); Ui.click("Datasets"); Ui.waitText("Datasets")
                scenario.onActivity { activity -> assertEquals("datasets", all(activity.window.decorView).filterIsInstance<WorkspaceNavigation>().single().selectedDestination) }
                Ui.device.pressBack(); Ui.waitText("Documents")
                scenario.onActivity { activity ->
                    assertEquals("navigation-fixture", all(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
                }
                expand()
                scenario.onActivity { activity ->
                    activity.startActivity(android.content.Intent(activity, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .setAction(actor.starintel.android.config.OperatorContracts.ACTION_OPEN_MAP))
                }
                Ui.waitText("Quasar tactical")
                scenario.onActivity { activity ->
                    val nav = all(activity.window.decorView).filterIsInstance<WorkspaceNavigation>().single()
                    assertFalse("Deep links reveal their destination", nav.isExpanded)
                    assertEquals("field", nav.selectedDestination)
                }
            }
        }
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
