package actor.starintel.quasar

import android.content.Context
import android.util.AtomicFile
import java.security.MessageDigest
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Private draft spillover avoids Binder's saved-state limit. Never used for credential fields. */
internal class WorkspaceDrafts(context: Context) {
    private val directory = context.filesDir.resolve("workspace-drafts-v1").apply { mkdirs() }
    fun save(name: String, source: String): String {
        val key = MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = AtomicFile(directory.resolve(key))
        val output = file.startWrite()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            output.write(cipher.iv.size); output.write(cipher.iv)
            output.write(cipher.doFinal(source.toByteArray(Charsets.UTF_8)))
            file.finishWrite(output)
        }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
        return key
    }
    fun read(key: String): String {
        require(key.matches(Regex("[0-9a-f]{64}")))
        return AtomicFile(directory.resolve(key)).openRead().use { input ->
            val ivSize = input.read()
            require(ivSize == 12) { "Invalid draft envelope" }
            val iv = ByteArray(ivSize)
            java.io.DataInputStream(input).readFully(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(input.readBytes()), Charsets.UTF_8)
        }
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
