package actor.starintel.android.fbp

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

data class PortDefinition(
    val name: String,
    val required: Boolean = false,
) {
    init {
        require(name.matches(PORT_NAME)) { "Invalid port name" }
    }
}

class ComponentDefinition(
    inputs: Set<PortDefinition> = emptySet(),
    outputs: Set<PortDefinition> = emptySet(),
    private val configValidator: (JSONObject) -> Unit = {},
) {
    val inputs: Set<PortDefinition> = inputs.sortedBy(PortDefinition::name).toCollection(linkedSetOf())
    val outputs: Set<PortDefinition> = outputs.sortedBy(PortDefinition::name).toCollection(linkedSetOf())

    init {
        require(this.inputs.map(PortDefinition::name).distinct().size == this.inputs.size) { "Duplicate input port" }
        require(this.outputs.map(PortDefinition::name).distinct().size == this.outputs.size) { "Duplicate output port" }
    }

    internal fun hasInput(name: String): Boolean = inputs.any { it.name == name }

    internal fun hasOutput(name: String): Boolean = outputs.any { it.name == name }

    internal fun validateConfig(config: JSONObject) = configValidator(config)
}

fun interface FlowComponentFactory {
    fun create(config: JSONObject): FlowComponent
}

fun interface FlowComponent {
    fun run(context: ProcessContext)
}

class FbpComponentRegistry {
    private val components = linkedMapOf<String, RegisteredComponent>()

    @Synchronized
    fun register(type: String, definition: ComponentDefinition, factory: FlowComponentFactory) {
        require(type.matches(COMPONENT_TYPE)) { "Invalid component type" }
        check(type !in components) { "Component is already registered: $type" }
        components[type] = RegisteredComponent(definition, factory)
    }

    @Synchronized
    internal fun definition(type: String): ComponentDefinition? = components[type]?.definition

    @Synchronized
    internal fun create(type: String, config: JSONObject): FlowComponent =
        components[type]?.factory?.create(config.deepSortedCopy()) ?: error("Unknown component: $type")

    private data class RegisteredComponent(
        val definition: ComponentDefinition,
        val factory: FlowComponentFactory,
    )
}

class InformationPacket internal constructor(
    val id: Long,
    content: JSONObject,
) {
    val content: JSONObject = content.deepSortedCopy()
}

interface ProcessContext {
    val graphId: String
    val processId: String

    /** Returns null only after the upstream port closes normally. */
    fun receive(port: String): InformationPacket?

    /** Ownership of a fresh immutable packet is transferred to the connected downstream process. */
    fun send(port: String, content: JSONObject)

    fun isCancelled(): Boolean

    fun checkpoint()
}

enum class FlowRunStatus { CREATED, VALIDATED, RUNNING, COMPLETED, CANCELLED, FAILED }

enum class ProcessRunStatus { WAITING, RUNNING, COMPLETED, CANCELLED, FAILED }

data class ProcessRunSnapshot(
    val processId: String,
    val component: String,
    val status: ProcessRunStatus,
)

data class ConnectionRunSnapshot(
    val source: PortAddress,
    val target: PortAddress,
    val capacity: Int,
    val highWaterMark: Int,
    val packetsSent: Long,
)

data class FlowRunSnapshot(
    val graphId: String,
    val status: FlowRunStatus,
    val processes: List<ProcessRunSnapshot>,
    val connections: List<ConnectionRunSnapshot>,
    val failure: String? = null,
)

class FlowRunHandle internal constructor(
    val completion: CompletableFuture<FlowRunSnapshot>,
    private val cancelAction: () -> Boolean,
    private val snapshotAction: () -> FlowRunSnapshot,
) {
    fun cancel(): Boolean = cancelAction()

    fun snapshot(): FlowRunSnapshot = snapshotAction()
}

