package actor.starintel.quasar

import actor.starintel.android.lisp.EclLispRuntime
import actor.starintel.android.lisp.InitLispStore
import actor.starintel.android.lisp.LispRuntimeStatus
import actor.starintel.android.fbp.FbpComponentRegistry
import actor.starintel.android.fbp.FlowGraph
import actor.starintel.android.fbp.FlowRunHandle
import actor.starintel.android.fbp.FlowRuntime
import actor.starintel.android.fbp.registerTek9ExpertComponents
import actor.starintel.android.fbp.registerWebSocketDocumentComponent
import actor.starintel.android.api.StarWebSocketDocumentStream
import actor.starintel.android.model.ActorManifest
import actor.starintel.android.store.LispTek9Store
import actor.starintel.android.store.Tek9Status
import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal class LocalRuntimeController(private val context: Context) : AutoCloseable {
    private val runtimeDirectory = File(context.filesDir, "quasar-lisp-v1")
    private val initStore = InitLispStore(runtimeDirectory) { context.assets.open("lisp/init.lisp") }
    private val lispRuntime by lazy {
        installLispSources()
        EclLispRuntime(runtimeDirectory.absolutePath)
    }
    private val tek9Store by lazy { LispTek9Store(lispRuntime, File(context.filesDir, "tek9").absolutePath) }
    private val config by lazy { QuasarConfig(context.applicationContext) }
    private val flowRegistry by lazy {
        FbpComponentRegistry().also { registry ->
            registerTek9ExpertComponents(registry, tek9Store)
            registerWebSocketDocumentComponent(registry) {
                val token = config.apiKey()
                require(config.serverUrl().isNotBlank() && !token.isNullOrBlank()) {
                    "Configure a Star server before running document workflows"
                }
                StarWebSocketDocumentStream(
                    serverUrl = config.serverUrl(),
                    apiKey = token,
                    clientVersion = BuildConfig.VERSION_NAME,
                    allowCleartext = BuildConfig.DEBUG,
                )
            }
        }
    }
    private val flowRuns = mutableSetOf<FlowRuntime>()

    fun lispStatus(): LispRuntimeStatus = lispRuntime.status

    fun initLispSource(): String = initStore.ensureSeeded().readText()

    /** Persists first, then asks the closed Lisp API to reload the fixed app-private file. */
    fun saveInitLisp(source: String): LispRuntimeStatus {
        initStore.save(source)
        if (!lispRuntime.status.available) return lispRuntime.status
        return runCatching {
            lispRuntime.request("runtime.reload-init")
            lispRuntime.status
        }.getOrElse { error ->
            LispRuntimeStatus(false, "ECL", error.message?.take(400) ?: "init.lisp reload failed")
        }
    }

    fun tek9Status(): Tek9Status = if (lispRuntime.status.available) {
        tek9Store.status
    } else {
        Tek9Status(false, File(context.filesDir, "tek9").absolutePath, "Waiting for the ECL bridge")
    }

    fun validateFlow(graph: FlowGraph): List<String> = graph.validate(flowRegistry)

    @Synchronized
    fun startFlow(graph: FlowGraph): FlowRunHandle {
        val errors = validateFlow(graph)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        val runtime = FlowRuntime(graph, flowRegistry)
        flowRuns += runtime
        return runtime.start().also { handle ->
            handle.completion.whenComplete { _, _ -> synchronized(this) { flowRuns.remove(runtime) } }
        }
    }

    override fun close() {
        synchronized(this) {
            flowRuns.toList().forEach { runCatching(it::close) }
            flowRuns.clear()
        }
        if (lispRuntime.status.available) runCatching { tek9Store.close() }
        else runCatching { lispRuntime.close() }
    }

    private fun installLispSources() {
        runtimeDirectory.mkdirs()
        initStore.ensureSeeded()
        listOf("starintel-mobile-runtime.lisp", "local-actors.lisp").forEach { name ->
            val target = File(runtimeDirectory, name)
            context.assets.open("lisp/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}

internal class ActorDefinitionStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun manifests(): List<ActorManifest> = buildList {
        ASSET_MANIFESTS.forEach { path ->
            context.assets.open(path).bufferedReader().use { add(ActorManifest.fromJson(JSONObject(it.readText()))) }
        }
        val custom = JSONArray(prefs.getString(KEY_CUSTOM, "[]").orEmpty().ifBlank { "[]" })
        for (index in 0 until custom.length().coerceAtMost(MAX_CUSTOM)) {
            custom.optJSONObject(index)?.let { add(ActorManifest.fromJson(it)) }
        }
    }.distinctBy(ActorManifest::id)

    fun saveCustom(raw: String): ActorManifest {
        require(raw.length <= MAX_MANIFEST_CHARS) { "Actor manifest exceeds 64 KiB" }
        val root = JSONObject(raw)
        val manifest = ActorManifest.fromJson(root)
        val custom = JSONArray(prefs.getString(KEY_CUSTOM, "[]").orEmpty().ifBlank { "[]" })
        val updated = JSONArray()
        var replaced = false
        for (index in 0 until custom.length().coerceAtMost(MAX_CUSTOM)) {
            val current = custom.optJSONObject(index) ?: continue
            if (current.optString("id") == manifest.id) {
                updated.put(root)
                replaced = true
            } else {
                updated.put(current)
            }
        }
        if (!replaced) {
            require(updated.length() < MAX_CUSTOM) { "Local actor limit reached" }
            updated.put(root)
        }
        check(prefs.edit().putString(KEY_CUSTOM, updated.toString()).commit()) {
            "Could not save local actor definition"
        }
        return manifest
    }

    companion object {
        private const val PREFS = "quasar_local_actor_definitions_v1"
        private const val KEY_CUSTOM = "custom_manifests"
        private const val MAX_CUSTOM = 64
        private const val MAX_MANIFEST_CHARS = 64 * 1024
        private val ASSET_MANIFESTS = listOf(
            "actors/person-normalizer.json",
            "actors/relation-indexer.json",
        )
    }
}
