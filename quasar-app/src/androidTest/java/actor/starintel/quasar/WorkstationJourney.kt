package actor.starintel.quasar

import actor.starintel.testing.NativeUi as Ui
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.*

internal object WorkstationJourney {
    fun verify() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val drafts = WorkspaceDrafts(instrumentation.targetContext)
        val draftSource = "synthetic-private-source-".repeat(1000)
        val draftKey = drafts.save("fixture-roundtrip", draftSource)
        assertEquals(draftSource, drafts.read(draftKey))
        assertFalse(instrumentation.targetContext.filesDir.resolve("workspace-drafts-v1/$draftKey").readBytes().toString(Charsets.ISO_8859_1).contains("synthetic-private-source"))
        WorkstationFixture().use { backend ->
            QuasarConfig(instrumentation.targetContext).save(backend.origin, "star_sk_v1_fixture")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                Ui.waitText("Investigation desk"); Ui.click("Search")
                fun search(value: String) {
                    scenario.onActivity { activity ->
                        val nodes = all(activity.window.decorView).toList()
                        nodes.filterIsInstance<EditText>().single().setText(value)
                        nodes.filterIsInstance<TextView>().single { it.text.toString() == "Search" && it.tag != "Search" }.performClick()
                    }
                }
                val start = System.nanoTime()
                search("*"); Ui.waitText("40 records loaded")
                val elapsed = (System.nanoTime() - start) / 1_000_000
                scenario.onActivity { activity ->
                    val list = all(activity.window.decorView).filterIsInstance<RecyclerView>().single()
                    assertEquals(40, list.adapter!!.itemCount)
                    assertTrue("Only visible holders should be attached", list.childCount < 40)
                    list.scrollToPosition(30)
                }
                instrumentation.waitForIdleSync()
                Ui.capture("quasar-search-bounded")
                scenario.recreate(); Ui.waitText("40 records loaded")
                scenario.onActivity { activity ->
                    val list = all(activity.window.decorView).filterIsInstance<RecyclerView>().single()
                    assertEquals("*", all(activity.window.decorView).filterIsInstance<EditText>().single().text.toString())
                    assertTrue((list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager).findFirstVisibleItemPosition() > 0)
                }
                backend.searchStatus = 503
                search("*"); Ui.waitText("Refresh failed; previous results retained")
                scenario.onActivity { activity -> assertEquals(40, all(activity.window.decorView).filterIsInstance<RecyclerView>().single().adapter!!.itemCount) }
                backend.searchStatus = 401
                search("*"); Ui.waitText("Authentication expired")
                backend.searchStatus = 403
                search("*"); Ui.waitText("Permission denied")
                backend.searchStatus = 200
                search("slow")
                assertTrue(backend.slowRequested.await(5, java.util.concurrent.TimeUnit.SECONDS))
                search("empty"); Ui.waitText("No matching documents")
                Thread.sleep(900) // Release the older slow response before checking the superseding result.
                scenario.onActivity { activity -> assertEquals(0, all(activity.window.decorView).filterIsInstance<RecyclerView>().single().adapter!!.itemCount) }
                search("*"); Ui.waitText("40 records loaded")
                Ui.click("Fixture record 0"); Ui.waitText("Synthetic field exercise")
                Ui.device.pressBack(); Ui.waitText("40 records loaded")
                scenario.onActivity { activity ->
                    val adapter = all(activity.window.decorView).filterIsInstance<RecyclerView>().single().adapter as DocumentRows
                    assertEquals("fixture-0", adapter.selectedId)
                }
                assertEquals(40, backend.maxRequested.get())
                instrumentation.sendStatus(0, android.os.Bundle().apply {
                    putString("stream", "\nBounded corpus: 1000000 synthetic records, requested=${backend.maxRequested.get()}, initial search+UI=${elapsed}ms, heap=${Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()} bytes. Emulator measurement only.\n")
                })
                Ui.capture("quasar-search-reconnected")
                Ui.click("Field"); Ui.waitText("100 GEO / 100 RECORDS")
                Ui.click("LIST")
                scenario.onActivity { activity ->
                    val nodes = all(activity.window.decorView).toList()
                    nodes.filterIsInstance<EditText>().single().setText("record 2")
                    nodes.filterIsInstance<actor.starintel.quasar.field.FieldOpsView>().single().focusOn(51.502, -.118)
                }
                Ui.waitText("Fixture record 2")
                scenario.recreate(); Ui.waitText("100 GEO / 100 RECORDS")
                scenario.onActivity { activity ->
                    val nodes = all(activity.window.decorView).toList()
                    assertEquals("record 2", nodes.filterIsInstance<EditText>().single().text.toString())
                    val list = nodes.filterIsInstance<RecyclerView>().single()
                    assertTrue("Field list mode must survive recreation", list.isShown)
                    assertEquals(11, list.adapter!!.itemCount)
                    val state = nodes.filterIsInstance<actor.starintel.quasar.field.FieldOpsView>().single().saveWorkspace()
                    assertEquals("fixture-2", state.getString("selected"))
                    assertEquals(51.502, state.getDouble("latitude"), .000001)
                }
                Ui.capture("quasar-field-list-restored")
            }
        }
    }
    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
