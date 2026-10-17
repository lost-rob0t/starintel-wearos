package actor.starintel.quasar.ide

/** Coloring is optional; editing and validation remain available beyond this budget. */
internal object HighlightBudget {
    const val MAX_CHARACTERS = 32 * 1024
    const val MAX_SPANS = 4096

    fun tokens(source: String, language: IdeLanguage): List<SourceToken> {
        if (source.length > MAX_CHARACTERS) return emptyList()
        val tokens = LanguageRegistry.implementation(language).tokenize(source)
        return if (tokens.size <= MAX_SPANS) tokens else emptyList()
    }
}

/** All methods/callbacks except [analyze] run on the editor's UI dispatcher. */
internal class EditorAnalysis(
    private val delay: (Runnable) -> Unit,
    private val cancelDelay: (Runnable) -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val foreground: (() -> Unit) -> Unit,
    private val snapshot: () -> String,
    private val analyze: (String) -> List<SourceToken>,
    private val apply: (List<SourceToken>) -> Unit,
) {
    private var version = 0L
    private var closed = false
    private var running = false
    private var ready = false
    private val pending = Runnable {
        ready = true
        launchLatest()
    }

    fun changed() {
        if (closed) return
        version++
        ready = false
        cancelDelay(pending)
        delay(pending)
    }

    private fun launchLatest() {
        if (closed || running || !ready) return
        ready = false
        running = true
        val revision = version
        val source = snapshot()
        background {
            val tokens = analyze(source)
            foreground {
                running = false
                if (!closed && revision == version) apply(tokens)
                launchLatest()
            }
        }
    }

    fun close() {
        closed = true
        version++
        ready = false
        cancelDelay(pending)
    }
}

/** Incremental UTF-8 accounting: inspect only replaced/inserted text for normal edits.
 * A split surrogate boundary can change encoding of its neighbours; the Android
 * adapter includes one neighbour on each side when computing its delta.
 */
internal fun utf8Size(value: CharSequence, start: Int = 0, end: Int = value.length): Int {
    var bytes = 0
    var index = start
    while (index < end) {
        val c = value[index++]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && index < end && value[index].isLowSurrogate() -> { index++; 4 }
            c.isSurrogate() -> 1 // JVM UTF-8 replacement for an unpaired surrogate.
            else -> 3
        }
    }
    return bytes
}

internal const val MAX_EDITOR_BYTES = 256 * 1024

/** Include only neighbours which could pair with a changed UTF-16 surrogate. */
internal fun replacementWindow(value: CharSequence, start: Int, end: Int): IntRange {
    val left = if (start > 0 && value[start - 1].isHighSurrogate()) start - 1 else start
    val right = if (end < value.length && value[end].isLowSurrogate()) end + 1 else end
    return left until right
}

internal fun replacementByteCount(
    value: CharSequence, start: Int, end: Int,
    inserted: CharSequence, insertStart: Int, insertEnd: Int, currentBytes: Int,
): Int {
    // Avoid allocating an arbitrarily large paste before checking its size.
    if (insertEnd - insertStart > MAX_EDITOR_BYTES) return Int.MAX_VALUE
    val window = replacementWindow(value, start, end)
    val replacement = buildString {
        append(value, window.first, start)
        append(inserted, insertStart, insertEnd)
        append(value, end, window.last + 1)
    }
    return currentBytes - utf8Size(value, window.first, window.last + 1) + utf8Size(replacement)
}
