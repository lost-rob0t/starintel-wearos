package actor.starintel.quasar.ide

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.EditText
import java.util.concurrent.Executors

/** One bounded worker per attached source editor, disposed on detach (including recreation).
 * Install on a newly created field, including fields created from restored draft content.
 */
internal class SourceEditorAnalysis(
    private val field: EditText,
    private val language: IdeLanguage,
    private val tokenColor: (TokenKind) -> Int,
    private val rejected: () -> Unit,
    private val changed: () -> Unit,
) : TextWatcher, View.OnAttachStateChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private var worker = Executors.newSingleThreadExecutor()
    private var session: EditorAnalysis? = null
    private var applying = false
    private var bytes = utf8Size(field.text)
    private var oldWindowBytes = 0
    private var left = 0
    private var rightExtra = 0

    init {
        // Source input is rejected as a whole, not silently truncated. Existing
        // oversized legacy drafts may be reduced, but cannot grow further.
        field.filters = field.filters + InputFilter { source, start, end, dest, dstart, dend ->
            // TextView.setText filters against an empty destination, unlike an
            // Editable replacement. Do not reuse the old field's byte count there.
            val destinationBytes = if (dest === field.text) bytes else utf8Size(dest)
            val next = replacementByteCount(dest, dstart, dend, source, start, end, destinationBytes)
            if (next <= MAX_EDITOR_BYTES || next < destinationBytes) null else {
                rejected()
                dest.subSequence(dstart, dend)
            }
        }
        field.addTextChangedListener(this)
        field.addOnAttachStateChangeListener(this)
        startSession()
    }

    private fun startSession() {
        if (session != null) return
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        session = EditorAnalysis(
            delay = { handler.postDelayed(it, 150) },
            cancelDelay = { handler.removeCallbacks(it) },
            background = { task -> worker.execute { task() } },
            foreground = { task -> handler.post { task() } },
            snapshot = { field.text.toString() },
            analyze = { HighlightBudget.tokens(it, language) },
            apply = ::applyTokens,
        ).also { it.changed() }
    }

    private fun applyTokens(tokens: List<SourceToken>) {
        applying = true
        try {
            val editable = field.text
            editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach(editable::removeSpan)
            tokens.forEach { token ->
                editable.setSpan(ForegroundColorSpan(tokenColor(token.kind)), token.start, token.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } finally {
            applying = false
        }
    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
        if (applying) return
        val window = replacementWindow(s, start, start + count)
        left = window.first
        rightExtra = window.last + 1 - start - count
        oldWindowBytes = utf8Size(s, window.first, window.last + 1)
    }

    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        if (!applying) bytes += utf8Size(s, left, start + count + rightExtra) - oldWindowBytes
    }

    override fun afterTextChanged(s: Editable) {
        if (!applying) {
            changed()
            session?.changed()
        }
    }

    override fun onViewAttachedToWindow(v: View) = startSession()

    override fun onViewDetachedFromWindow(v: View) {
        session?.close()
        session = null
        worker.shutdown()
    }
}
