package actor.starintel.wear.complications

import actor.starintel.wear.data.StarIntelSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarIntelMetricCatalogTest {
    @Test
    fun zeroIsARealValueWhenARealSnapshotExists() {
        val snapshot = liveSnapshot(documentsTotal = 0L)
        val reading = StarIntelMetricCatalog.documentsTotal.read(snapshot, NOW)
        assertEquals(MetricReading.Value("0"), reading)
    }

    @Test
    fun missingSnapshotNeverMasqueradesAsZero() {
        val snapshot = StarIntelSnapshot(
            configured = true,
            reachable = false,
            receivedAt = 0L,
            stale = true,
        )
        val reading = StarIntelMetricCatalog.documentsTotal.read(snapshot, NOW)
        assertEquals(MetricReading.State("NO DATA"), reading)
    }

    @Test
    fun staleCachedCountIsMarkedWithoutChangingItsValue() {
        val snapshot = liveSnapshot(documentsTotal = 12_400L, stale = true)
        val reading = StarIntelMetricCatalog.documentsTotal.read(snapshot, NOW)
        assertTrue(reading is MetricReading.Value)
        assertEquals("~12.4K", reading.displayText())
    }

    @Test
    fun largeValuesUseBoundedCompactFormatting() {
        val snapshot = liveSnapshot(documentsTotal = 2_500_000_000L)
        val reading = StarIntelMetricCatalog.documentsTotal.read(snapshot, NOW)
        assertEquals("2.5B", reading.displayText())
    }

    @Test
    fun targetFamiliesComeOnlyFromServerBackedDtypeCounts() {
        val snapshot = liveSnapshot(
            documentsByType = mapOf(
                "target" to 7L,
                "investigation-target" to 11L,
            ),
            targetsTotal = 18L,
        )
        assertEquals("18", StarIntelMetricCatalog.targetsTotal.read(snapshot, NOW).displayText())
        assertEquals("7", StarIntelMetricCatalog.targetDocuments.read(snapshot, NOW).displayText())
        assertEquals("11", StarIntelMetricCatalog.investigationTargets.read(snapshot, NOW).displayText())
    }

    @Test
    fun freshnessUsesDeterministicClockAndNeverClaimsLiveWhenStale() {
        val fresh = liveSnapshot(receivedAt = NOW - 42_000L)
        assertEquals("42s", StarIntelMetricCatalog.syncFreshness.read(fresh, NOW).displayText())

        val stale = fresh.copy(stale = true)
        assertEquals("STALE", StarIntelMetricCatalog.syncFreshness.read(stale, NOW).displayText())
    }

    @Test
    fun chooserPreviewsContainNoFakeNumericStats() {
        val previews = StarIntelMetricCatalog.currentStatsMetrics.map { it.chooserPreview }
        assertFalse(previews.any { preview -> preview.any(Char::isDigit) })
    }

    private fun liveSnapshot(
        documentsTotal: Long = 100L,
        documentsByType: Map<String, Long> = emptyMap(),
        targetsTotal: Long = 0L,
        receivedAt: Long = NOW - 5_000L,
        stale: Boolean = false,
    ) = StarIntelSnapshot(
        configured = true,
        reachable = true,
        generatedAt = receivedAt / 1000L,
        receivedAt = receivedAt,
        documentsTotal = documentsTotal,
        documentsByType = documentsByType,
        targetsTotal = targetsTotal,
        stale = stale,
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
    }
}
