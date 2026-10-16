package actor.starintel.quasar.importing;

/** Allocation-free early input budget. Unpaired UTF-16 surrogates count conservatively as 3 bytes. */
public final class Utf8Budget {
    private Utf8Budget() {}
    public static boolean fits(CharSequence text, int maximum) {
        return replacementFits(text, 0, 0, "", 0, 0, maximum);
    }
    public static boolean replacementFits(CharSequence destination, int from, int to,
                                          CharSequence source, int start, int end, int maximum) {
        if (maximum < 0 || from < 0 || to < from || to > destination.length() ||
                start < 0 || end < start || end > source.length()) throw new IllegalArgumentException("Invalid text range");
        long length = (long) destination.length() - (to - from) + end - start;
        long bytes = 0;
        for (long i = 0; i < length; i++) {
            char c = at(destination, from, to, source, start, end, i);
            if (c <= 0x7f) bytes++;
            else if (c <= 0x7ff) bytes += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < length &&
                    Character.isLowSurrogate(at(destination, from, to, source, start, end, i + 1))) {
                bytes += 4; i++;
            } else bytes += 3;
            if (bytes > maximum) return false;
        }
        return true;
    }
    private static char at(CharSequence destination, int from, int to, CharSequence source, int start, int end, long i) {
        if (i < from) return destination.charAt((int)i);
        if (i < (long)from + end - start) return source.charAt((int)(i - from + start));
        return destination.charAt((int)(i - from - (end - start) + to));
    }
}
