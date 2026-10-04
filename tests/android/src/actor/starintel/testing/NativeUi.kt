package actor.starintel.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import android.graphics.Bitmap
import java.io.File

object NativeUi {
    val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    fun click(text: String) { device.findObject(UiSelector().text(text)).apply { check(waitForExists(10000)) { "Missing control: $text" }; click() } }
    fun waitText(text: String) { check(device.findObject(UiSelector().textContains(text)).waitForExists(10000)) { "Missing state: $text" } }
    fun field(index: Int, value: String) { device.findObject(UiSelector().className("android.widget.EditText").instance(index)).setText(value) }
    fun fieldName(label: String, value: String) { device.findObject(UiSelector().description(label)).setText(value) }
    fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
