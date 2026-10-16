package actor.starintel.quasar.ide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeLanguageTest {
    @Test
    fun lispLexerIsDeterministicAndReportsUnbalancedForms() {
        val source = "; policy\n(defparameter *limit* 12)\n(defun ready-p (x) (> x *limit*)"
        val first = LispLanguage.tokenize(source)

        assertEquals(first, LispLanguage.tokenize(source))
        assertTrue(first.any { it.kind == TokenKind.COMMENT && it.text(source) == "; policy" })
        assertTrue(first.any { it.kind == TokenKind.KEYWORD && it.text(source) == "defun" })
        assertTrue(first.any { it.kind == TokenKind.NUMBER && it.text(source) == "12" })
        assertTrue(LispLanguage.validate("(defun broken (x) x").any { it.code == "lisp.unclosed-list" })
    }

    @Test
    fun prologLexerAndValidatorIdentifyCoreForms() {
        val source = "% evidence\nrisk(Person, high) :- score(Person, N), N > 0.8.\n"
        val tokens = PrologLanguage.tokenize(source)

        assertTrue(tokens.any { it.kind == TokenKind.COMMENT })
        assertTrue(tokens.any { it.kind == TokenKind.VARIABLE && it.text(source) == "Person" })
        assertTrue(tokens.any { it.kind == TokenKind.NUMBER && it.text(source) == "0.8" })
        assertTrue(tokens.any { it.kind == TokenKind.OPERATOR && it.text(source) == ":-" })
        assertTrue(PrologLanguage.validate("fact(one)\nfact(two).\n").any { it.code == "prolog.missing-period" })
        assertTrue(PrologLanguage.validate("candidate(X) :-\n  score(X, N),\n  N > 0.8.\n").isEmpty())
    }

    @Test
    fun searchReturnsBoundedLineAndColumnResultsAcrossBuffers() {
        val buffers = listOf(
            SourceBuffer("facts.pl", IdeLanguage.PROLOG, "person(alice).\nperson(bob)."),
            SourceBuffer("rules.pl", IdeLanguage.PROLOG, "known(alice)."),
        )

        val matches = SourceSearch.find(buffers, "alice", limit = 10)

        assertEquals(2, matches.size)
        assertEquals(1, matches.first().line)
        assertEquals(8, matches.first().column)
    }
    @Test
    fun adversarialDiagnosticWorkIsLinearAndOutputBounded() {
        for (language in IdeLanguage.entries) {
            for (size in listOf(1024, 4096, 16384, 65536, 262144)) {
                for (delimiter in listOf("(", ")")) {
                    val work = DiagnosticWork()
                    val source = delimiter.repeat(size)
                    val diagnostics = structuralDiagnostics(source, language, work)
                    assertEquals(size, work.characters)
                    assertTrue(work.remainingOpeners <= MAX_SOURCE_DIAGNOSTICS)
                    assertEquals(MAX_SOURCE_DIAGNOSTICS, work.emitted)
                    assertEquals(MAX_SOURCE_DIAGNOSTICS, diagnostics.size)
                    assertEquals((1..MAX_SOURCE_DIAGNOSTICS).toList(), diagnostics.map { it.column })
                    assertEquals(MAX_SOURCE_DIAGNOSTICS, LanguageRegistry.implementation(language).validate(source).size)
                }
            }
        }
        assertEquals(MAX_SOURCE_DIAGNOSTICS, PrologLanguage.validate("fact(x)\n".repeat(1000)).size)
    }

    @Test
    fun diagnosticsPreserveMultilinePositionsQuotesAndOrder() {
        val lisp = LispLanguage.validate("; ) ignored\n  (\n ]\n \"unterminated")
        assertEquals(listOf("lisp.unexpected-close", "lisp.unclosed-string", "lisp.unclosed-list"), lisp.map { it.code })
        assertEquals(listOf(3 to 2, 4 to 14, 2 to 3), lisp.map { it.line to it.column })
        val prolog = structuralDiagnostics("% ) ignored\n  [\n )\n 'x", IdeLanguage.PROLOG)
        assertEquals(listOf("prolog.unexpected-close", "prolog.unclosed-delimiter", "prolog.unclosed-string"), prolog.map { it.code })
        assertEquals(listOf(3 to 2, 2 to 3, 4 to 3), prolog.map { it.line to it.column })
        assertTrue(LispLanguage.validate("(print \"escaped \\\" )\")").isEmpty())
        assertTrue(PrologLanguage.validate("fact(')').").isEmpty())
    }

    @Test
    fun searchPositionsMatchReferenceAcrossNewlinesAndUnicode() {
        val source = "é😀x\nXx\n\nx"
        val matches = SourceSearch.find(listOf(SourceBuffer("test", IdeLanguage.LISP, source)), "x", 1000)
        assertEquals(listOf(1 to 4, 2 to 1, 2 to 2, 4 to 1), matches.map { it.line to it.column })
        assertEquals(2, SourceSearch.find(listOf(SourceBuffer("test", IdeLanguage.LISP, source)), "x", 2).size)
        val spanning = SourceSearch.find(listOf(SourceBuffer("test", IdeLanguage.LISP, "a\na\na\na")), "a\na")
        assertEquals(listOf(1 to 1, 3 to 1), spanning.map { it.line to it.column })
    }
}
