package actor.starintel.quasar.workflows

import actor.starintel.quasar.R
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real Android host and persistence; this fixture is not a launcher or an FBP executor. */
@RunWith(AndroidJUnit4::class)
class WorkflowWidgetJourneyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = SharedPreferencesWorkflowRepository(context)
    private val preferences get() = context.getSharedPreferences("quasar_workflow_control_plane_v1", 0)

    @Before fun reset() { preferences.edit().clear().commit() }
    @After fun clean() { preferences.edit().clear().commit() }

    private fun snapshot(time: Long? = System.currentTimeMillis(), status: WorkflowRunStatus = WorkflowRunStatus.IDLE,
                         online: Boolean = true, failure: String? = null) = WorkflowWidgetState(
        if (online) WorkflowConnectivity.ONLINE else WorkflowConnectivity.OFFLINE,
        listOf(WorkflowWidgetSummary.create("alpha", "Alpha workflow", status,
            lastRunEpochMillis = System.currentTimeMillis() - 86_400_000, lastFailure = failure)), time)

    @Test fun hostRendersCachedReportsAndRoutesTypedCommandWithoutRefreshingObservation() {
        val observed = System.currentTimeMillis() - 1000
        repository.publish(snapshot(observed).copy(workflows = snapshot(observed).workflows + listOf(
            WorkflowWidgetSummary.create("beta", "Beta workflow", WorkflowRunStatus.IDLE),
            WorkflowWidgetSummary.create("gamma", "Gamma workflow", WorkflowRunStatus.IDLE),
        )))
        withHost { scenario, host ->
            await { text(scenario).contains("Last reported online") }
            scenario.onActivity {
                val run = host.findViewById<View>(R.id.workflow_widget_run)
                assertTrue(run.isShown)
                assertTrue(run.height >= (48 * it.resources.displayMetrics.density).toInt())
                assertEquals("Run Alpha workflow", run.contentDescription.toString())
                assertFalse(host.findViewById<View>(R.id.workflow_widget_pause).isShown)
                assertFalse(host.findViewById<View>(R.id.workflow_widget_row_one).isShown)
                val position = IntArray(2); run.getLocationOnScreen(position)
                val hostPosition = IntArray(2); host.getLocationOnScreen(hostPosition)
                assertTrue("Run target is clipped at the advertised minimum", position[1] + run.height <= hostPosition[1] + host.height)
            }
            capture(scenario, "widget-recent-minimum-250x360")
            scenario.onActivity { activity ->
                host.layoutParams = host.layoutParams.apply { height = (480 * activity.resources.displayMetrics.density).toInt() }
                AppWidgetManager.getInstance(activity).updateAppWidgetOptions(host.appWidgetId, options(480))
            }
            await { text(scenario).contains("Gamma workflow") }
            capture(scenario, "widget-expanded-three-workflows")
            scenario.onActivity { host.findViewById<View>(R.id.workflow_widget_run).performClick() }
            await { repository.pendingCommands().size == 1 }
            assertEquals(WorkflowCommand.RUN, repository.pendingCommands().single().command)
            assertEquals(WorkflowCommandSource.HOME_WIDGET, repository.pendingCommands().single().source)
            assertEquals(observed, SharedPreferencesWorkflowRepository(context).state().updatedAtEpochMillis)
            await { text(scenario).contains("RUN PENDING") }
            capture(scenario, "widget-command-pending")
            // Keep the rendered Run PendingIntent but expire its persisted observation before the click.
            repository.acknowledge(repository.pendingCommands().single().requestId)
            repository.publish(snapshot())
            await { text(scenario).contains("IDLE") }
            preferences.edit().putString("widget_state", WorkflowWidgetStateCodec.encode(
                snapshot(System.currentTimeMillis() - WorkflowSnapshotPolicy.MAX_AGE_MILLIS - 1000))).commit()
            scenario.onActivity { host.findViewById<View>(R.id.workflow_widget_run).performClick() }
            await { text(scenario).contains("Report expired") }
            assertTrue(repository.pendingCommands().isEmpty())
            capture(scenario, "widget-retained-control-rejected")
        }
    }

    @Test fun hostSuppressesUnsafeControlsAndFailureDetailsAcrossSnapshotStates() {
        repository.publish(snapshot(status = WorkflowRunStatus.RUNNING))
        withHost { scenario, host ->
            await { text(scenario).contains("RUNNING") }
            scenario.onActivity {
                assertTrue(host.findViewById<View>(R.id.workflow_widget_stop).isShown)
                assertFalse(host.findViewById<View>(R.id.workflow_widget_pause).isShown)
            }
            capture(scenario, "widget-running-stop-only")
            val cases = listOf(
                "stale" to snapshot(System.currentTimeMillis() - WorkflowSnapshotPolicy.MAX_AGE_MILLIS - 1000),
                "future" to snapshot(System.currentTimeMillis() + 60_000),
                "missing" to snapshot(null),
                "offline" to snapshot(online = false),
                "failure" to snapshot(status = WorkflowRunStatus.FAILED, failure = "PRIVATE_FAILURE_FIXTURE"),
            )
            for ((name, state) in cases) {
                repository.publish(state)
                val expected = when (name) {
                    "stale" -> "Report expired"
                    "future" -> "Report time is in the future"
                    "missing" -> "No timestamped runtime report"
                    "offline" -> "Last reported offline"
                    else -> "Failure reported"
                }
                await { text(scenario).contains(expected) }
                assertFalse(text(scenario).contains("PRIVATE_FAILURE_FIXTURE"))
                if (name != "failure") {
                    scenario.onActivity {
                        for (id in listOf(R.id.workflow_widget_run, R.id.workflow_widget_pause,
                            R.id.workflow_widget_resume, R.id.workflow_widget_stop)) {
                            assertFalse("$name exposes control $id", host.findViewById<View>(id).isShown)
                        }
                    }
                    val receipt = repository.request(WorkflowCommandRequest(WorkflowId.parse("alpha"),
                        WorkflowCommand.RUN, WorkflowCommandSource.HOME_WIDGET, "stale_token_123456"))
                    assertFalse(receipt.accepted)
                }
                capture(scenario, "widget-$name")
            }
        }
    }

    @Test fun statusRefreshesAfterBackgroundRecreationAndNewExplicitLinks() {
        fun publish(failure: String) = repository.publish(WorkflowWidgetState(WorkflowConnectivity.ONLINE, listOf(
            WorkflowWidgetSummary.create("alpha", "Alpha workflow", WorkflowRunStatus.FAILED, lastFailure = failure),
            WorkflowWidgetSummary.create("beta", "Beta workflow", WorkflowRunStatus.FAILED, lastFailure = "Only beta details"),
        ), System.currentTimeMillis()))
        publish("Only alpha details")
        ActivityScenario.launch<WorkflowStatusActivity>(route("alpha")).use { scenario ->
            await { text(scenario).contains("Only alpha details") }
            assertTrue(text(scenario).contains("Pause and resume are not supported"))
            scenario.recreate()
            assertTrue(text(scenario).contains("Only alpha details"))
            scenario.moveToState(Lifecycle.State.CREATED)
            publish("Changed alpha details")
            scenario.moveToState(Lifecycle.State.RESUMED)
            await { text(scenario).contains("Changed alpha details") }
            capture(scenario, "status-resumed-alpha")
            scenario.onActivity { it.startActivity(route("beta").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await { text(scenario).contains("Only beta details") }
            assertFalse(text(scenario).contains("Changed alpha details"))
            capture(scenario, "status-new-intent-beta")
            scenario.recreate()
            assertTrue(text(scenario).contains("Only beta details"))
            scenario.onActivity { it.startActivity(route(null).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            await { !text(scenario).contains("Only beta details") }
            capture(scenario, "status-all-workflows")
        }
    }

    private fun route(id: String?) = Intent(context, WorkflowStatusActivity::class.java).apply {
        action = Intent.ACTION_VIEW
        if (id != null) data = Uri.parse(WorkflowDeepLink.forWorkflow(WorkflowId.parse(id)))
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun withHost(block: (ActivityScenario<WorkflowStatusActivity>, AppWidgetHostView) -> Unit) {
        ActivityScenario.launch<WorkflowStatusActivity>(route(null)).use { scenario ->
            lateinit var host: AppWidgetHost
            lateinit var view: AppWidgetHostView
            scenario.onActivity { activity ->
                host = AppWidgetHost(activity, 115)
                val id = host.allocateAppWidgetId()
                val manager = AppWidgetManager.getInstance(activity)
                assertTrue("CI must grant widget binding to the disposable emulator app",
                    manager.bindAppWidgetIdIfAllowed(id, ComponentName(activity, WorkflowWidgetProvider::class.java)))
                view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                val frame = FrameLayout(activity).apply {
                    setBackgroundColor(Color.BLACK)
                    val density = resources.displayMetrics.density
                    setPadding((16 * density).toInt(), (48 * density).toInt(), (16 * density).toInt(), 0)
                    addView(view, FrameLayout.LayoutParams((250 * density).toInt(), (360 * density).toInt()))
                }
                activity.setContentView(frame)
                manager.updateAppWidgetOptions(id, options(360))
                host.startListening()
                WorkflowWidgetRenderer.update(activity, manager, id)
            }
            try { block(scenario, view) } finally { scenario.onActivity { host.stopListening(); host.deleteHost() } }
        }
    }

    private fun options(height: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
    }

    private fun text(scenario: ActivityScenario<WorkflowStatusActivity>): String {
        var result = ""
        scenario.onActivity { activity ->
            fun collect(view: View): String = when (view) {
                is TextView -> if (view.isShown) view.text.toString() else ""
                is ViewGroup -> (0 until view.childCount).joinToString("\n") { collect(view.getChildAt(it)) }
                else -> ""
            }
            result = collect(activity.window.decorView)
        }
        return result
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue("Timed out waiting for Android state", condition())
    }

    private fun capture(scenario: ActivityScenario<WorkflowStatusActivity>, name: String) {
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        instrumentation.waitForIdleSync()
        UiDevice.getInstance(instrumentation).waitForIdle()
        val directory = File(context.getExternalFilesDir(null), "widget-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        UiDevice.getInstance(instrumentation).dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
