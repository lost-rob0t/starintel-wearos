package actor.starintel.quasar.ide

enum class IdeLanguage(val directory: String, val extension: String, val displayName: String) {
    LISP("lisp", "lisp", "Common Lisp"),
    PROLOG("prolog", "pl", "Prolog"),
}

enum class TokenKind {
    COMMENT,
    KEYWORD,
    SYMBOL,
    VARIABLE,
    NUMBER,
    STRING,
    OPERATOR,
    PUNCTUATION,
}

data class SourceToken(
    val kind: TokenKind,
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0 && endExclusive >= start)
    }

    fun text(source: String): String = source.substring(start, endExclusive)
}

enum class DiagnosticSeverity { INFO, WARNING, ERROR }

data class Diagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val line: Int = 1,
    val column: Int = 1,
) {
    companion object {
        fun error(code: String, message: String, line: Int = 1, column: Int = 1) =
            Diagnostic(DiagnosticSeverity.ERROR, code, message, line, column)

        fun warning(code: String, message: String, line: Int = 1, column: Int = 1) =
            Diagnostic(DiagnosticSeverity.WARNING, code, message, line, column)
    }
}

data class SourceBuffer(
    val name: String,
    val language: IdeLanguage,
    val source: String,
    val dirty: Boolean = false,
)

fun interface ValidationHook {
    fun validate(buffer: SourceBuffer): List<Diagnostic>
}

interface SourceLanguage {
    fun tokenize(source: String): List<SourceToken>
    fun validate(source: String): List<Diagnostic>
}

object LispLanguage : SourceLanguage {
    private val keywords = setOf(
        "and", "block", "case", "cond", "defclass", "defconstant", "defgeneric", "define-condition",
        "defmacro", "defmethod", "defpackage", "defparameter", "defstruct", "deftype", "defun", "defvar",
        "do", "dolist", "dotimes", "flet", "function", "handler-bind", "handler-case", "if", "labels",
        "lambda", "let", "let*", "loop", "multiple-value-bind", "or", "progn", "quote", "restart-case",
        "setf", "unless", "unwind-protect", "when",
    )

    override fun tokenize(source: String): List<SourceToken> {
        val result = mutableListOf<SourceToken>()
        var index = 0
        while (index < source.length) {
            val character = source[index]
            when {
                character.isWhitespace() -> index++
                character == ';' -> {
                    val start = index
                    while (index < source.length && source[index] != '\n') index++
                    result += SourceToken(TokenKind.COMMENT, start, index)
                }
                character == '"' -> {
                    val start = index++
                    var escaped = false
                    while (index < source.length) {
                        val current = source[index++]
                        if (current == '"' && !escaped) break
                        escaped = current == '\\' && !escaped
                        if (current != '\\') escaped = false
                    }
                    result += SourceToken(TokenKind.STRING, start, index)
                }
                character.isDigit() || (character in "+-" && source.getOrNull(index + 1)?.isDigit() == true) -> {
                    val start = index++
                    while (index < source.length && (source[index].isDigit() || source[index] in ".eEdDfF+-")) index++
                    result += SourceToken(TokenKind.NUMBER, start, index)
                }
                character in "()[]{}" -> result.add(SourceToken(TokenKind.PUNCTUATION, index, ++index))
                character in "'`," -> result.add(SourceToken(TokenKind.OPERATOR, index, ++index))
                else -> {
                    val start = index
                    while (index < source.length && !source[index].isWhitespace() && source[index] !in "()[]{}'`,\";") index++
                    val word = source.substring(start, index)
                    val normalized = word.lowercase().removePrefix("#'")
                    result += SourceToken(if (normalized in keywords || word.startsWith(":")) TokenKind.KEYWORD else TokenKind.SYMBOL, start, index)
                }
            }
        }
        return result
    }

    override fun validate(source: String): List<Diagnostic> = structuralDiagnostics(source, IdeLanguage.LISP)

}

