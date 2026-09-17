package actor.starintel.android

import actor.starintel.android.actors.LocalActor
import actor.starintel.android.actors.LocalActorSystem
import actor.starintel.android.model.ActorCapability
import actor.starintel.android.model.ActorEffect
import actor.starintel.android.model.ActorEnvelope
import actor.starintel.android.model.ActorInstanceConfig
import actor.starintel.android.model.ActorManifest
import actor.starintel.android.model.ActorResult
import actor.starintel.android.store.Tek9Status
import actor.starintel.android.store.Tek9Store
import actor.starintel.android.store.Tek9Transaction
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalActorSystemTest {
    @Test
    fun declaredEffectCommitsWithAuditEvent() {
        val store = RecordingStore()
        val actor = actor(setOf(ActorCapability.WRITE_DOCUMENT)) {
            ActorResult(listOf(ActorEffect.SaveDocument(JSONObject().put("_id", "person:1"))), "saved")
        }
        val system = LocalActorSystem(store, workers = 1)
        system.register(actor, ActorInstanceConfig("normalizer-1", actor.manifest.id, true, JSONObject()))

        val run = system.dispatch("normalizer-1", envelope()).get(2, TimeUnit.SECONDS)

        assertEquals(1, run.acceptedEffects)
        assertEquals(listOf("document", "event"), store.operations)
        system.close()
    }

    @Test
    fun undeclaredEffectFailsClosedBeforeCommit() {
        val store = RecordingStore()
        val actor = actor(setOf(ActorCapability.READ_DOCUMENT)) {
            ActorResult(listOf(ActorEffect.SaveDocument(JSONObject().put("_id", "person:1"))), "unsafe")
        }
        val system = LocalActorSystem(store, workers = 1)
        system.register(actor, ActorInstanceConfig("reader-1", actor.manifest.id, true, JSONObject()))

        try {
            system.dispatch("reader-1", envelope()).get(2, TimeUnit.SECONDS)
            throw AssertionError("Expected actor effect rejection")
        } catch (failure: ExecutionException) {
            assertEquals("Actor emitted document.write without capability", failure.cause?.message)
        }

        assertEquals(emptyList<String>(), store.operations)
        system.close()
    }

    private fun actor(capabilities: Set<ActorCapability>, receive: () -> ActorResult) = object : LocalActor {
        override val manifest = ActorManifest(
            id = "local.test",
            name = "Test",
            version = "1",
            description = "",
            entrypoint = "LOCAL.TEST:HANDLE",
            accepts = setOf("person"),
            capabilities = capabilities,
            defaultConfig = JSONObject(),
        )

        override fun receive(envelope: ActorEnvelope, config: JSONObject): ActorResult = receive()
    }

    private fun envelope() = ActorEnvelope(
        messageId = "message-1",
        document = JSONObject().put("_id", "person:1").put("dtype", "person"),
        datasetId = "investigation",
        depth = 0,
        parentMessageId = null,
    )

    private class RecordingStore : Tek9Store {
        val operations = mutableListOf<String>()
        override val status = Tek9Status(true, "/test", "ready")

        override fun document(id: String): JSONObject? = null

        override fun search(query: String, limit: Int): List<JSONObject> = emptyList()

        override fun transact(block: Tek9Transaction.() -> Unit) {
            object : Tek9Transaction {
                override fun putDocument(document: JSONObject) { operations += "document" }
                override fun putRelation(fromId: String, predicate: String, toId: String, attributes: JSONObject) { operations += "relation" }
                override fun assertFact(fact: JSONObject) { operations += "fact" }
                override fun enqueueTarget(request: JSONObject) { operations += "target" }
                override fun appendEvent(event: JSONObject) { operations += "event" }
            }.block()
        }

        override fun close() = Unit
    }
}
