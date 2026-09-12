package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSearchStoreTest {
    @Test
    fun savedSearchRoundTripPreservesMonitorState() {
        val input = listOf(
            SavedSearch(
                id = "one",
                label = "New GitHub users",
                query = "dtype:user github",
                intervalMinutes = 30,
                enabled = true,
                lastRunAt = 1_234L,
                seenIds = listOf("doc-a", "doc-b"),
                lastNewMatches = 2,
            ),
        )

        val parsed = parseSavedSearches(encodeSavedSearches(input)).single()

        assertEquals("one", parsed.id)
        assertEquals("New GitHub users", parsed.label)
        assertEquals("dtype:user github", parsed.query)
        assertEquals(30, parsed.intervalMinutes)
        assertTrue(parsed.enabled)
        assertEquals(1_234L, parsed.lastRunAt)
        assertEquals(listOf("doc-a", "doc-b"), parsed.seenIds)
        assertEquals(2, parsed.lastNewMatches)
    }

    @Test
    fun malformedStorageFailsClosedWithoutCrashing() {
        assertTrue(parseSavedSearches("not-json").isEmpty())
        assertTrue(parseSavedSearches(null).isEmpty())
    }

    @Test
    fun unsupportedIntervalFallsBackAndSeenIdsAreDeduplicated() {
        val parsed = parseSavedSearches(
            """
            [{
              "id":"watch",
              "label":"watch",
              "query":"alice",
              "interval_minutes":1,
              "enabled":false,
              "seen_ids":["a","a","b"]
            }]
            """.trimIndent(),
        ).single()

        assertEquals(60, parsed.intervalMinutes)
        assertFalse(parsed.enabled)
        assertEquals(listOf("a", "b"), parsed.seenIds)
    }
}
