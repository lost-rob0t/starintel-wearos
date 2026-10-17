package actor.starintel.quasar.ide

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LogicDraftJourneyTest {
    @Test fun sourceAnalysisWidensConcreteFilterArrays() {
        ActivityScenario.launch(LogicStudioActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val limit = android.text.InputFilter.LengthFilter(16)
                // A separate short-lived field avoids installing a second analyzer on the editor.
                val isolated = EditText(activity).apply { filters = arrayOf(limit) }
                val analysis = SourceEditorAnalysis(isolated, IdeLanguage.LISP, { 0 }, {}, {})
                try {
                    assertEquals(android.text.InputFilter::class.java, isolated.filters.javaClass.componentType)
                    assertSame(limit, isolated.filters.first())
                    isolated.text.append("a".repeat(20))
                    assertEquals(16, isolated.length())
                } finally { analysis.onViewDetachedFromWindow(isolated) }
            }
        }
    }

    @Test fun dirtySourceSurvivesLanguageNavigationAndRotation() {
        ActivityScenario.launch(LogicStudioActivity::class.java).use { scenario ->
            val source = "; unsaved bounded draft\n(defun preserved () 42)"
            scenario.onActivity { activity ->
                editor(activity).setText(source)
                click(activity, "Prolog")
                click(activity, "Lisp")
                assertEquals(source, editor(activity).text.toString())
            }
            scenario.recreate()
            scenario.onActivity { assertEquals(source, editor(it).text.toString()) }
        }
    }

    @Test fun pendingRestoreReviewCannotBeBypassedByRotation() {
        ActivityScenario.launch(LogicStudioActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                LogicStudioActivity::class.java.getDeclaredField("unreviewedRestore").apply {
                    isAccessible = true; setBoolean(activity, true)
                }
            }
            repeat(2) {
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertFalse(views(activity.window.decorView).any { it is EditText })
                    assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString() == "Review restored drafts"
                    })
                }
            }
            scenario.onActivity { activity ->
                click(activity, "Review restored drafts")
                assertNotNull(editor(activity))
            }
        }
    }

    @Test fun missingEnvelopeBlocksEditorUntilExplicitDiscard() {
        ActivityScenario.launch(LogicStudioActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                // Saved-state corruption fixture, not a production backdoor.
                val field = LogicStudioActivity::class.java.getDeclaredField("draftReferences").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val references = field.get(activity) as MutableMap<String, String>
                references["logic:LISP:missing-fixture.lisp"] = "a".repeat(64)
            }
            scenario.recreate()
            val deadline = SystemClock.uptimeMillis() + 5000
            var blocked = false
            while (!blocked && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    blocked = views(activity.window.decorView).filterIsInstance<TextView>().any {
                        it.text.toString().startsWith("Saved draft could not be read")
                    }
                }
                if (!blocked) SystemClock.sleep(25)
            }
            assertTrue("Missing draft must fail visibly", blocked)
            scenario.onActivity { activity ->
                assertFalse(views(activity.window.decorView).any { it is EditText })
                click(activity, "Prolog")
                assertFalse(views(activity.window.decorView).any { it is EditText })
                click(activity, "Discard unavailable drafts")
                assertNotNull(editor(activity))
            }
        }
    }

    private fun editor(activity: LogicStudioActivity) = views(activity.window.decorView).filterIsInstance<EditText>().first()
    private fun click(activity: LogicStudioActivity, label: String) {
        check(views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick())
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
}
