package actor.starintel.quasar.importing;

import org.junit.Test;
import static org.junit.Assert.*;

public class Utf8BudgetTest {
    @Test public void unicodeCountsBytesAndPreservesPairsAcrossReplacement() {
        assertTrue(Utf8Budget.fits("é😀", 6)); assertFalse(Utf8Budget.fits("é😀", 5));
        assertFalse(Utf8Budget.fits("\ud800", 2));
        assertTrue(Utf8Budget.replacementFits("\ud83d", 1, 1, "\ude00", 0, 1, 4));
        assertFalse(Utf8Budget.replacementFits("\ud83d", 1, 1, "\ude00", 0, 1, 3));
        assertTrue(Utf8Budget.replacementFits("123456", 1, 5, "é", 0, 1, 4));
    }
    @Test public void hugePasteStopsAtBudgetWithoutCopying() {
        CharSequence huge = new CharSequence() {
            public int length() { return Integer.MAX_VALUE; }
            public char charAt(int i) { assertTrue(i <= 1024); return 'a'; }
            public CharSequence subSequence(int start, int end) { throw new AssertionError("copy"); }
            public String toString() { throw new AssertionError("copy"); }
        };
        assertFalse(Utf8Budget.replacementFits("", 0, 0, huge, 0, huge.length(), 1024));
    }
}
