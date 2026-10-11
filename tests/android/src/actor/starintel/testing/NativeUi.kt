package actor.starintel.testing

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiScrollable
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        // Read the resumed activity's actual rendered window, not a compositor frame
        // that may still belong to the previous activity or its secure surface.
        check(android.os.Build.VERSION.SDK_INT >= 29) { "Frame-synchronized evidence requires API 29+" }
        val targetPackage = instrumentation.targetContext.packageName
        check(device.wait(androidx.test.uiautomator.Until.hasObject(
            androidx.test.uiautomator.By.pkg(targetPackage).depth(0)), 10_000)) { "Fixture window is not foreground: $name" }
        check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == targetPackage)
        val committed = CountDownLatch(1)
        val copied = CountDownLatch(1)
        val copyResult = AtomicInteger(-1)
        lateinit var activity: android.app.Activity
        lateinit var screenshot: Bitmap
        instrumentation.runOnMainSync {
            activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            val decor = activity.window.decorView
            check(activity.hasWindowFocus()) { "Fixture activity is not focused: $name" }
            check(decor.isAttachedToWindow && decor.width > 0 && decor.height > 0)
            check(decor.isHardwareAccelerated) { "Frame commit requires a hardware-rendered window" }
            activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        check(committed.await(10, TimeUnit.SECONDS)) { "No committed fixture frame: $name" }
        instrumentation.runOnMainSync {
            check(activity in androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)) { "Fixture activity changed: $name" }
            check(activity.hasWindowFocus()) { "Fixture activity lost focus: $name" }
            val decor = activity.window.decorView
            screenshot = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, screenshot, { result ->
                copyResult.set(result)
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        check(copied.await(10, TimeUnit.SECONDS)) { "Fixture window copy timed out: $name" }
        check(copyResult.get() == PixelCopy.SUCCESS) { "Fixture window copy failed: ${copyResult.get()} ($name)" }
        val context = instrumentation.targetContext
        val directory = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
        check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == targetPackage) {
            "Fixture lost the active window before hierarchy capture: $name"
        }
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