/** Runs one long-lived process per graph process, matching classic FBP's blocking receive/send model. */
class FlowRuntime(
    private val graph: FlowGraph,
    private val registry: FbpComponentRegistry,
) : Closeable {
    private val state = AtomicReference(FlowRunStatus.CREATED)
    private val cancellation = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val completion = CompletableFuture<FlowRunSnapshot>()
    private val failure = AtomicReference<String?>(null)
    private val processStates = graph.processes.associate { it.id to AtomicReference(ProcessRunStatus.WAITING) }
    private val transports = graph.connections.map(::RuntimeConnection)
    private val iipTransports = graph.initialPackets.associate { iip ->
        iip.target to RuntimeConnection.forInitialPacket(iip.target, packet(iip.value))
    }
    private val inputByAddress = buildMap {
        transports.forEach { put(it.definition.target, it) }
        putAll(iipTransports)
    }
    private val outputByAddress = transports.associateBy { it.definition.source }
    private var executor: ExecutorService? = null

    fun start(): FlowRunHandle {
        check(started.compareAndSet(false, true)) { "FBP graph has already started" }
        val errors = graph.validate(registry)
        if (errors.isNotEmpty()) {
            state.set(FlowRunStatus.FAILED)
            failure.set(errors.joinToString("; "))
            completion.complete(snapshot())
            return handle()
        }
        state.set(FlowRunStatus.VALIDATED)
        if (graph.processes.isEmpty()) {
            state.set(FlowRunStatus.COMPLETED)
            completion.complete(snapshot())
            return handle()
        }

        state.set(FlowRunStatus.RUNNING)
        val pool = Executors.newFixedThreadPool(graph.processes.size, FlowThreadFactory(graph.id))
        executor = pool
        val jobs = graph.processes.map { process ->
            CompletableFuture.runAsync({ runProcess(process) }, pool)
        }
        CompletableFuture.allOf(*jobs.toTypedArray()).whenComplete { _, _ -> finish(pool) }
        return handle()
    }

    override fun close() {
        cancel()
    }

    private fun runProcess(process: ProcessDefinition) {
        val processState = processStates.getValue(process.id)
        if (cancellation.get()) {
            processState.set(ProcessRunStatus.CANCELLED)
            return
        }
        processState.set(ProcessRunStatus.RUNNING)
        val context = RuntimeProcessContext(process)
        try {
            registry.create(process.component, process.config).run(context)
            processState.set(if (cancellation.get()) ProcessRunStatus.CANCELLED else ProcessRunStatus.COMPLETED)
        } catch (_: FlowCancelledException) {
            processState.set(ProcessRunStatus.CANCELLED)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            if (cancellation.get()) {
                processState.set(ProcessRunStatus.CANCELLED)
            } else {
                processState.set(ProcessRunStatus.FAILED)
                fail("process[${process.id}]: interrupted")
            }
        } catch (cause: Throwable) {
            processState.set(ProcessRunStatus.FAILED)
            fail("process[${process.id}]: ${cause.message ?: cause.javaClass.simpleName}")
        } finally {
            graph.connections.filter { it.source.process == process.id }.forEach { connection ->
                outputByAddress[connection.source]?.closeNormally()
            }
        }
    }

    private fun fail(message: String) {
        if (state.compareAndSet(FlowRunStatus.RUNNING, FlowRunStatus.FAILED)) {
            failure.set(message)
            cancellation.set(true)
            allTransports().forEach(RuntimeConnection::cancel)
            executor?.shutdownNow()
        }
    }

    private fun cancel(): Boolean {
        val changed = state.compareAndSet(FlowRunStatus.RUNNING, FlowRunStatus.CANCELLED) ||
            state.compareAndSet(FlowRunStatus.VALIDATED, FlowRunStatus.CANCELLED)
        if (!changed) return false
        cancellation.set(true)
        allTransports().forEach(RuntimeConnection::cancel)
        executor?.shutdownNow()
        return true
    }

    private fun finish(pool: ExecutorService) {
        if (state.compareAndSet(FlowRunStatus.RUNNING, FlowRunStatus.COMPLETED)) Unit
        pool.shutdownNow()
        completion.complete(snapshot())
    }

    private fun handle() = FlowRunHandle(completion, ::cancel, ::snapshot)

    private fun snapshot(): FlowRunSnapshot = FlowRunSnapshot(
        graphId = graph.id,
        status = state.get(),
        processes = graph.processes.map { process ->
            ProcessRunSnapshot(process.id, process.component, processStates.getValue(process.id).get())
        },
        connections = transports.map(RuntimeConnection::snapshot),
        failure = failure.get(),
    )

    private fun packet(content: JSONObject): InformationPacket = InformationPacket(sequence.incrementAndGet(), content)

    private fun allTransports(): List<RuntimeConnection> = transports + iipTransports.values

    private inner class RuntimeProcessContext(
        private val process: ProcessDefinition,
    ) : ProcessContext {
        private val definition = registry.definition(process.component) ?: error("Unknown component")

        override val graphId: String = graph.id
        override val processId: String = process.id

        override fun receive(port: String): InformationPacket? {
            require(definition.hasInput(port)) { "Input port is not declared: $port" }
            checkpoint()
            return inputByAddress[PortAddress(process.id, port)]?.receive(cancellation)
        }

        override fun send(port: String, content: JSONObject) {
            require(definition.hasOutput(port)) { "Output port is not declared: $port" }
            require(content.toString().length <= MAX_PACKET_CHARS) { "Information packet exceeds 512 KiB" }
            checkpoint()
            outputByAddress[PortAddress(process.id, port)]?.send(packet(content), cancellation)
        }

        override fun isCancelled(): Boolean = cancellation.get()

        override fun checkpoint() {
            if (cancellation.get() || Thread.currentThread().isInterrupted) throw FlowCancelledException()
        }
    }
}

