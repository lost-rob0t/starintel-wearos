package actor.starintel.quasar.workflows

import java.net.URI

object WorkflowDeepLink {
    private const val SCHEME = "quasar"
    private const val HOST = "workflow"

    fun forWorkflow(id: WorkflowId): String = URI(SCHEME, HOST, "/${id.value}", null).toASCIIString()

    fun parse(raw: String?): WorkflowId? = runCatching {
        if (raw.isNullOrBlank()) return@runCatching null
        val uri = URI(raw)
        if (uri.scheme != SCHEME || uri.host != HOST || uri.rawQuery != null || uri.rawFragment != null) {
            return@runCatching null
        }
        val path = uri.rawPath ?: return@runCatching null
        if (!path.startsWith('/') || path.indexOf('/', startIndex = 1) >= 0 || path.contains('%')) {
            return@runCatching null
        }
        WorkflowId.parse(path.drop(1))
    }.getOrNull()
}
