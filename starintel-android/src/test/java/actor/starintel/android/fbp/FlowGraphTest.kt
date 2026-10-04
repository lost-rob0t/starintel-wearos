package actor.starintel.android.fbp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FlowGraphTest {
    @Test
    fun closedGraphJsonRoundTripsInDeterministicOrder() {
        val graph = FlowGraph.fromJson(
            JSONObject(
                """
                {
                  "schema":"starintel.fbp.graph/v1",
                  "id":"geo-alerts",
                  "processes":[
                    {"id":"sink","component":"test.sink/v1","config":{}},
                    {"id":"source","component":"test.source/v1","config":{"count":2}}
                  ],
                  "connections":[
                    {
                      "source":{"process":"source","port":"OUT"},
                      "target":{"process":"sink","port":"IN"},
                      "capacity":1
                    }
                  ],
                  "iips":[]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("sink", graph.processes.first().id)
        assertEquals(graph, FlowGraph.fromJson(graph.toJson()))
        assertEquals("sink", graph.toJson().getJSONArray("processes").getJSONObject(0).getString("id"))
    }

    @Test
    fun unknownGraphFieldsAreRejected() {
        val raw = JSONObject()
            .put("schema", FlowGraph.SCHEMA)
            .put("id", "bad")
            .put("processes", org.json.JSONArray())
            .put("connections", org.json.JSONArray())
            .put("iips", org.json.JSONArray())
            .put("executable_lisp", "(delete-everything)")

        val failure = assertThrows(IllegalArgumentException::class.java) { FlowGraph.fromJson(raw) }

        assertEquals("Unknown graph field: executable_lisp", failure.message)
    }

    @Test
    fun validationReportsPortAndTopologyErrorsDeterministically() {
        val registry = FbpComponentRegistry().apply {
            register("test.sink/v1", ComponentDefinition(inputs = setOf(PortDefinition("IN", required = true)))) {
                object : FlowComponent {
                    override fun run(context: ProcessContext) = Unit
                }
            }
        }
        val graph = FlowGraph(
            id = "invalid",
            processes = listOf(ProcessDefinition("sink", "test.sink/v1")),
            connections = listOf(
                ConnectionDefinition(
                    source = PortAddress("missing", "OUT"),
                    target = PortAddress("sink", "WRONG"),
                    capacity = 0,
                ),
            ),
        )

        assertEquals(
            listOf(
                "connection[missing.OUT->sink.WRONG]: capacity must be between 1 and 1024",
                "connection[missing.OUT->sink.WRONG]: source process does not exist",
                "connection[missing.OUT->sink.WRONG]: target port is not declared",
                "process[sink]: required input IN is not connected",
            ),
            graph.validate(registry),
        )
    }
}
