package actor.starintel.quasar

import android.text.InputFilter
import android.text.Spanned

/** Install only on non-credential editors. Reject oversized paste before Editable allocates it. */
internal class DraftInputFilter(private val rejected: () -> Unit) : InputFilter {
    override fun filter(source: CharSequence, start: Int, end: Int, dest: Spanned, dstart: Int, dend: Int): CharSequence? {
        if (DraftLimits.replacementFits(source, start, end, dest, dstart, dend)) return null
        rejected()
        // Returning the selected text preserves it when rejecting a replacement paste.
        return dest.subSequence(dstart, dend)
    }
}
