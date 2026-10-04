package actor.starintel.quasar.ide

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class WorkspaceException(message: String) : IllegalArgumentException(message)

class PrivateWorkspaceStore(
    private val root: File,
    private val maxSourceBytes: Int = 256 * 1024,
    private val maxWorkspaceBytes: Long = 2L * 1024 * 1024,
    private val maxFiles: Int = 64,
    private val validationHooks: List<ValidationHook> = emptyList(),
) {
    init {
        require(maxSourceBytes > 0 && maxWorkspaceBytes >= maxSourceBytes && maxFiles > 0)
        root.mkdirs()
    }

    fun seedDefaults() {
        seed(
            SourceBuffer(
                "init.lisp",
                IdeLanguage.LISP,
                "; Quasar private runtime configuration. This file is writable.\n" +
                    "(in-package #:cl-user)\n\n" +
                    "(defparameter *quasar-local-mode* t)\n",
            ),
        )
        seed(
            SourceBuffer(
                "knowledge.pl",
                IdeLanguage.PROLOG,
                "% Quasar private facts and reviewed rules.\n" +
                    ":- discontiguous document/2.\n",
            ),
        )
    }

    fun list(language: IdeLanguage): List<SourceBuffer> {
        val directory = languageDirectory(language)
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension == language.extension && !it.name.endsWith(".tmp") }
            .sortedBy { it.name.lowercase() }
            .map { SourceBuffer(it.name, language, readBounded(it)) }
            .toList()
    }

    fun load(language: IdeLanguage, name: String): SourceBuffer {
        val file = resolve(language, name)
        if (!file.isFile) throw WorkspaceException("Source does not exist")
        return SourceBuffer(name, language, readBounded(file))
    }

    @Synchronized
    fun save(buffer: SourceBuffer): List<Diagnostic> {
        val target = resolve(buffer.language, buffer.name)
        val bytes = buffer.source.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxSourceBytes) throw WorkspaceException("Source exceeds the private workspace size limit")
        if (SecretGuard.containsLikelySecret(buffer.source)) throw WorkspaceException("Source contains credential-like material and was not saved")

        val diagnostics = LanguageRegistry.implementation(buffer.language).validate(buffer.source) +
            validationHooks.flatMap { it.validate(buffer) }
        if (diagnostics.any { it.severity == DiagnosticSeverity.ERROR }) {
            throw WorkspaceException("Source has ${diagnostics.count { it.severity == DiagnosticSeverity.ERROR }} blocking diagnostic(s)")
        }

        val files = allSourceFiles()
        if (!target.exists() && files.size >= maxFiles) throw WorkspaceException("Private workspace file limit reached")
        val existing = target.takeIf { it.isFile }?.length() ?: 0L
        val projected = files.sumOf { it.length() } - existing + bytes.size
        if (projected > maxWorkspaceBytes) throw WorkspaceException("Private workspace size limit reached")

        atomicWrite(target, bytes)
        return diagnostics
    }

    private fun seed(buffer: SourceBuffer) {
        val file = resolve(buffer.language, buffer.name)
        if (!file.exists()) save(buffer)
    }

    private fun languageDirectory(language: IdeLanguage): File = File(root, language.directory).also { directory ->
        if (!directory.exists() && !directory.mkdirs()) throw WorkspaceException("Could not create private workspace")
    }

    private fun resolve(language: IdeLanguage, name: String): File {
        if (!SAFE_NAME.matches(name) || !name.endsWith(".${language.extension}")) {
            throw WorkspaceException("Invalid source name")
        }
        return File(languageDirectory(language), name)
    }

    private fun allSourceFiles(): List<File> = IdeLanguage.entries.flatMap { language ->
        languageDirectory(language).listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }.orEmpty()
    }

    private fun readBounded(file: File): String {
        if (file.length() > maxSourceBytes) throw WorkspaceException("Stored source exceeds the private workspace size limit")
        return file.readText(Charsets.UTF_8)
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        internal fun atomicWrite(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                if (temporary.exists()) temporary.delete()
            }
        }
    }
}

object SecretGuard {
    private val patterns = listOf(
        Regex("star_sk_v1_[A-Za-z0-9_-]{8,}"),
        Regex("(?i)authorization\\s*[:=]\\s*bearer\\s+\\S+"),
        Regex("(?i)(api[_-]?key|access[_-]?token|client[_-]?secret|password)\\s*[:=]\\s*[\"']?[^\\s\"']{8,}"),
        Regex("-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    )

    fun containsLikelySecret(source: String): Boolean = patterns.any { it.containsMatchIn(source) }
}
