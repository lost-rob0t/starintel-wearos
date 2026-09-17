package actor.starintel.quasar.workflows

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FlowDefinitionStoreTest {
    @Test
    fun savesCanonicalClosedGraphAndListsIt() {
        val root = Files.createTempDirectory("quasar-flow-store").toFile()
        val store = FlowDefinitionStore(root)
        val graph = store.save(
            """
            {
              "schema":"starintel.fbp.graph/v1",
              "id":"geo-alerts",
              "processes":[],
              "connections":[],
              "iips":[]
            }
            """.trimIndent(),
        )

        assertEquals("geo-alerts", graph.id)
        assertEquals(listOf("geo-alerts"), store.list())
        assertEquals("geo-alerts", org.json.JSONObject(store.read("geo-alerts")).getString("id"))
    }

    @Test
    fun rejectsExecutableFields() {
        val store = FlowDefinitionStore(Files.createTempDirectory("quasar-flow-store").toFile())
        val error = assertThrows(IllegalArgumentException::class.java) {
            store.save(
                """
                {"schema":"starintel.fbp.graph/v1","id":"bad","processes":[],"connections":[],"iips":[],"eval":"(shell)"}
                """.trimIndent(),
            )
        }

        assertEquals("Unknown graph field: eval", error.message)
    }
}
