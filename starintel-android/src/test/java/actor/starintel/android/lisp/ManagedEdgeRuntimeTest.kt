package actor.starintel.android.lisp

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedEdgeRuntimeTest {
    @Test
    fun `catalog parser rejects anonymous rows and bounds untrusted text`() {
        val values = JSONArray()
            .put("not-an-object")
            .put(org.json.JSONObject().put("name", "anonymous"))
            .put(
                org.json.JSONObject()
                    .put("id", "runtime.echo")
                    .put("name", "Echo")
                    .put("description", "x".repeat(1_000)),
            )

        val actors = ManagedEdgeRuntime.parseActorCatalog(values)

        assertEquals(1, actors.size)
        assertEquals("runtime.echo", actors.single().id)
        assertEquals(800, actors.single().description.length)
    }
}
