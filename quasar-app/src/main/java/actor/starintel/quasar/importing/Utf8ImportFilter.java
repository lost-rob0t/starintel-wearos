package actor.starintel.quasar.importing;

import android.text.InputFilter;
import android.text.Spanned;

/** Install before restoring a paste draft or accepting keyboard/clipboard input. */
public final class Utf8ImportFilter implements InputFilter {
    private final int maximum;
    public Utf8ImportFilter(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative UTF-8 limit");
        this.maximum = maximum;
    }
    @Override public CharSequence filter(CharSequence source, int start, int end,
                                          Spanned destination, int from, int to) {
        return Utf8Budget.replacementFits(destination, from, to, source, start, end, maximum)
                ? null : destination.subSequence(from, to);
    }
}
