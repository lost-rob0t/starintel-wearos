package actor.starintel.mobile

import java.net.URI

object CompanionProtocol {
    const val CAPABILITY = "starintel_config_receiver"
    const val CONFIG_PATH = "/starintel/config/v1"
    const val ACK_PATH = "/starintel/config/ack/v1"
    const val VERSION = 1
    const val ACK_TIMEOUT_MS = 15_000L
    const val MAX_PAYLOAD_BYTES = 8 * 1024
    const val MAX_DETAIL_CHARS = 120

    const val CODE_OK = "ok"
    const val CODE_INVALID_PAYLOAD = "invalid_payload"
    const val CODE_INVALID_URL = "invalid_url"
    const val CODE_INVALID_KEY = "invalid_key"
    const val CODE_AUTH_REJECTED = "auth_rejected"
    const val CODE_FORBIDDEN = "forbidden"
    const val CODE_UNREACHABLE = "unreachable"
    const val CODE_SAVE_FAILED = "save_failed"
    const val CODE_UNSUPPORTED = "unsupported"

    fun normalizeServerUrl(raw: String, allowCleartext: Boolean): String? {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank() || value.length > 2048) return null

        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            val validScheme = scheme == "https" || (allowCleartext && scheme == "http")
            val path = uri.path.orEmpty()
            val originOnly = path.isBlank() || path == "/"
            val safeAuthority = uri.userInfo == null && uri.host != null
            val noExtras = uri.query == null && uri.fragment == null
            val validPort = uri.port == -1 || uri.port in 1..65535
            if (!validScheme || !originOnly || !safeAuthority || !noExtras || !validPort) {
                null
            } else {
                URI(scheme, null, uri.host, uri.port, null, null, null).toString()
            }
        }.getOrNull()
    }

    fun validApiKey(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("star_sk_v1_") &&
            value.length in 20..4096 &&
            value.none { it.isWhitespace() || it.isISOControl() }
    }

    fun validRequestId(value: String?): Boolean {
        val requestId = value.orEmpty()
        return requestId.length in 16..64 && requestId.all { it.isLetterOrDigit() || it == '-' }
    }

    fun boundedDetail(value: String?): String = value
        .orEmpty()
        .filterNot { it.isISOControl() && it != '\n' }
        .replace('\n', ' ')
        .trim()
        .take(MAX_DETAIL_CHARS)

    fun userMessage(code: String?, detail: String?): String = when (code) {
        CODE_OK -> boundedDetail(detail).ifBlank { "Watch configured successfully" }
        CODE_AUTH_REJECTED -> "StarIntel rejected the API key"
        CODE_FORBIDDEN -> "API key does not have permission to read stats"
        CODE_UNREACHABLE -> "Watch could not reach the StarIntel server"
        CODE_SAVE_FAILED -> "Watch could not save the configuration securely"
        CODE_INVALID_URL -> "Watch rejected the server URL"
        CODE_INVALID_KEY -> "Watch rejected the API key format"
        CODE_UNSUPPORTED -> "Phone and watch app versions do not match"
        CODE_INVALID_PAYLOAD -> "Watch rejected the configuration payload"
        else -> "Watch configuration failed"
    }
}
