package actor.starintel.quasar

import android.content.Context
import android.util.AtomicFile
import java.security.MessageDigest

/** Private draft spillover avoids Binder's saved-state limit. Never used for credential fields. */
internal class WorkspaceDrafts(context: Context) {
    private val directory = context.filesDir.resolve("workspace-drafts-v1").apply { mkdirs() }
    fun save(name: String, source: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = AtomicFile(directory.resolve(key))
        val output = file.startWrite()
        try { output.write(source.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        return key
    }
    fun read(key: String): String {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return AtomicFile(directory.resolve(key)).openRead().bufferedReader().use { it.readText() }
    }
}
