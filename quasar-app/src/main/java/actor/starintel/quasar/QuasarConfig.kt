package actor.starintel.quasar

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class QuasarConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun serverUrl(): String = prefs.getString(KEY_SERVER, "").orEmpty().trimEnd('/')

    fun save(serverUrl: String, apiKey: String) {
        val normalized = serverUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://") || (BuildConfig.DEBUG && normalized.startsWith("http://"))) {
            if (BuildConfig.DEBUG) "Use an HTTP or HTTPS server URL" else "Release builds require HTTPS"
        }
        require(apiKey.startsWith("star_sk_v1_") && apiKey.length <= 4096) { "Invalid StarIntel API key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        check(
            prefs.edit()
                .putString(KEY_SERVER, normalized)
                .putString(KEY_SECRET, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist Quasar configuration" }
    }

    fun apiKey(): String? {
        val encrypted = prefs.getString(KEY_SECRET, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isConfigured(): Boolean = serverUrl().isNotBlank() && !apiKey().isNullOrBlank()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "quasar_config_v1"
        private const val KEY_SERVER = "server_url"
        private const val KEY_SECRET = "api_key_ciphertext"
        private const val KEY_IV = "api_key_iv"
        private const val KEY_ALIAS = "quasar-starintel-api-key-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
