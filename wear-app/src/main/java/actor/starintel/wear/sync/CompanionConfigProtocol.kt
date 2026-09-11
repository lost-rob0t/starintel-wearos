package actor.starintel.wear.sync

import java.net.URI

object CompanionConfigProtocol {
    const val CAPABILITY = "starintel_config_receiver"
    const val CONFIG_PATH = "/starintel/config/v1"
    const val ACK_PATH = "/starintel/config/ack/v1"
    const val VERSION = 1
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

    fun errorCode(error: String?): String = when {
        error?.contains("401") == true -> CODE_AUTH_REJECTED
        error?.contains("403") == true -> CODE_FORBIDDEN
        else -> CODE_UNREACHABLE
    }

    fun safeDetail(code: String): String = when (code) {
        CODE_AUTH_REJECTED -> "Authentication rejected"
        CODE_FORBIDDEN -> "Credential is not authorized for stats"
        CODE_UNREACHABLE -> "Could not reach StarIntel"
        CODE_SAVE_FAILED -> "Could not save configuration securely"
        CODE_INVALID_URL -> "Invalid StarIntel server URL"
        CODE_INVALID_KEY -> "Invalid StarIntel API key"
        CODE_UNSUPPORTED -> "Phone and watch app versions do not match"
        CODE_INVALID_PAYLOAD -> "Invalid configuration payload"
        else -> "Configuration failed"
    }

    fun boundedDetail(value: String): String = value
        .filterNot { it.isISOControl() && it != '\n' }
        .replace('\n', ' ')
        .trim()
        .take(MAX_DETAIL_CHARS)
}
