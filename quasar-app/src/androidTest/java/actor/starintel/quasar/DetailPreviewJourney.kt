package actor.starintel.quasar

import actor.starintel.quasar.detail.RecordDetailPreview
import actor.starintel.quasar.detail.RecordDetailView
import actor.starintel.testing.NativeUi as Ui
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.*

/** Runs through the actual loadDocument route in the hosted native journey. */
internal object DetailPreviewJourney {
    fun verify(scenario: ActivityScenario<MainActivity>, backend: WorkstationFixture) {
        fun inspect(action: (RecordDetailView, List<TextView>) -> Unit) {
            scenario.onActivity { activity ->
                val detail = all(activity.window.decorView).filterIsInstance<RecordDetailView>().single()
                action(detail, all(detail).filterIsInstance<TextView>().toList())
            }
        }
        fun click(label: String) = inspect { _, texts -> texts.single { it.text.toString() == label }.performClick() }
        fun collapsed() = inspect { _, texts ->
            assertFalse(texts.any { it.isShown && (it.text.startsWith("Complete raw preview") || it.text.startsWith("Partial preview:")) })
            assertFalse(texts.any { it.isShown && it.text.contains("\"document\"") })
        }
        collapsed()
        click("Raw JSON"); Ui.waitText("Complete raw preview.")
        inspect { _, texts -> assertTrue(texts.any { it.isShown && it.text.contains("\"document\"") }) }
        click("Raw JSON"); collapsed()
        click("Structured fields"); Ui.waitText("Structured field preview.")
        inspect { _, texts -> assertTrue(texts.any { it.isShown && it.text.contains("\"name\": \"Fixture record 0\"") }) }
        backend.detailPadding = 256 * 1024
        try {
            scenario.recreate(); Ui.waitText("Synthetic field exercise"); collapsed()
            click("Raw JSON"); Ui.waitText("Partial preview:")
            inspect { _, texts ->
                assertTrue(texts.filter { it.isShown }.all { it.text.length <= RecordDetailPreview.MAX_CHARS })
                assertTrue(texts.any { it.isShown && it.text.length == RecordDetailPreview.MAX_CHARS })
            }
            click("Structured fields"); Ui.waitText("Partial preview:")
            inspect { _, texts -> assertTrue(texts.filter { it.isShown }.all { it.text.length <= RecordDetailPreview.MAX_CHARS }) }
            click("Structured fields"); collapsed()
            Ui.capture("quasar-detail-bounded-collapsed")
        } finally { backend.detailPadding = 0 }
    }

    private fun all(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(all(view.getChildAt(i)))
    }
}
