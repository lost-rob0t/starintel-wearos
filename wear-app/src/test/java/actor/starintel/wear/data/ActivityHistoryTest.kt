package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryTest {
    @Test fun flatSeriesProducesRealZeroActivity() {
        val state = record(sample(NOW - 600, 100), sample(NOW - 300, 100), sample(NOW, 100))
        assertEquals(listOf(0L, 0L), values(state, ActivityRange.H1))
    }
    @Test fun spikeIsPreservedWithoutSyntheticSmoothing() {
        val state = record(sample(NOW - 600, 100), sample(NOW - 300, 105), sample(NOW, 10_105))
        assertEquals(listOf(5L, 10_000L), values(state, ActivityRange.H1))
    }
    @Test fun longOfflineIntervalIsAGapNotAFakeSpike() {
        val point = ActivityHistoryModel.points(record(sample(NOW - 3600, 100), sample(NOW, 500)), ActivityRange.H6, NOW).single()
        assertTrue(point.gap); assertNull(point.documentsAdded)
    }
    @Test fun counterResetIsExplicitAndNeverNegative() {
        val point = ActivityHistoryModel.points(record(sample(NOW - 300, 10_000), sample(NOW, 5)), ActivityRange.H1, NOW).single()
        assertTrue(point.reset); assertNull(point.documentsAdded)
    }
    @Test fun sameTimestampReplacesInsteadOfDuplicating() {
        assertEquals(listOf(15L), values(record(sample(NOW - 300, 10), sample(NOW, 20), sample(NOW, 25)), ActivityRange.H1))
    }
    @Test fun sevenAndThirtyDayRangesUseBoundedHourlyAnchors() {
        var state = ActivityHistoryState()
        for (hour in 0..900) state = ActivityHistoryModel.record(state, sample(NOW - (900L - hour) * 3600L, hour.toLong()))
        assertTrue(state.hourly.size <= 760)
        assertTrue(ActivityHistoryModel.points(state, ActivityRange.D7, NOW).isNotEmpty())
        assertTrue(ActivityHistoryModel.points(state, ActivityRange.D30, NOW).isNotEmpty())
    }
    private fun values(state: ActivityHistoryState, range: ActivityRange) = ActivityHistoryModel.points(state, range, NOW).map { it.documentsAdded }
    private fun record(vararg samples: ActivitySample) = samples.fold(ActivityHistoryState()) { state, sample -> ActivityHistoryModel.record(state, sample) }
    private fun sample(timestamp: Long, total: Long) = ActivitySample(timestamp, total)
    companion object { private const val NOW = 1_800_000_000L }
}
