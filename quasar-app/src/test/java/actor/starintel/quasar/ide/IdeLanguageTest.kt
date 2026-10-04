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
}
