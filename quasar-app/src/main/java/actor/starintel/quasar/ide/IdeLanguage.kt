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

    override fun validate(source: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        val stack = ArrayDeque<Pair<Int, Char>>()
        var index = 0
        var inString = false
        var escaped = false
        var inComment = false
        while (index < source.length) {
            val current = source[index]
            when {
                inComment && current == '\n' -> inComment = false
                inComment -> Unit
                inString && current == '"' && !escaped -> inString = false
                inString -> {
                    escaped = current == '\\' && !escaped
                    if (current != '\\') escaped = false
                }
                current == ';' -> inComment = true
                current == '"' -> inString = true
                current in "([{" -> stack.addLast(index to current)
                current in ")] }".replace(" ", "") -> {
                    val expected = when (current) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.lastOrNull()?.second == expected) stack.removeLast()
                    else diagnostics += diagnosticAt(source, index, "lisp.unexpected-close", "Unexpected closing delimiter")
                }
            }
            index++
        }
        if (inString) diagnostics += diagnosticAt(source, source.lastIndex.coerceAtLeast(0), "lisp.unclosed-string", "String is not closed")
        stack.forEach { (offset, _) -> diagnostics += diagnosticAt(source, offset, "lisp.unclosed-list", "List or form is not closed") }
        return diagnostics
    }
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
        val diagnostics = balancedDelimiters(source, "prolog")
        val lines = source.lineSequence().mapIndexedNotNull { index, rawLine ->
            rawLine.substringBefore('%').trim().takeIf(String::isNotEmpty)?.let { Triple(index + 1, rawLine, it) }
        }.toList()
        lines.forEachIndexed { index, (lineNumber, rawLine, line) ->
            if (line.endsWith(".") || !delimitersBalanced(line) || continuesClause(line)) return@forEachIndexed
            val isLast = index == lines.lastIndex
            val nextStartsClause = !isLast && CLAUSE_HEAD.containsMatchIn(lines[index + 1].third)
            if (isLast || nextStartsClause) {
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
            while (result.size < limit) {
                val found = buffer.source.indexOf(query, from, ignoreCase = true)
                if (found < 0) break
                val prefix = buffer.source.substring(0, found)
                val lineStart = prefix.lastIndexOf('\n') + 1
                result += SearchMatch(
                    sourceName = buffer.name,
                    line = prefix.count { it == '\n' } + 1,
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

private fun balancedDelimiters(source: String, prefix: String): MutableList<Diagnostic> {
    val result = mutableListOf<Diagnostic>()
    val stack = ArrayDeque<Pair<Int, Char>>()
    var quote: Char? = null
    var escaped = false
    var comment = false
    source.forEachIndexed { index, character ->
        when {
            comment && character == '\n' -> comment = false
            comment -> Unit
            quote != null && character == quote && !escaped -> quote = null
            quote != null -> {
                escaped = character == '\\' && !escaped
                if (character != '\\') escaped = false
            }
            character == '%' -> comment = true
            character == '\'' || character == '"' -> quote = character
            character in "([{" -> stack.addLast(index to character)
            character in ")] }".replace(" ", "") -> {
                val expected = when (character) { ')' -> '('; ']' -> '['; else -> '{' }
                if (stack.lastOrNull()?.second == expected) stack.removeLast()
                else result += diagnosticAt(source, index, "$prefix.unexpected-close", "Unexpected closing delimiter")
            }
        }
    }
    stack.forEach { (offset, _) -> result += diagnosticAt(source, offset, "$prefix.unclosed-delimiter", "Delimiter is not closed") }
    if (quote != null) result += diagnosticAt(source, source.lastIndex.coerceAtLeast(0), "$prefix.unclosed-string", "Quoted value is not closed")
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

private fun diagnosticAt(source: String, offset: Int, code: String, message: String): Diagnostic {
    val bounded = offset.coerceIn(0, source.length)
    val prefix = source.substring(0, bounded)
    val lineStart = prefix.lastIndexOf('\n') + 1
    return Diagnostic.error(code, message, prefix.count { it == '\n' } + 1, bounded - lineStart + 1)
}