object PrologLanguage : SourceLanguage {
    override fun tokenize(source: String): List<SourceToken> {
        val result = mutableListOf<SourceToken>()
        var index = 0
        while (index < source.length) {
            val character = source[index]
            when {
                character.isWhitespace() -> index++
                character == '%' -> {
                    val start = index
                    while (index < source.length && source[index] != '\n') index++
                    result += SourceToken(TokenKind.COMMENT, start, index)
                }
                character == '\'' || character == '"' -> {
                    val delimiter = character
                    val start = index++
                    var escaped = false
                    while (index < source.length) {
                        val current = source[index++]
                        if (current == delimiter && !escaped) break
                        escaped = current == '\\' && !escaped
                        if (current != '\\') escaped = false
                    }
                    result += SourceToken(TokenKind.STRING, start, index)
                }
                character.isDigit() -> {
                    val start = index++
                    var decimalSeen = false
                    while (index < source.length) {
                        val current = source[index]
                        when {
                            current.isDigit() -> index++
                            current == '.' && !decimalSeen && source.getOrNull(index + 1)?.isDigit() == true -> {
                                decimalSeen = true
                                index++
                            }
                            current in "eE" && source.getOrNull(index + 1)?.let { it.isDigit() || it in "+-" } == true -> {
                                index++
                                if (source.getOrNull(index)?.let { it == '+' || it == '-' } == true) index++
                            }
                            else -> break
                        }
                    }
                    result += SourceToken(TokenKind.NUMBER, start, index)
                }
                character.isLetter() || character == '_' -> {
                    val start = index++
                    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
                    val kind = if (character.isUpperCase() || character == '_') TokenKind.VARIABLE else TokenKind.SYMBOL
                    result += SourceToken(kind, start, index)
                }
                source.startsWith(":-", index) || source.startsWith("?-", index) || source.startsWith(">=", index) ||
                    source.startsWith("=<", index) || source.startsWith("\\=", index) || source.startsWith("==", index) -> {
                    result += SourceToken(TokenKind.OPERATOR, index, index + 2)
                    index += 2
                }
                character in "=<>+*/\\-" -> result.add(SourceToken(TokenKind.OPERATOR, index, ++index))
                else -> result.add(SourceToken(TokenKind.PUNCTUATION, index, ++index))
            }
        }
        return result
    }

    override fun validate(source: String): List<Diagnostic> {
        val diagnostics = structuralDiagnostics(source, IdeLanguage.PROLOG).toMutableList()
        val lines = source.lineSequence().mapIndexedNotNull { index, rawLine ->
            rawLine.substringBefore('%').trim().takeIf(String::isNotEmpty)?.let { Triple(index + 1, rawLine, it) }
        }.toList()
        lines.forEachIndexed { index, (lineNumber, rawLine, line) ->
            if (line.endsWith(".") || !delimitersBalanced(line) || continuesClause(line)) return@forEachIndexed
            val isLast = index == lines.lastIndex
            val nextStartsClause = !isLast && CLAUSE_HEAD.containsMatchIn(lines[index + 1].third)
            if ((isLast || nextStartsClause) && diagnostics.size < MAX_SOURCE_DIAGNOSTICS) {
                diagnostics += Diagnostic.error("prolog.missing-period", "Clause must end with a period", lineNumber, rawLine.length.coerceAtLeast(1))
            }
        }
        return diagnostics
    }

    private fun continuesClause(line: String): Boolean =
        listOf(":-", ",", ";", "->", "\\+", "=", ">", "<").any(line::endsWith)

    private val CLAUSE_HEAD = Regex("^(?::-\\s*)?[a-z][a-zA-Z0-9_]*\\s*(?:\\(|\\.)")
}

object LanguageRegistry {
    fun implementation(language: IdeLanguage): SourceLanguage = when (language) {
        IdeLanguage.LISP -> LispLanguage
        IdeLanguage.PROLOG -> PrologLanguage
    }
}

data class SearchMatch(
    val sourceName: String,
    val line: Int,
    val column: Int,
    val start: Int,
    val endExclusive: Int,
)

