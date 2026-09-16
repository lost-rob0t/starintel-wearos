package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryTest {
    @Test fun exposesRequestedWatchRangesInOrder() {
        assertEquals(listOf("1M", "5M", "15M", "1H", "6H", "1D", "1W"), ActivityRange.entries.map { it.label })
    }
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
    @Test fun oneWeekRangeUsesBoundedHourlyAnchors() {
        var state = ActivityHistoryState()
        for (hour in 0..900) state = ActivityHistoryModel.record(state, sample(NOW - (900L - hour) * 3600L, hour.toLong()))
        assertTrue(state.hourly.size <= 200)
        assertTrue(ActivityHistoryModel.points(state, ActivityRange.W1, NOW).isNotEmpty())
    }
    @Test fun documentTypesProduceIndependentRealSeries() {
        val state = record(
            sample(NOW - 300, 100, mapOf("target" to 30, "relation" to 10)),
            sample(NOW, 112, mapOf("target" to 37, "relation" to 15)),
        )
        val point = ActivityHistoryModel.points(state, ActivityRange.H1, NOW).single()
        assertEquals(12L, point.documentsAdded)
        assertEquals(mapOf("target" to 7L, "relation" to 5L), point.documentsByTypeAdded)
        assertEquals(listOf("total", "target", "relation"), ActivityHistoryModel.series(listOf(point), true).map { it.key })
    }
    @Test fun autoRangeRotatesOnlyAcrossRangesWithEnoughSamples() {
        val state = record(
            sample(NOW - 300, 100), sample(NOW - 240, 101), sample(NOW - 180, 102), sample(NOW - 120, 103), sample(NOW - 60, 104), sample(NOW, 105),
        )
        assertTrue(ActivityHistoryModel.autoRange(state, NOW) in listOf(ActivityRange.M5, ActivityRange.M15, ActivityRange.H1, ActivityRange.H6, ActivityRange.D1))
    }
    private fun values(state: ActivityHistoryState, range: ActivityRange) = ActivityHistoryModel.points(state, range, NOW).map { it.documentsAdded }
    private fun record(vararg samples: ActivitySample) = samples.fold(ActivityHistoryState()) { state, sample -> ActivityHistoryModel.record(state, sample) }
    private fun sample(timestamp: Long, total: Long, types: Map<String, Long> = emptyMap()) = ActivitySample(timestamp, total, types)
    companion object { private const val NOW = 1_800_000_000L }
}
