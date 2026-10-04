package actor.starintel.android.model

import org.json.JSONArray
import org.json.JSONObject

/** Safe registry fields only. A ready actor is an observation, never a permission grant. */
data class RemoteActor(val uri: String, val name: String, val status: String, val ready: Boolean,
    val accepts: List<String>, val capabilities: List<String>) {
    companion object {
        @JvmStatic fun fromJson(value: JSONObject): RemoteActor? {
            if (!value.optBoolean("operatorVisible", false)) return null
            val uri = value.optString("resourceUri")
            val semantic = value.optJSONObject("semantic") ?: return null
            val name = semantic.optString("name")
            if (!uri.startsWith("star://") || uri.length > 512 || name.isBlank() || name.length > 512) return null
            fun strings(values: JSONArray?): List<String> = buildList {
                if (values != null) for (index in 0 until values.length().coerceAtMost(128)) {
                    val item = values.opt(index)
                    if (item is String && item.length in 1..512) add(item)
                }
            }
            return RemoteActor(uri, name, value.optString("status", "unavailable").take(64),
                value.optBoolean("ready", false), strings(value.optJSONObject("accepts")?.optJSONArray("targets")),
                strings(value.optJSONArray("capabilities")))
        }
    }
}