object SourceSearch {
    fun find(buffers: List<SourceBuffer>, query: String, limit: Int = 100): List<SearchMatch> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val result = mutableListOf<SearchMatch>()
        buffers.forEach { buffer ->
            var from = 0
            var positionOffset = 0
            var line = 1
            var lineStart = 0
            while (result.size < limit) {
                val found = buffer.source.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                while (positionOffset < found) {
                    if (buffer.source[positionOffset] == '\n') {
                        line++
                        lineStart = positionOffset + 1
                    }
                    positionOffset++
                }
                result += SearchMatch(
                    sourceName = buffer.name,
                    line = line,
                    column = found - lineStart + 1,
                    start = found,
                    endExclusive = found + query.length,
                )
                from = found + query.length.coerceAtLeast(1)
            }
        }
        return result
    }
}

/** Production cap, applied before constructing diagnostic messages/objects. */
internal const val MAX_SOURCE_DIAGNOSTICS = 25

/** Deterministic operation counts for regression tests, not wall-clock measurements. */
internal class DiagnosticWork {
    var characters = 0
    var remainingOpeners = 0
    var emitted = 0
}

private data class OpenDelimiter(val character: Char, val line: Int, val column: Int)

internal fun structuralDiagnostics(
    source: String,
    language: IdeLanguage,
    work: DiagnosticWork? = null,
): List<Diagnostic> {
    val result = mutableListOf<Diagnostic>()
    val stack = ArrayDeque<OpenDelimiter>()
    val prefix = if (language == IdeLanguage.LISP) "lisp" else "prolog"
    var line = 1
    var column = 1
    var lastLine = 1
    var lastColumn = 1
    fun emit(code: String, message: String, atLine: Int, atColumn: Int) {
        if (result.size < MAX_SOURCE_DIAGNOSTICS) {
            result += Diagnostic.error(code, message, atLine, atColumn)
            work?.let { it.emitted++ }
        }
    }
    var quote: Char? = null
    var escaped = false
    var comment = false
    source.forEach { character ->
        work?.let { it.characters++ }
        lastLine = line
        lastColumn = column
        when {
            comment && character == '\n' -> comment = false
            comment -> Unit
            quote != null && character == quote && !escaped -> quote = null
            quote != null -> {
                escaped = character == '\\' && !escaped
                if (character != '\\') escaped = false
            }
            character == (if (language == IdeLanguage.LISP) ';' else '%') -> comment = true
            character == '"' || (language == IdeLanguage.PROLOG && character == '\'') -> quote = character
            character in "([{" -> stack.addLast(OpenDelimiter(character, line, column))
            character in ")]}" -> {
                val expected = when (character) { ')' -> '('; ']' -> '['; else -> '{' }
                if (stack.lastOrNull()?.character == expected) stack.removeLast()
                else emit("$prefix.unexpected-close", "Unexpected closing delimiter", line, column)
            }
        }
        if (character == '\n') { line++; column = 1 } else column++
    }
    fun unclosedString() {
        if (quote != null) emit("$prefix.unclosed-string",
            if (language == IdeLanguage.LISP) "String is not closed" else "Quoted value is not closed",
            lastLine, lastColumn)
    }
    // Preserve each language's existing diagnostic order, including the EOF position.
    if (language == IdeLanguage.LISP) unclosedString()
    for (opener in stack) {
        if (result.size == MAX_SOURCE_DIAGNOSTICS) break
        work?.let { it.remainingOpeners++ }
        emit(if (language == IdeLanguage.LISP) "lisp.unclosed-list" else "prolog.unclosed-delimiter",
            if (language == IdeLanguage.LISP) "List or form is not closed" else "Delimiter is not closed",
            opener.line, opener.column)
    }
    if (language == IdeLanguage.PROLOG) unclosedString()
    return result
}

private fun delimitersBalanced(value: String): Boolean {
    var depth = 0
    value.forEach { character ->
        if (character in "([{") depth++
        if (character in ")]}") depth--
    }
    return depth == 0
}
