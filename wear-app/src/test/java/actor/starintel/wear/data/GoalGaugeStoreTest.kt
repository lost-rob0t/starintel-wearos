package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalGaugeStoreTest {
    private val snapshot = StarIntelSnapshot(
        configured = true,
        reachable = true,
        documentsTotal = 750L,
        documentsByType = mapOf("person" to 42L, "target" to 12L),
        targetsTotal = 12L,
    )

    @Test
    fun documentsGoalProducesBoundedRangedProgress() {
        val reading = goalGaugeReading(
            GoalGaugeMetric.DOCUMENTS,
            GoalGaugeConfig(documentsGoal = 1_000L),
            snapshot,
        )

        assertTrue(reading.available)
        assertEquals(75, reading.percent)
        assertEquals(750f, reading.rangedValue)
        assertEquals(1_000f, reading.rangedMax)
    }

    @Test
    fun progressClampsAtGoalForRangedProviders() {
        val reading = goalGaugeReading(
            GoalGaugeMetric.TARGETS,
            GoalGaugeConfig(targetsGoal = 5L),
            snapshot,
        )

        assertEquals(100, reading.percent)
        assertEquals(5f, reading.rangedValue)
    }

    @Test
    fun arbitraryDocumentTypeGoalUsesStatsTypeCount() {
        val reading = goalGaugeReading(
            GoalGaugeMetric.DOCUMENT_TYPE,
            GoalGaugeConfig(documentType = "person", documentTypeGoal = 100L),
            snapshot,
        )

        assertTrue(reading.available)
        assertEquals(42L, reading.current)
        assertEquals(42, reading.percent)
        assertEquals("PERSON", reading.label)
    }

    @Test
    fun dtypeGoalIsUnavailableWithoutConfiguredType() {
        val reading = goalGaugeReading(
            GoalGaugeMetric.DOCUMENT_TYPE,
            GoalGaugeConfig(documentTypeGoal = 100L),
            snapshot,
        )

        assertFalse(reading.available)
    }

    @Test
    fun netGrowthIgnoresGapAndResetIntervals() {
        val result = hourlyNetGrowth(
            listOf(
                ActivityPoint(1L, 4L),
                ActivityPoint(2L, null, gap = true),
                ActivityPoint(3L, 6L),
                ActivityPoint(4L, null, reset = true),
            ),
        )

        assertEquals(10L, result)
        assertNull(hourlyNetGrowth(listOf(ActivityPoint(1L, null, gap = true))))
    }
}
