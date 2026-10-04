package actor.starintel.quasar.workflows

import actor.starintel.android.fbp.FlowGraph
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

internal class FlowDefinitionStore(
    private val directory: File,
) {
    fun list(): List<String> {
        ensureDirectory()
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(EXTENSION) }
            .map { it.name.removeSuffix(EXTENSION) }
            .sorted()
    }

    fun read(id: String): String {
        val file = graphFile(id)
        require(file.isFile) { "Workflow does not exist: $id" }
        require(!Files.isSymbolicLink(file.toPath())) { "Workflow symlinks are forbidden" }
        require(file.length() <= MAX_BYTES) { "Workflow exceeds 1 MiB" }
        return file.readText(StandardCharsets.UTF_8)
    }

    fun save(source: String): FlowGraph {
        val bytes = source.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Workflow exceeds 1 MiB" }
        val graph = FlowGraph.fromJson(JSONObject(source))
        ensureDirectory()
        val target = graphFile(graph.id)
        require(!target.exists() || !Files.isSymbolicLink(target.toPath())) { "Workflow symlinks are forbidden" }
        val temporary = File(directory, ".${graph.id}.${System.nanoTime()}.tmp")
        temporary.outputStream().use { output ->
            output.write(graph.toJson().toString(2).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return graph
    }

    private fun graphFile(id: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid workflow id" }
        ensureDirectory()
        return File(directory, "$id$EXTENSION")
    }

    private fun ensureDirectory() {
        require(directory.mkdirs() || directory.isDirectory) { "Could not open workflow directory" }
        require(!Files.isSymbolicLink(directory.toPath())) { "Workflow directory symlinks are forbidden" }
    }

    companion object {
        private const val EXTENSION = ".fbp.json"
        private const val MAX_BYTES = 1024 * 1024
        private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    }
}
