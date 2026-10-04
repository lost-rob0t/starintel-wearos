package actor.starintel.wear.data

import org.json.JSONObject

data class RecentDocument(val id: String, val title: String, val dtype: String, val dataset: String, val added: Long)
data class RecentDocuments(val documents: List<RecentDocument> = emptyList(), val partial: Boolean = false, val error: String? = null)

internal fun parseRecentDocuments(payload: String): RecentDocuments {
    val root = JSONObject(payload)
    require(root.optInt("version") == 1) { "Unsupported radar feed" }
    val rows = root.getJSONArray("documents")
    require(rows.length() <= 32) { "Radar response exceeds limit" }
    val documents = (0 until rows.length()).mapNotNull { index ->
        val item = rows.getJSONObject(index)
        val id = (item.opt("id") as? String)?.trim().orEmpty()
        val added = item.opt("date_added")
        require(added is Number && added.toLong() >= 0 && added.toDouble() == added.toLong().toDouble()) { "Invalid arrival time" }
        require(id.length <= 512) { "Document ID too long" }
        if (id.isBlank()) null else RecentDocument(id, item.optString("title").ifBlank { id }.take(180),
            item.optString("dtype").take(64), item.optString("dataset").take(128), added.toLong())
    }.distinctBy { it.id }.sortedWith(compareByDescending<RecentDocument> { it.added }.thenBy { it.id })
    return RecentDocuments(documents, root.optBoolean("partial"))
}

internal data class RadarFeedState(val documents: List<RecentDocument> = emptyList(), val error: String? = null) {
    fun update(result: RecentDocuments): RadarFeedState =
        if (result.error == null) RadarFeedState(result.documents) else copy(error = result.error)
}
