package actor.starintel.android.api

import org.json.JSONArray
import org.json.JSONObject

/** Couch FTS bookmark is opaque. A bookmark alone does not prove another nonempty page exists. */
data class SearchPage(
    val rows: JSONArray,
    val nextCursor: String?,
    val hasMore: Boolean?,
    val total: Long?,
) {
    companion object {
        const val MAX_CURSOR_CHARS = 8192

        fun validateCursor(cursor: String?) {
            require(cursor == null || (cursor.isNotBlank() && cursor.length <= MAX_CURSOR_CHARS &&
                cursor.none { it.isISOControl() })) { "Invalid search bookmark" }
        }

        fun fromJson(root: JSONObject, limit: Int, requestedCursor: String? = null): SearchPage {
            val data = root.optJSONObject("data") ?: root
            val rows = data.optJSONArray("rows") ?: data.optJSONArray("documents")
                ?: error("Search response has no rows")
            require(rows.length() <= limit) { "Search response exceeded page limit" }
            val rawCursor = data.opt("bookmark")
            require(rawCursor == null || rawCursor == JSONObject.NULL || rawCursor is String) { "Invalid search bookmark" }
            val cursor = (rawCursor as? String)?.takeIf { it.isNotEmpty() }
            validateCursor(cursor)
            // An empty terminal page may legitimately repeat the last CouchDB bookmark.
            require(rows.length() == 0 || cursor == null || cursor != requestedCursor) { "Search bookmark did not advance" }
            val rawTotal = data.opt("total_rows")
            val total = if (rawTotal == null || rawTotal == JSONObject.NULL) null else {
                require(rawTotal is Number && rawTotal.toDouble().isFinite() &&
                    rawTotal.toDouble() >= 0 && rawTotal.toDouble() < Long.MAX_VALUE.toDouble() &&
                    rawTotal.toDouble() == rawTotal.toLong().toDouble()) { "Invalid search total" }
                rawTotal.toLong()
            }
            val unique = JSONArray()
            val ids = HashSet<String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.optString("id").ifBlank { (row.optJSONObject("doc") ?: row).optString("_id") }
                if (id.isBlank() || ids.add(id)) unique.put(row)
            }
            return SearchPage(unique, if (rows.length() == 0) null else cursor,
                if (rows.length() == 0) false else null, total)
        }
    }
}
