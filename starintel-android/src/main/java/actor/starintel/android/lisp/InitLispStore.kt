package actor.starintel.android.lisp

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Owns the user-editable, app-private init.lisp without overwriting edits on upgrade. */
class InitLispStore(
    private val runtimeDirectory: File,
    private val seed: () -> InputStream,
) {
    val file = runtimeDirectory.resolve("init.lisp")
    val backupFile = runtimeDirectory.resolve("init.lisp.bak")

    @Synchronized
    fun ensureSeeded(): File {
        ensureDirectory()
        rejectSymlink(file)
        if (!file.exists()) {
            val bytes = seed().use { it.readBounded(MAX_INIT_BYTES) }
            require(bytes.size <= MAX_INIT_BYTES) { "Seed init.lisp exceeds 256 KiB" }
            writeReplacement(file, bytes)
        }
        require(file.length() <= MAX_INIT_BYTES) { "init.lisp exceeds 256 KiB" }
        return file
    }

    @Synchronized
    fun save(source: String): File {
        val bytes = source.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_INIT_BYTES) { "init.lisp exceeds 256 KiB" }
        ensureDirectory()
        rejectSymlink(file)
        rejectSymlink(backupFile)
        if (file.exists()) copyAsBackup(file, backupFile)
        writeReplacement(file, bytes)
        return file
    }

    private fun ensureDirectory() {
        check(runtimeDirectory.mkdirs() || runtimeDirectory.isDirectory) { "Could not create Lisp runtime directory" }
        require(!Files.isSymbolicLink(runtimeDirectory.toPath())) { "Lisp runtime directory must not be a symlink" }
    }

    private fun rejectSymlink(target: File) {
        require(!Files.isSymbolicLink(target.toPath())) { "Refusing symbolic link for ${target.name}" }
    }

    private fun copyAsBackup(source: File, destination: File) {
        val temporary = runtimeDirectory.resolve("${destination.name}.tmp")
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
        FileOutputStream(temporary, true).use { it.fd.sync() }
        moveReplacing(temporary, destination)
    }

    private fun writeReplacement(destination: File, bytes: ByteArray) {
        val temporary = runtimeDirectory.resolve("${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveReplacing(temporary, destination)
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Seed init.lisp exceeds 256 KiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        const val MAX_INIT_BYTES = 256 * 1024
    }
}
