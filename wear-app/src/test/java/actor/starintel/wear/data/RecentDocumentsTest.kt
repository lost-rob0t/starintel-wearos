package actor.starintel.wear.data
import org.junit.Assert.*
import org.junit.Test

class RecentDocumentsTest {
    private fun doc(id: String, time: Long) = """{"id":"$id","date_added":$time,"title":"Title $id","dtype":"document","dataset":"public"}"""
    private fun feed(vararg docs: String) = """{"version":1,"partial":true,"documents":[${docs.joinToString(",") }]}"""
    @Test fun newestFirst() { assertEquals(listOf("b", "a"), parseRecentDocuments(feed(doc("a", 1),doc("b",2))).documents.map { it.id }) }
    @Test fun deduplicatesIds() { assertEquals(1, parseRecentDocuments(feed(doc("a",1),doc("a",1))).documents.size) }
    @Test fun emptyArrayIsSuccessful() { assertTrue(parseRecentDocuments(feed()).documents.isEmpty()) }
    @Test fun propagatesBoundedWindow() { assertTrue(parseRecentDocuments(feed()).partial) }
    @Test fun blankIdsAreNotActionable() { assertTrue(parseRecentDocuments(feed(doc("",1))).documents.isEmpty()) }
    @Test fun missingTitleUsesId() { assertEquals("a",parseRecentDocuments(feed("""{"id":"a","date_added":1}""")).documents.single().title) }
    @Test(expected=IllegalArgumentException::class) fun rejectsNegativeTime() { parseRecentDocuments(feed(doc("a",-1))) }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingTime() { parseRecentDocuments(feed("""{"id":"a"}""")) }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnsupportedVersion() { parseRecentDocuments("""{"version":2,"documents":[]}""") }
    @Test(expected=IllegalArgumentException::class) fun rejectsOversizedFeed() { parseRecentDocuments(feed(*(0..32).map { doc("$it",it.toLong()) }.toTypedArray())) }
    @Test(expected=org.json.JSONException::class) fun rejectsMalformedJson() { parseRecentDocuments("{") }
    @Test fun staleSnapshotSurvivesFailureAndRecovers() {
        val first = RadarFeedState().update(parseRecentDocuments(feed(doc("a",1))))
        val stale = first.update(RecentDocuments(error="offline"))
        assertEquals(first.documents,stale.documents); assertEquals("offline",stale.error)
        val recovered = stale.update(parseRecentDocuments(feed(doc("b",2))))
        assertNull(recovered.error); assertEquals("b",recovered.documents.single().id)
    }
    @Test fun successfulEmptyResponseClearsOldSnapshot() {
        assertTrue(RadarFeedState().update(parseRecentDocuments(feed(doc("a",1)))).update(parseRecentDocuments(feed())).documents.isEmpty())
    }
    @Test fun irregularTimesKeepRealDistance() {
        assertEquals(0.5f, activityTimeFraction(8200,ActivityRange.H1,10000),0.0001f)
        assertEquals(0f,activityTimeFraction(1,ActivityRange.H1,10000),0f)
        assertEquals(1f,activityTimeFraction(20000,ActivityRange.H1,10000),0f)
    }
    @Test fun firstInWindowSampleUsesPredecessor() {
        var state = ActivityHistoryState()
        state = ActivityHistoryModel.record(state,ActivitySample(6390,100))
        state = ActivityHistoryModel.record(state,ActivitySample(6401,110))
        assertEquals(10L, ActivityHistoryModel.points(state,ActivityRange.H1,10000).single().documentsAdded)
    }
}
