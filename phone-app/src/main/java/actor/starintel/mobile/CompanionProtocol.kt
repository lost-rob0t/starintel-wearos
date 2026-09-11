package actor.starintel.mobile

import java.net.URI

object CompanionProtocol {
    const val CAPABILITY = "starintel_config_receiver"
    const val CONFIG_PATH = "/starintel/config/v1"
    const val ACK_PATH = "/starintel/config/ack/v1"
    const val VERSION = 1

    fun normalizeServerUrl(raw: String): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank() || value.length > 2048) return null
        return runCatching {
            val uri = URI(value)
            if (uri.host == null || (uri.scheme != "https" && uri.scheme != "http")) null else value
        }.getOrNull()
    }

    fun validApiKey(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("star_sk_v1_") && value.length in 20..4096
    }
}
