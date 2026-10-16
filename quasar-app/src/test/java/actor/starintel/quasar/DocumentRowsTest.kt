package actor.starintel.quasar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DocumentRowsTest {
    @Test fun projectionBoundsRowsAndTextAndUsesNestedSummary() {
        val rows = JSONArray()
        repeat(10_000) { index -> rows.put(JSONObject().put("id", "$index").put("doc", JSONObject()
            .put("dtype", "person").put("data", JSONObject().put("name", "N".repeat(1000)).put("summary", "S".repeat(1000))))) }
        val projected = DocumentRow.from(rows)
        assertEquals(100, projected.size)
        assertEquals("99", projected.last().id)
        assertEquals(240, projected.first().title.length)
        assertEquals(400, projected.first().summary.length)
        assertEquals(40, DocumentRow.from(rows, 40).size)
    }
    @Test fun repeatedIdentifiersDoNotCreateConflictingRows() {
        val row = JSONObject().put("id", "same").put("name", "Evidence")
        val result = DocumentRow.from(JSONArray().put(row).put(row))
        assertEquals(1, result.size)
        assertEquals("Evidence", result.single().title)
    }
}
