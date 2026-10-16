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
        dismissFixtureLauncherAnr()
        val control = device.findObject(UiSelector().text(text))
        val destinations = device.findObject(UiSelector().description("Primary destinations"))
        if (text in setOf("Home", "Field", "Actors", "Search", "Settings") && !control.exists() && destinations.exists()) destinations.click()
        if (!control.waitForExists(1000)) UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView(text)
        check(control.waitForExists(10000)) { "Missing control: $text" }
        control.click()
    }
    fun waitText(text: String) {
        dismissFixtureLauncherAnr()
        if (!device.findObject(UiSelector().textContains(text)).waitForExists(10000)) {
            dismissFixtureLauncherAnr()
            if (!device.findObject(UiSelector().textContains(text)).waitForExists(2000)) {
                runCatching { reportWaitFailure(text) }
                error("Missing state: $text")
            }
        }
    }

    /** Same bounded external-launcher repair as the existing widget fixture. Never dismiss app failures. */
    private fun dismissFixtureLauncherAnr() {
        val dialog = device.findObject(UiSelector().text("Pixel Launcher isn't responding"))
        if (!dialog.exists()) return
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1") { "Launcher repair requires a disposable emulator" }
        val close = device.findObject(UiSelector().resourceId("android:id/aerr_close"))
        check(close.exists()) { "Named launcher error has no close control" }
        close.click()
        check(dialog.waitUntilGone(5000)) { "Launcher error remained visible" }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
            putString("stream", "\nDismissed the disposable emulator's named Pixel Launcher ANR; app errors remain fatal.\n")
        })
    }

    /** The IME is a separate window and may own focus in landscape; record the visible compositor. */
    fun captureKeyboard(name: String) {
        dismissFixtureLauncherAnr()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val ime = android.provider.Settings.Secure.getString(context.contentResolver,
            android.provider.Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().substringBefore('/')
        val active = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        check(active == context.packageName || active == ime) { "Unexpected window over fixture keyboard: $active" }
        val directory = File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
        File(directory, "$name.capture.txt").writeText("Compositor capture of the synthetic IME/window state; PixelCopy cannot include a separate IME window.\n")
    }

    /** Fixture-only diagnostics run before ActivityScenario tears down the failed window. */
    private fun reportWaitFailure(expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val report = StringBuilder("Native fixture wait failed: ").append(expected.take(160)).append('\n')
        fun record(label: String, block: () -> String) {
            report.append(label).append(": ")
                .append(runCatching(block).getOrElse { "unavailable (${it.javaClass.simpleName})" }.take(8192))
                .append('\n')
        }
        record("activePackage") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() ?: "none"
        }
        record("resumedActivities") {
            val ready = CountDownLatch(1)
            val value = java.util.concurrent.atomic.AtomicReference("unavailable")
            Handler(Looper.getMainLooper()).post {
                try {
                    value.set(androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .joinToString { activity ->
                            "${activity.javaClass.name}:focus=${activity.hasWindowFocus()}," +
                                "attached=${activity.window.decorView.isAttachedToWindow}"
                        })
                } catch (failure: Exception) {
                    value.set("unavailable (${failure.javaClass.simpleName})")
                } finally { ready.countDown() }
            }
            if (ready.await(2, TimeUnit.SECONDS)) value.get() else "main thread did not respond within 2s"
        }
        record("power") {
            val context = instrumentation.targetContext
            val power = context.getSystemService(android.os.PowerManager::class.java)
            val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
            "interactive=${power.isInteractive},keyguardLocked=${keyguard.isKeyguardLocked},deviceLocked=${keyguard.isDeviceLocked}"
        }
        record("focusedWindow") {
            device.executeShellCommand("dumpsys -t 2 window windows").lineSequence()
                .filter { it.contains("mCurrentFocus=") || it.contains("mFocusedApp=") }
                .take(4).joinToString("\n")
        }
        record("activeHierarchy") {
            // Bound both collected nodes and text. Never include another app's text or input fields.
            val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
            val rows = mutableListOf<String>()
            while (pending.isNotEmpty() && rows.size < 48) {
                val node = pending.removeFirst()
                val owned = node.packageName?.toString() == instrumentation.targetContext.packageName
                val input = node.className?.toString()?.contains("EditText") == true || node.isPassword
                rows += "${node.packageName}/${node.className}: " +
                    if (owned && !input) node.text?.toString().orEmpty().take(120) else "[text omitted]"
                for (index in 0 until node.childCount.coerceAtMost(48 - rows.size - pending.size).coerceAtLeast(0)) {
                    node.getChild(index)?.let(pending::add)
                }
                node.recycle()
            }
            pending.forEach { it.recycle() }
            rows.joinToString("\n")
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putString("stream", "\n${report}\n") })
    }
    fun field(index: Int, value: String) { device.findObject(UiSelector().className("android.widget.EditText").instance(index)).setText(value) }
    fun fieldName(label: String, value: String) {
        dismissFixtureLauncherAnr()
        val field = device.findObject(UiSelector().description(label))
        if (!field.waitForExists(3000)) {
            val scroll = UiScrollable(UiSelector().scrollable(true))
            if (scroll.exists()) scroll.scrollDescriptionIntoView(label)
        }
        check(field.waitForExists(10000)) { "Missing input: $label" }
        field.setText(value)
        device.executeShellCommand("input keyevent 111")
    }
    fun capture(name: String) {
        dismissFixtureLauncherAnr()
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
        check(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == targetPackage) {
            "Fixture lost the active window before hierarchy capture: $name"
        }
        val hierarchy = File(directory, "$name.xml")
        device.dumpWindowHierarchy(hierarchy)
        // Supplemental compositor evidence covers the actual keyboard and system bars.
        // The fixture-only secure-flag override above is already committed to a frame.
        // Window frame commitment can precede SurfaceFlinger applying the test-only
        // secure flag transaction. Require the compositor to contain this frame,
        // rather than saving a black protected surface as valid evidence.
        val deadline = android.os.SystemClock.uptimeMillis() + 10_000
        var system: Bitmap
        while (true) {
            system = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "No fixture compositor frame: $name" }
            var matched = 0
            var sampled = 0
            if (system.width == screenshot.width && system.height == screenshot.height) {
                for (y in screenshot.height / 4 until screenshot.height * 3 / 4 step 16) {
                    for (x in screenshot.width / 4 until screenshot.width * 3 / 4 step 16) {
                        val expected = screenshot.getPixel(x, y)
                        val actual = system.getPixel(x, y)
                        if (kotlin.math.abs(android.graphics.Color.red(expected) - android.graphics.Color.red(actual)) < 16 &&
                            kotlin.math.abs(android.graphics.Color.green(expected) - android.graphics.Color.green(actual)) < 16 &&
                            kotlin.math.abs(android.graphics.Color.blue(expected) - android.graphics.Color.blue(actual)) < 16) matched++
                        sampled++
                    }
                }
            }
            if (sampled > 0 && matched.toDouble() / sampled >= 0.9) break
            system.recycle()
            check(android.os.SystemClock.uptimeMillis() < deadline) { "Compositor did not show the fixture frame: $name" }
            android.os.SystemClock.sleep(50)
        }
        screenshot.recycle()
        File(directory, "$name-system.png").outputStream().use {
            check(system.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        system.recycle()
        hierarchy.copyTo(File(directory, "$name-system.xml"), overwrite = true)
    }
}
