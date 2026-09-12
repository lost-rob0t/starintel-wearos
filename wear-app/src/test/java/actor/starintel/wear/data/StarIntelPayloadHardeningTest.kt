package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarIntelPayloadHardeningTest {
    private fun payload(total: String = "1234", generated: String = "1788235200") =
        """{"status":"ok","data":{"service":"starintel-gserver","version":"0.4","generated_at":$generated,"documents":{"total":$total,"by_dtype":{"target":10,"investigation-target":5}},"targets":{"total":15}}}"""

    @Test fun parsesValidPayload() {
        val snapshot = parseStatsPayload(payload(), 1_788_235_260_000L, 1_788_235_260_000L)
        assertTrue(snapshot.reachable); assertEquals(1234L, snapshot.documentsTotal); assertEquals(5L, snapshot.investigationTargetCount)
    }
    @Test fun numericStringsAreRejectedInsteadOfCoerced() {
        assertTrue(runCatching { parseStatsPayload(payload(total = "\"1234\""), 1L, 1L) }.isFailure)
        assertTrue(runCatching { parseStatsPayload(payload(generated = "\"1788235200\""), 1L, 1L) }.isFailure)
    }
    @Test fun fractionalNumbersAreRejected() {
        assertTrue(runCatching { parseStatsPayload(payload(total = "1.5"), 1L, 1L) }.isFailure)
    }
    @Test fun missingRequiredFieldsAreRejected() {
        assertTrue(runCatching { parseStatsPayload("""{"status":"ok"}""", 1L, 1L) }.isFailure)
    }
    @Test fun statusNotOkIsRepresentedAsUnreachable() {
        val raw = payload().replace("\"status\":\"ok\"", "\"status\":\"error\"")
        assertFalse(parseStatsPayload(raw, 1L, 1L).reachable)
    }
    @Test fun compactCountRollsOverCleanly() {
        assertEquals("999.9K", 999_900L.compactCount()); assertEquals("1.0M", 1_000_000L.compactCount())
    }
    @Test fun configEpochRejectsOldGeneration() {
        val epoch = ConfigEpoch(); val old = epoch.current(); epoch.next(); assertFalse(epoch.matches(old))
    }
}
