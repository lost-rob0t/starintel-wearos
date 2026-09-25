package actor.starintel.android

import actor.starintel.android.graph.GraphCommand
import actor.starintel.android.graph.GraphDocument
import actor.starintel.android.graph.GraphEdge
import actor.starintel.android.graph.GraphHistory
import actor.starintel.android.graph.GraphNode
import actor.starintel.android.graph.GraphPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphDocumentTest {
    private fun node(id: String, x: Float = 0f) =
        GraphNode(id, "starintel:person:$id", id, "person", GraphPoint(x, 0f))

    @Test
    fun commandsPreserveEndpointsAndRevision() {
        var graph = GraphDocument.empty()
        graph = graph.apply(GraphCommand.PutNode(node("a")))
        graph = graph.apply(GraphCommand.PutNode(node("b", 1f)))
        graph = graph.apply(GraphCommand.PutEdge(GraphEdge("edge-1", "a", "b", "knows")))
        assertEquals(3L, graph.revision)
        assertEquals(2, graph.nodes.size)
        assertEquals(1, graph.edges.size)
        graph = graph.apply(GraphCommand.DeleteNode("a"))
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun historyUndoRedoRestoresCanonicalSnapshots() {
        val history = GraphHistory(GraphDocument.empty(), capacity = 2)
        history.execute(GraphCommand.PutNode(node("a")))
        history.execute(GraphCommand.PutNode(node("b")))
        assertTrue(history.canUndo)
        assertEquals(setOf("a"), history.undo().nodes.keys)
        assertTrue(history.canRedo)
        assertEquals(setOf("a", "b"), history.redo().nodes.keys)
        assertFalse(history.canRedo)
    }

    @Test
    fun jsonRoundTripIsDeterministicAndRejectsBadEdges() {
        val graph = GraphDocument.empty()
            .apply(GraphCommand.PutNode(node("a")))
            .apply(GraphCommand.PutNode(node("b")))
            .apply(GraphCommand.PutEdge(GraphEdge("edge-1", "a", "b", "related-to")))
        val decoded = GraphDocument.fromJson(graph.toJson())
        assertEquals(graph, decoded)
        assertEquals(graph.toJson().toString(), decoded.toJson().toString())
        assertThrows(IllegalArgumentException::class.java) {
            GraphDocument("bad", "Bad", 0, mapOf("a" to node("a")), mapOf(
                "e" to GraphEdge("e", "a", "missing", "related-to"),
            ))
        }
    }

    @Test
    fun coordinatesMustBeFinite() {
        assertThrows(IllegalArgumentException::class.java) { GraphPoint(Float.NaN, 0f) }
    }
}
