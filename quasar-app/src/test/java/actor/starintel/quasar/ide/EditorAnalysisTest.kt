package actor.starintel.quasar.ide

import org.junit.Assert.*
import org.junit.Test

class EditorAnalysisTest {
    private class Harness {
        var source = "(first)"
        val timers = mutableListOf<Runnable>()
        val workers = ArrayDeque<() -> Unit>()
        val ui = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<List<SourceToken>>()
        var snapshots = 0
        val session = EditorAnalysis(
            delay = { timers += it }, cancelDelay = { timers.remove(it) },
            background = { workers.addLast(it) }, foreground = { ui.addLast(it) },
            snapshot = { snapshots++; source },
            analyze = { HighlightBudget.tokens(it, IdeLanguage.LISP) }, apply = { applied += it },
        )
        fun tick() { val ready = timers.toList(); timers.clear(); ready.forEach { it.run() } }
        fun complete() { workers.removeFirst()(); ui.removeFirst()() }
    }

    @Test fun rapidEditsCaptureOnlyAfterDebounce() {
        val h = Harness()
        repeat(1000) { h.session.changed() }
        assertEquals(1, h.timers.size)
        assertEquals(0, h.snapshots)
        h.tick(); h.complete()
        assertEquals(1, h.snapshots)
        assertEquals(1, h.applied.size)
    }

    @Test fun runningWorkIsSupersededAndQueueIsBounded() {
        val h = Harness()
        h.session.changed(); h.tick()
        repeat(100) { h.source = "x$it"; h.session.changed(); h.tick() }
        assertEquals(1, h.workers.size)
        assertEquals(1, h.snapshots)
        h.complete()
        assertTrue(h.applied.isEmpty())
        assertEquals(1, h.workers.size)
        h.complete()
        assertEquals(LispLanguage.tokenize("x99"), h.applied.single())
        assertEquals(2, h.snapshots)
    }

    @Test fun newEditStillWaitsForItsDebounceWhenOldWorkCompletes() {
        val h = Harness()
        h.session.changed(); h.tick()
        h.session.changed(); h.complete()
        assertTrue(h.workers.isEmpty())
        assertTrue(h.applied.isEmpty())
        h.tick(); h.complete()
        assertEquals(1, h.applied.size)
    }

    @Test fun disposedEditorCannotApplyAndRestoredEditorAnalyzesFreshSource() {
        val old = Harness()
        old.session.changed(); old.tick(); old.session.close(); old.complete()
        assertTrue(old.applied.isEmpty())
        old.session.changed()
        assertTrue(old.timers.isEmpty())
        val restored = Harness()
        restored.source = "(restored 3)"
        restored.session.changed(); restored.tick(); restored.complete()
        assertEquals(LispLanguage.tokenize(restored.source), restored.applied.single())
    }

    @Test fun largePasteAndExcessiveTokenCountUsePlainTextWithoutTruncation() {
        assertTrue(HighlightBudget.tokens("x".repeat(HighlightBudget.MAX_CHARACTERS + 1), IdeLanguage.LISP).isEmpty())
        assertTrue(HighlightBudget.tokens("()".repeat(HighlightBudget.MAX_SPANS), IdeLanguage.LISP).isEmpty())
        for (language in IdeLanguage.entries) {
            val source = if (language == IdeLanguage.LISP) "(defun f () \"hi\") ; comment" else "fact(X, 1.0). % comment"
            assertEquals(LanguageRegistry.implementation(language).tokenize(source), HighlightBudget.tokens(source, language))
        }
    }

    @Test fun byteAccountingMatchesJvmIncludingUnpairedSurrogates() {
        for (source in listOf("ascii", "é€😀", "\uD800", "\uDC00", "a\n", "")) {
            assertEquals(source.toByteArray(Charsets.UTF_8).size, utf8Size(source))
        }
    }
    @Test fun replacementBudgetMatchesUtf8ForEverySurrogateBoundary() {
        val value = "a😀é\uD800z\uDC00"
        for (start in 0..value.length) for (end in start..value.length) {
            for (insert in listOf("", "a", "€", "😀", "\uD800", "\uDC00")) {
                val expected = value.substring(0, start) + insert + value.substring(end)
                assertEquals(expected.toByteArray(Charsets.UTF_8).size,
                    replacementByteCount(value, start, end, insert, 0, insert.length, utf8Size(value)))
            }
        }
        val full = "a".repeat(MAX_EDITOR_BYTES)
        assertEquals(MAX_EDITOR_BYTES + 1, replacementByteCount(full, 0, 0, "b", 0, 1, MAX_EDITOR_BYTES))
        assertEquals(MAX_EDITOR_BYTES, replacementByteCount(full, 0, 1, "b", 0, 1, MAX_EDITOR_BYTES))
        assertEquals(MAX_EDITOR_BYTES - 1, replacementByteCount(full, 0, 1, "", 0, 0, MAX_EDITOR_BYTES))
        val huge = "x".repeat(MAX_EDITOR_BYTES + 1)
        assertEquals(Int.MAX_VALUE, replacementByteCount("", 0, 0, huge, 0, huge.length, 0))
    }
}
