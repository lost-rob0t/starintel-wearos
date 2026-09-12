package actor.starintel.wear.complications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCatalogTest {
    @Test
    fun stableIdsRemainUniqueAndAddressable() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6), SlotCatalog.all.map { it.id })
        assertEquals(SlotCatalog.leftEdge, SlotCatalog.byId(4))
        assertEquals(SlotCatalog.rightEdge, SlotCatalog.byId(5))
        assertEquals(null, SlotCatalog.byId(999))
    }

    @Test
    fun everySlotSupportsAnExplicitBlankState() {
        assertTrue(SlotCatalog.all.all { SlotDataType.EMPTY in it.supportedTypes })
    }

    @Test
    fun sideSlotsAreRealRangedValueSlots() {
        val sides = listOf(SlotCatalog.leftEdge, SlotCatalog.rightEdge)
        assertTrue(sides.all { it.family == SlotFamily.CURVED_RANGED })
        assertTrue(sides.all { SlotDataType.RANGED_VALUE in it.supportedTypes })
        assertTrue(sides.all { SlotDataType.SHORT_TEXT !in it.supportedTypes })
    }

    @Test
    fun circularSlotsAcceptTextAndRangedProviders() {
        val circles = listOf(SlotCatalog.lowerLeft, SlotCatalog.lowerCenter, SlotCatalog.lowerRight)
        assertTrue(circles.all { it.family == SlotFamily.CIRCULAR })
        assertTrue(circles.all { SlotDataType.SHORT_TEXT in it.supportedTypes })
        assertTrue(circles.all { SlotDataType.RANGED_VALUE in it.supportedTypes })
    }

    @Test
    fun allBlankFixtureStaysBlank() {
        val states = SlotCatalog.all.map { SlotCatalog.visualState(false, false) }
        assertTrue(states.all { it == SlotVisualState.BLANK })
    }

    @Test
    fun allConfiguredFixtureRendersData() {
        val states = SlotCatalog.all.map { SlotCatalog.visualState(true, true) }
        assertTrue(states.all { it == SlotVisualState.CONFIGURED })
    }

    @Test
    fun mixedConfiguredAndBlankFixtureIsDeterministic() {
        val states = listOf(
            SlotCatalog.visualState(true, true),
            SlotCatalog.visualState(false, false),
            SlotCatalog.visualState(true, true),
            SlotCatalog.visualState(false, true),
        )
        assertEquals(
            listOf(
                SlotVisualState.CONFIGURED,
                SlotVisualState.BLANK,
                SlotVisualState.CONFIGURED,
                SlotVisualState.BLANK,
            ),
            states,
        )
    }

    @Test
    fun unavailableProviderNeverCreatesFallbackData() {
        assertEquals(SlotVisualState.BLANK, SlotCatalog.visualState(true, false))
    }
}
