package actor.starintel.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import android.graphics.Bitmap
import java.io.File

object NativeUi {
    val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun click(text: String) {
        val control = device.findObject(UiSelector().text(text))
        if (!control.waitForExists(1000)) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text)
        check(control.waitForExists(10000)) { "Missing control: $text" }
        control.click()
    }
    fun waitText(text: String) { check(device.findObject(UiSelector().textContains(text)).waitForExists(10000)) { "Missing state: $text" } }
    fun field(index: Int, value: String) { device.findObject(UiSelector().className("android.widget.EditText").instance(index)).setText(value) }
    fun fieldName(label: String, value: String) {
        val field = device.findObject(UiSelector().description(label))
        if (!field.exists()) UiScrollable(UiSelector().scrollable(true)).scrollDescriptionIntoView(label)
        field.setText(value)
        device.executeShellCommand("input keyevent 111")
    }
    fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (activity in androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) {
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
