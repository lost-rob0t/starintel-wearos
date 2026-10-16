package actor.starintel.quasar

import android.content.Context
import android.util.AtomicFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Private draft spillover avoids Binder's saved-state limit. Never used for credential fields. */
internal class WorkspaceDrafts(context: Context) : DraftStorage {
    private val directory = context.applicationContext.filesDir.resolve("workspace-drafts-v1")
    // Compatibility entry points remain synchronous until serialized Activity integration.
    override fun save(name: String, source: String): String = synchronized(storageLock) {
        require(name.length in 1..512)
        DraftLimits.utf8Bytes(source)
        require(!actor.starintel.quasar.ide.SecretGuard.containsLikelySecret(source)) { "Draft contains credential-like material" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create draft directory" }
        val key = DraftReference.key(name)
        val file = AtomicFile(directory.resolve(key))
        val existing = directory.listFiles().orEmpty()
        require(existing.size < MAX_FILES || file.baseFile.exists()) { "Draft retention limit reached; discard unused drafts" }
        // Reserve both the prior acknowledged envelope and an interrupted replacement.
        require(existing.sumOf { it.length() } + MAX_ENVELOPE_BYTES <= MAX_STORAGE_BYTES) { "Draft storage limit reached" }
        val output = file.startWrite()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            output.write(cipher.iv.size); output.write(cipher.iv)
            output.write(cipher.doFinal(source.toByteArray(Charsets.UTF_8)))
            file.finishWrite(output)
        }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        key
    }
    override fun read(key: String): String = synchronized(storageLock) {
        require(key.matches(Regex("[0-9a-f]{64}")))
        AtomicFile(directory.resolve(key)).openRead().use { input ->
            val ivSize = input.read()
            require(ivSize == 12) { "Invalid draft envelope" }
            val iv = ByteArray(ivSize)
            java.io.DataInputStream(input).readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            val ciphertext = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var remaining = DraftLimits.MAX_BYTES + 16
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
                if (count < 0) break
                require(count <= remaining) { "Stored draft exceeds size limit" }
                ciphertext.write(buffer, 0, count)
                remaining -= count
            }
            String(cipher.doFinal(ciphertext.toByteArray()), Charsets.UTF_8).also { DraftLimits.utf8Bytes(it) }
        }
    }
    /** Only after the integration owner has collected ALL live Activity/studio saved-state keys.
     * Never age-evict a referenced unsaved draft. Must run on the coordinator worker.
     */
    fun pruneUnreferenced(retained: Set<String>) = synchronized(storageLock) {
        require(retained.all { it.matches(Regex("[0-9a-f]{64}")) })
        directory.listFiles().orEmpty().map { it.name.removeSuffix(".bak").removeSuffix(".new") }
            .filter { it.matches(Regex("[0-9a-f]{64}")) && it !in retained }.distinct()
            .forEach { AtomicFile(directory.resolve(it)).delete() }
    }

    companion object {
        private val storageLock = Any()
        @Volatile private var processCoordinator: DraftCoordinator? = null
        fun coordinator(context: Context): DraftCoordinator = processCoordinator ?: synchronized(this) {
            processCoordinator ?: DraftCoordinator(WorkspaceDrafts(context.applicationContext)).also { processCoordinator = it }
        }
        private const val MAX_FILES = 64
        private const val MAX_ENVELOPE_BYTES = DraftLimits.MAX_BYTES + 29L
        private const val MAX_STORAGE_BYTES = 8L * 1024 * 1024
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("quasar-ui-drafts-v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("quasar-ui-drafts-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
