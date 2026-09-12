package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryTest {
    @Test
    fun flatSeriesProducesRealZeroActivity() {
        val state = record(
            sample(NOW - 600L, 100L),
            sample(NOW - 300L, 100L),
            sample(NOW, 100L),
        )
        assertEquals(listOf(0L, 0L), values(state, ActivityRange.H1))
    }

    @Test
    fun spikeIsPreservedWithoutSyntheticSmoothing() {
        val state = record(
            sample(NOW - 600L, 100L),
            sample(NOW - 300L, 105L),
            sample(NOW, 10_105L),
        )
        assertEquals(listOf(5L, 10_000L), values(state, ActivityRange.H1))
    }

    @Test
    fun longOfflineIntervalIsAGapNotAFakeSpike() {
        val state = record(
            sample(NOW - 3600L, 100L),
            sample(NOW, 500L),
        )
        val points = ActivityHistoryModel.points(state, ActivityRange.H6, NOW)
        assertEquals(1, points.size)
        assertTrue(points.single().gap)
        assertNull(points.single().documentsAdded)
    }

    @Test
    fun counterResetIsExplicitAndNeverNegative() {
        val state = record(
            sample(NOW - 300L, 10_000L),
            sample(NOW, 5L),
        )
        val point = ActivityHistoryModel.points(state, ActivityRange.H1, NOW).single()
        assertTrue(point.reset)
        assertNull(point.documentsAdded)
    }

    @Test
    fun sameTimestampReplacesInsteadOfDuplicating() {
        val state = record(
            sample(NOW - 300L, 10L),
            sample(NOW, 20L),
            sample(NOW, 25L),
        )
        assertEquals(listOf(15L), values(state, ActivityRange.H1))
    }

    @Test
    fun sevenAndThirtyDayRangesUseBoundedHourlyAnchors() {
        var state = ActivityHistoryState()
        for (hour in 0..900) {
            state = ActivityHistoryModel.record(
                state,
                sample(NOW - (900L - hour.toLong()) * 3600L, hour.toLong()),
            )
        }
        assertTrue(state.hourly.size <= 760)
        assertTrue(ActivityHistoryModel.points(state, ActivityRange.D7, NOW).isNotEmpty())
        assertTrue(ActivityHistoryModel.points(state, ActivityRange.D30, NOW).isNotEmpty())
    }

    @Test
    fun rangeFilteringDoesNotLeakOlderSamples() {
        val state = record(
            sample(NOW - 8L * 3600L, 1L),
            sample(NOW - 50L * 60L, 10L),
            sample(NOW - 40L * 60L, 20L),
            sample(NOW, 30L),
        )
        val points = ActivityHistoryModel.points(state, ActivityRange.H1, NOW)
        assertTrue(points.all { it.epochSeconds >= NOW - ActivityRange.H1.seconds })
    }

    private fun values(state: ActivityHistoryState, range: ActivityRange): List<Long?> =
        ActivityHistoryModel.points(state, range, NOW).map { it.documentsAdded }

    private fun record(vararg samples: ActivitySample): ActivityHistoryState =
        samples.fold(ActivityHistoryState()) { state, sample ->
            ActivityHistoryModel.record(state, sample)
        }

    private fun sample(timestamp: Long, total: Long) = ActivitySample(timestamp, total)

    companion object {
        private const val NOW = 1_800_000_000L
    }
}
