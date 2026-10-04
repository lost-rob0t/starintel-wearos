package actor.starintel.android.fbp

import java.util.Collections
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowRuntimeTest {
    @Test
    fun runsProcessesWithNamedPortsIipsAndBoundedConnections() {
        val received = Collections.synchronizedList(mutableListOf<Int>())
        val registry = FbpComponentRegistry().apply {
            register(
                "test.expand/v1",
                ComponentDefinition(
                    inputs = setOf(PortDefinition("COUNT", required = true)),
                    outputs = setOf(PortDefinition("OUT")),
                ),
            ) {
                object : FlowComponent {
                    override fun run(context: ProcessContext) {
                        val count = context.receive("COUNT")!!.content.getInt("value")
                        repeat(count) { context.send("OUT", JSONObject().put("value", it)) }
                    }
                }
            }
            register(
                "test.collect/v1",
                ComponentDefinition(inputs = setOf(PortDefinition("IN", required = true))),
            ) {
                object : FlowComponent {
                    override fun run(context: ProcessContext) {
                        while (true) {
                            val packet = context.receive("IN") ?: return
                            received += packet.content.getInt("value")
                        }
                    }
                }
            }
        }
        val graph = FlowGraph(
            id = "bounded-iip",
            processes = listOf(
                ProcessDefinition("expand", "test.expand/v1"),
                ProcessDefinition("collect", "test.collect/v1"),
            ),
            connections = listOf(
                ConnectionDefinition(PortAddress("expand", "OUT"), PortAddress("collect", "IN"), 1),
            ),
            initialPackets = listOf(
                InitialPacketDefinition(PortAddress("expand", "COUNT"), JSONObject().put("value", 3)),
            ),
        )

        val run = FlowRuntime(graph, registry).start()
        val result = run.completion.get(2, TimeUnit.SECONDS)

        assertEquals(FlowRunStatus.COMPLETED, result.status)
        assertEquals(listOf(0, 1, 2), received)
        assertEquals(1, result.connections.single().capacity)
        assertTrue(result.connections.single().highWaterMark <= 1)
        assertEquals(setOf(ProcessRunStatus.COMPLETED), result.processes.map { it.status }.toSet())
    }

    @Test
    fun cancellationReleasesAProcessBlockedOnReceive() {
        val registry = FbpComponentRegistry().apply {
            register(
                "test.block/v1",
                ComponentDefinition(
                    inputs = setOf(PortDefinition("IN", required = false)),
                    outputs = setOf(PortDefinition("OUT")),
                ),
            ) {
                object : FlowComponent {
                    override fun run(context: ProcessContext) {
                        context.receive("IN")
                    }
                }
            }
        }
        val graph = FlowGraph(
            "cancel",
            listOf(ProcessDefinition("block", "test.block/v1")),
            connections = listOf(
                ConnectionDefinition(PortAddress("block", "OUT"), PortAddress("block", "IN"), 1),
            ),
        )
        val run = FlowRuntime(graph, registry).start()

        assertTrue(run.cancel())
        val result = run.completion.get(2, TimeUnit.SECONDS)

        assertEquals(FlowRunStatus.CANCELLED, result.status)
    }
}
