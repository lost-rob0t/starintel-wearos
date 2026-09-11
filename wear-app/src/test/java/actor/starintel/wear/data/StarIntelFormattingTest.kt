package actor.starintel.wear.data

import kotlin.test.Test
import kotlin.test.assertEquals

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
