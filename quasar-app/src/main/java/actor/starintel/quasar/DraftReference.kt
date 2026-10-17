package actor.starintel.quasar

import java.security.MessageDigest

/** Stable reference only, not proof that any generation has reached durable storage. */
internal object DraftReference {
    fun key(name: String): String {
        require(name.length in 1..512)
        return MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