private class RuntimeConnection private constructor(
    val definition: ConnectionDefinition,
    initialPacket: InformationPacket?,
) {
    private val queue = ArrayBlockingQueue<TransportItem>(definition.capacity + 1)
    private val permits = Semaphore(definition.capacity)
    private val closed = AtomicBoolean(false)
    private val depth = AtomicInteger(0)
    private val highWaterMark = AtomicInteger(0)
    private val packetsSent = AtomicLong(0)

    constructor(definition: ConnectionDefinition) : this(definition, null)

    init {
        if (initialPacket != null) {
            permits.acquireUninterruptibly()
            queue.add(TransportItem.Packet(initialPacket))
            queue.add(TransportItem.Closed)
            closed.set(true)
            depth.set(1)
            highWaterMark.set(1)
            packetsSent.set(1)
        }
    }

    fun send(packet: InformationPacket, cancellation: AtomicBoolean) {
        while (true) {
            if (cancellation.get() || closed.get()) throw FlowCancelledException()
            if (!permits.tryAcquire(100, TimeUnit.MILLISECONDS)) continue
            if (cancellation.get() || closed.get()) {
                permits.release()
                throw FlowCancelledException()
            }
            queue.put(TransportItem.Packet(packet))
            val currentDepth = depth.incrementAndGet()
            highWaterMark.accumulateAndGet(currentDepth, ::maxOf)
            packetsSent.incrementAndGet()
            return
        }
    }

    fun receive(cancellation: AtomicBoolean): InformationPacket? {
        while (true) {
            if (cancellation.get()) throw FlowCancelledException()
            when (val item = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue) {
                is TransportItem.Packet -> {
                    depth.decrementAndGet()
                    permits.release()
                    return item.packet
                }
                TransportItem.Closed -> return null
                TransportItem.Cancelled -> throw FlowCancelledException()
            }
        }
    }

    fun closeNormally() {
        if (!closed.compareAndSet(false, true)) return
        queue.put(TransportItem.Closed)
    }

    fun cancel() {
        closed.set(true)
        queue.clear()
        depth.set(0)
        queue.offer(TransportItem.Cancelled)
    }

    fun snapshot(): ConnectionRunSnapshot = ConnectionRunSnapshot(
        source = definition.source,
        target = definition.target,
        capacity = definition.capacity,
        highWaterMark = highWaterMark.get(),
        packetsSent = packetsSent.get(),
    )

    companion object {
        fun forInitialPacket(target: PortAddress, packet: InformationPacket) = RuntimeConnection(
            ConnectionDefinition(PortAddress("iip", "OUT"), target, 1),
            packet,
        )
    }
}

private sealed interface TransportItem {
    data class Packet(val packet: InformationPacket) : TransportItem
    data object Closed : TransportItem
    data object Cancelled : TransportItem
}

private class FlowCancelledException : RuntimeException()

private class FlowThreadFactory(private val graphId: String) : java.util.concurrent.ThreadFactory {
    private val sequence = AtomicInteger(0)

    override fun newThread(task: Runnable): Thread = Thread(task, "star-fbp-$graphId-${sequence.incrementAndGet()}").apply {
        isDaemon = true
    }
}
