package actor.starintel.wear.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationGraphTest {
    @Test
    fun parsesQuasarStyleDirectedRelation() {
        val relation = parseRelation(
            JSONObject(
                """{
                  "_id":"rel-1",
                  "dtype":"relation",
                  "data":{
                    "subject":"person-1",
                    "object":"org-1",
                    "predicate":"member-of"
                  }
                }""",
            ),
        )!!

        assertEquals(listOf("person-1"), relation.subjects)
        assertEquals(listOf("org-1"), relation.objects)
        assertEquals("member-of", relation.predicate)
        assertTrue(relation.directed)
    }

    @Test
    fun acceptsLegacySourceTargetAndUndirectedFlag() {
        val relation = parseRelation(
            JSONObject(
                """{
                  "_id":"rel-2",
                  "dtype":"relation",
                  "source":"left",
                  "target":"right",
                  "predicate":"related",
                  "directed":false
                }""",
            ),
        )!!

        assertEquals(listOf("left"), relation.subjects)
        assertEquals(listOf("right"), relation.objects)
        assertFalse(relation.directed)
    }

    @Test
    fun acceptsObjectAndArrayEndpoints() {
        val relation = parseRelation(
            JSONObject(
                """{
                  "_id":"rel-3",
                  "dtype":"relation",
                  "data":{
                    "subject":[{"id":"a"},{"document_id":"b"}],
                    "object":{"entity_id":"c"},
                    "predicate":"knows"
                  }
                }""",
            ),
        )!!

        assertEquals(listOf("a", "b"), relation.subjects)
        assertEquals(listOf("c"), relation.objects)
    }

    @Test
    fun ignoresNonRelationsAndMissingEndpoints() {
        assertNull(parseRelation(JSONObject("{\"dtype\":\"person\"}")))
        assertNull(parseRelation(JSONObject("{\"dtype\":\"relation\",\"data\":{\"subject\":\"a\"}}")))
    }
}
