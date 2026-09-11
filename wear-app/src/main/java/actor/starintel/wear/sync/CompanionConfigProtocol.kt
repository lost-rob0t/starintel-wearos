package actor.starintel.wear.sync

import java.net.URI

object CompanionConfigProtocol {
    const val CAPABILITY = "starintel_config_receiver"
    const val CONFIG_PATH = "/starintel/config/v1"
    const val ACK_PATH = "/starintel/config/ack/v1"
    const val VERSION = 1

    fun normalizeServerUrl(raw: String, allowCleartext: Boolean): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank() || value.length > 2048) return null
        return runCatching {
            val uri = URI(value)
            val validScheme = uri.scheme == "https" || (allowCleartext && uri.scheme == "http")
            if (uri.host == null || !validScheme) null else value
        }.getOrNull()
    }

    fun validApiKey(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("star_sk_v1_") && value.length in 20..4096
    }

    fun safeDetail(error: String?): String = when {
        error == null -> "Authentication failed"
        error.contains("401") -> "Authentication rejected"
        error.contains("403") -> "Credential is not authorized for stats"
        else -> "Could not reach StarIntel"
    }
}
