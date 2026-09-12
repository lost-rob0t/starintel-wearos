package actor.starintel.wear.complications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotCatalogTest {
    @Test
    fun stableIdsRemainUniqueAndAddressable() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), SlotCatalog.all.map { it.id })
        assertEquals(SlotCatalog.leftEdge, SlotCatalog.byId(4))
        assertEquals(SlotCatalog.rightEdge, SlotCatalog.byId(5))
        assertEquals(SlotCatalog.activityGraph, SlotCatalog.byId(7))
        assertEquals(SlotCatalog.weatherGeo, SlotCatalog.byId(8))
        assertEquals(null, SlotCatalog.byId(999))
    }

    @Test
    fun everySlotSupportsAnExplicitBlankState() {
        assertTrue(SlotCatalog.all.all { SlotDataType.EMPTY in it.supportedTypes })
    }

    @Test
    fun sideSlotsAcceptTextAndRangedProvidersWithoutInventingConversions() {
        val sides = listOf(SlotCatalog.leftEdge, SlotCatalog.rightEdge)
        assertTrue(sides.all { it.family == SlotFamily.CURVED })
        assertTrue(sides.all { SlotDataType.SHORT_TEXT in it.supportedTypes })
        assertTrue(sides.all { SlotDataType.RANGED_VALUE in it.supportedTypes })
    }

    @Test
    fun circularSlotsAcceptTextAndRangedProviders() {
        val circles = listOf(
            SlotCatalog.lowerLeft,
            SlotCatalog.lowerCenter,
            SlotCatalog.lowerRight,
            SlotCatalog.textRegion,
        )
        assertTrue(circles.all { it.family == SlotFamily.CIRCULAR })
        assertTrue(circles.all { SlotDataType.SHORT_TEXT in it.supportedTypes })
        assertTrue(circles.all { SlotDataType.RANGED_VALUE in it.supportedTypes })
    }

    @Test
    fun panelSlotsExposeOnlyProviderDataTheyCanRender() {
        assertEquals(
            setOf(SlotDataType.SMALL_IMAGE, SlotDataType.EMPTY),
            SlotCatalog.activityGraph.supportedTypes,
        )
        assertEquals(
            setOf(SlotDataType.SHORT_TEXT, SlotDataType.SMALL_IMAGE, SlotDataType.EMPTY),
            SlotCatalog.weatherGeo.supportedTypes,
        )
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
