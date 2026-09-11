package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StarIntelFormattingTest {
    @Test
    fun compactCountKeepsSmallValuesReadable() {
        assertEquals("999", 999L.compactCount())
    }

    @Test
    fun compactCountAbbreviatesLargeValues() {
        assertEquals("1.2K", 1_200L.compactCount())
        assertEquals("2.5M", 2_500_000L.compactCount())
    }
}
