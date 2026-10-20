package actor.starintel.quasar

import org.json.JSONObject

/** A successful HTTP response alone does not establish actor execution or even acceptance. */
internal fun targetReceiptText(root: JSONObject): String {
    val receipt = root.optJSONObject("data") ?: root
    val id = receipt.opt("target_id") as? String ?: ""
    if (id.isBlank() || receipt.optString("status") !in setOf("accepted", "duplicate", "ok")) return "Server responded without a Target receipt. Acceptance and execution are unconfirmed.\n${root.toString(2)}"
    val state = if (receipt.optString("status") == "duplicate") "Server reports an existing accepted request." else "Accepted by server."
    return buildString {
        append(state).append(" Execution and result status are not tracked here.\nTarget · ").append(id)
        listOf("request_id" to "Request", "correlation_id" to "Correlation").forEach { (key, label) ->
            receipt.optString(key).takeIf { it.isNotBlank() }?.let { append("\n$label · $it") }
        }
    }
}
