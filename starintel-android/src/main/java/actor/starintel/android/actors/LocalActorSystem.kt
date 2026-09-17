package actor.starintel.android.actors

import actor.starintel.android.lisp.LispRuntime
import actor.starintel.android.model.ActorCapability
import actor.starintel.android.model.ActorEffect
import actor.starintel.android.model.ActorEnvelope
import actor.starintel.android.model.ActorInstanceConfig
import actor.starintel.android.model.ActorManifest
import actor.starintel.android.model.ActorResult
import actor.starintel.android.model.ActorRun
import actor.starintel.android.store.Tek9Store
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

interface LocalActor {
    val manifest: ActorManifest

    fun receive(envelope: ActorEnvelope, config: JSONObject): ActorResult
}

class LispLocalActor(
    override val manifest: ActorManifest,
    private val runtime: LispRuntime,
) : LocalActor {
    override fun receive(envelope: ActorEnvelope, config: JSONObject): ActorResult {
        val arguments = JSONObject()
            .put("actor_id", manifest.id)
            .put("entrypoint", manifest.entrypoint)
            .put("config", config)
            .put("message", envelope.toJson())
        val result = runtime.request("actor.dispatch", arguments)
        return ActorResult(
            effects = parseEffects(result.optJSONArray("effects") ?: JSONArray()),
            summary = result.optString("summary").take(2_000),
        )
    }

    private fun parseEffects(values: JSONArray): List<ActorEffect> = buildList {
        require(values.length() <= 256) { "Actor returned too many effects" }
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: error("Actor effect must be an object")
            add(
                when (value.optString("type")) {
                    "save_document" -> ActorEffect.SaveDocument(value.getJSONObject("document"))
                    "save_relation" -> ActorEffect.SaveRelation(
                        fromId = value.getString("from"),
                        predicate = value.getString("predicate"),
                        toId = value.getString("to"),
                        attributes = value.optJSONObject("attributes") ?: JSONObject(),
                    )
                    "assert_fact" -> ActorEffect.AssertFact(value.getJSONObject("fact"))
                    "dispatch_target" -> ActorEffect.DispatchTarget(value.getJSONObject("request"))
                    else -> error("Actor returned an unknown effect type")
                },
            )
        }
    }
}

class LocalActorSystem(
    private val store: Tek9Store,
    workers: Int = 2,
    private val mailboxCapacity: Int = 128,
) : Closeable {
    init {
        require(mailboxCapacity in 1..4_096) { "Mailbox capacity must be between 1 and 4096" }
    }

    private val executor: ExecutorService = Executors.newFixedThreadPool(workers.coerceIn(1, 8))
    private val cells = linkedMapOf<String, ActorCell>()
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun register(actor: LocalActor, config: ActorInstanceConfig) {
        check(!closed.get()) { "Actor system is closed" }
        require(actor.manifest.id == config.actorId) { "Actor configuration does not match manifest" }
        require(config.instanceId.matches(INSTANCE_ID)) { "Invalid actor instance id" }
        check(config.instanceId !in cells) { "Actor instance already registered" }
        cells[config.instanceId] = ActorCell(actor, config)
    }

    @Synchronized
    fun manifests(): List<ActorManifest> = cells.values.map { it.actor.manifest }

    fun dispatch(instanceId: String, envelope: ActorEnvelope): CompletableFuture<ActorRun> {
        check(!closed.get()) { "Actor system is closed" }
        val cell = synchronized(this) { cells[instanceId] } ?: error("Unknown actor instance: $instanceId")
        require(cell.config.enabled) { "Actor instance is disabled" }
        val dtype = envelope.document.optString("dtype")
        require(cell.actor.manifest.accepts.isEmpty() || dtype in cell.actor.manifest.accepts) {
            "Actor ${cell.actor.manifest.id} does not accept dtype $dtype"
        }
        val future = CompletableFuture<ActorRun>()
        cell.enqueue(Delivery(envelope, future))
        return future
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val actorCells = synchronized(this) {
            cells.values.toList().also { cells.clear() }
        }
        actorCells.forEach(ActorCell::close)
        executor.shutdownNow()
        store.close()
    }

    private inner class ActorCell(
        val actor: LocalActor,
        val config: ActorInstanceConfig,
    ) {
        private val mailbox = ArrayDeque<Delivery>()
        private var scheduled = false
        private var accepting = true

        fun enqueue(delivery: Delivery) {
            synchronized(this) {
                if (!accepting || closed.get()) {
                    delivery.future.completeExceptionally(IllegalStateException("Actor system is closed"))
                    return
                }
                if (mailbox.size >= mailboxCapacity) {
                    delivery.future.completeExceptionally(IllegalStateException("Actor mailbox is full"))
                    return
                }
                mailbox.addLast(delivery)
                if (scheduled) return
                scheduled = true
            }
            executor.execute(::drain)
        }

        fun close() {
            val pending = synchronized(this) {
                accepting = false
                scheduled = false
                buildList {
                    while (mailbox.isNotEmpty()) add(mailbox.removeFirst())
                }
            }
            pending.forEach {
                it.future.completeExceptionally(IllegalStateException("Actor system is closed"))
            }
        }

        private fun drain() {
            while (!closed.get()) {
                val delivery = synchronized(this) {
                    mailbox.pollFirst().also { if (it == null) scheduled = false }
                } ?: return
                runCatching { execute(delivery.envelope) }
                    .onSuccess(delivery.future::complete)
                    .onFailure(delivery.future::completeExceptionally)
            }
        }

        private fun execute(envelope: ActorEnvelope): ActorRun {
            val result = actor.receive(envelope, config.config)
            result.effects.forEach { effect ->
                require(effect.capability in actor.manifest.capabilities) {
                    "Actor emitted ${effect.capability.wireName} without capability"
                }
            }
            store.transact {
                result.effects.forEach { effect ->
                    when (effect) {
                        is ActorEffect.SaveDocument -> putDocument(effect.document)
                        is ActorEffect.SaveRelation -> putRelation(
                            effect.fromId,
                            effect.predicate,
                            effect.toId,
                            effect.attributes,
                        )
                        is ActorEffect.AssertFact -> assertFact(effect.fact)
                        is ActorEffect.DispatchTarget -> enqueueTarget(effect.request)
                    }
                }
                appendEvent(
                    JSONObject()
                        .put("type", "local_actor_run")
                        .put("actor_id", actor.manifest.id)
                        .put("actor_instance_id", config.instanceId)
                        .put("message_id", envelope.messageId)
                        .put("effect_count", result.effects.size),
                )
            }
            return ActorRun(actor.manifest.id, envelope.messageId, result.effects.size, result.summary)
        }
    }

    private data class Delivery(
        val envelope: ActorEnvelope,
        val future: CompletableFuture<ActorRun>,
    )

    companion object {
        private val INSTANCE_ID = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
    }
}

private fun ActorEnvelope.toJson(): JSONObject = JSONObject()
    .put("message_id", messageId)
    .put("document", document)
    .put("dataset_id", datasetId)
    .put("depth", depth)
    .put("parent_message_id", parentMessageId)
