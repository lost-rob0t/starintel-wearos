package actor.starintel.quasar.ide

import android.os.SystemClock
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogicEditorJourneyTest {
    @Test fun boundedEditsHighlightLatestSourceAndRestoreDraft() {
        ActivityScenario.launch(LogicStudioActivity::class.java).use { scenario ->
            val finalSource = "(defun newest () 42)"
            scenario.onActivity { activity ->
                val field = editor(activity)
                field.setText("(old)")
                // A whole rejected paste must preserve the prior content/selection.
                field.setSelection(2)
                field.text.insert(2, "x".repeat(256 * 1024 + 1))
                assertEquals("(old)", field.text.toString())
                assertEquals(2, field.selectionStart)
                // Replacing setText must not charge the previous source's bytes.
                field.setText("a".repeat(256 * 1024))
                assertEquals(256 * 1024, field.length())
                field.text.insert(field.length(), "é")
                assertEquals(256 * 1024, field.length())
                field.text.delete(0, 2)
                field.text.insert(0, "é")
                assertEquals(256 * 1024, field.text.toString().toByteArray(Charsets.UTF_8).size)
                field.setText("(intermediate)")
                field.setText(finalSource)
            }
            awaitHighlight(scenario, finalSource)
            scenario.recreate()
            scenario.onActivity { assertEquals(finalSource, editor(it).text.toString()) }
            awaitHighlight(scenario, finalSource)
            scenario.onActivity { editor(it).setText("x".repeat(40 * 1024)) }
            // Give the worker a chance to finish so a stale result would surface.
            SystemClock.sleep(350)
            scenario.onActivity {
                val field = editor(it)
                assertEquals(40 * 1024, field.length())
                assertTrue(field.text.getSpans(0, field.length(), ForegroundColorSpan::class.java).isEmpty())
                field.setText(finalSource)
            }
            awaitHighlight(scenario, finalSource)
        }
    }

    private fun awaitHighlight(scenario: ActivityScenario<LogicStudioActivity>, source: String) {
        val expected = LispLanguage.tokenize(source).map { it.start to it.endExclusive }.sortedBy { it.first }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var actual = emptyList<Pair<Int, Int>>()
            scenario.onActivity {
                val editable = editor(it).text
                assertEquals(source, editable.toString())
                actual = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
                    .map { span -> editable.getSpanStart(span) to editable.getSpanEnd(span) }.sortedBy { pair -> pair.first }
            }
            if (actual == expected) return
            SystemClock.sleep(25)
        }
        fail("Latest source did not receive its exact token spans")
    }

    private fun editor(activity: LogicStudioActivity): EditText =
        fields(activity.window.decorView).first()

    private fun fields(view: View): List<EditText> = when (view) {
        is EditText -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { fields(view.getChildAt(it)) }
        else -> emptyList()
    }
}
