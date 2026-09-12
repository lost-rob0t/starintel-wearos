package actor.starintel.wear.data

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SavedSearch(
    val id: String,
    val label: String,
    val query: String,
    val intervalMinutes: Int,
    val enabled: Boolean,
    val lastRunAt: Long = 0L,
    val seenIds: List<String> = emptyList(),
    val lastNewMatches: Int = 0,
)

data class SearchRunUpdate(
    val savedSearch: SavedSearch,
    val newIds: List<String>,
    val baseline: Boolean,
)

class SavedSearchStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()

    fun list(): List<SavedSearch> = synchronized(lock) {
        parseSavedSearches(prefs.getString(KEY_SEARCHES, null))
    }

    fun get(id: String): SavedSearch? = list().firstOrNull { it.id == id }

    fun upsert(
        id: String? = null,
        label: String,
        query: String,
        intervalMinutes: Int,
        enabled: Boolean,
    ): SavedSearch = synchronized(lock) {
        val cleanQuery = query.trim()
        require(cleanQuery.isNotBlank()) { "Query required" }
        require(cleanQuery.length <= MAX_QUERY_LENGTH) { "Query too long" }
        require(intervalMinutes in SUPPORTED_INTERVALS) { "Unsupported interval" }

        val existing = parseSavedSearches(prefs.getString(KEY_SEARCHES, null)).toMutableList()
        val targetId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val previous = existing.firstOrNull { it.id == targetId }
        val changedQuery = previous != null && previous.query != cleanQuery
        val updated = SavedSearch(
            id = targetId,
            label = label.trim().ifBlank { cleanQuery.take(48) }.take(MAX_LABEL_LENGTH),
            query = cleanQuery,
            intervalMinutes = intervalMinutes,
            enabled = enabled,
            lastRunAt = if (changedQuery) 0L else previous?.lastRunAt ?: 0L,
            seenIds = if (changedQuery) emptyList() else previous?.seenIds.orEmpty(),
            lastNewMatches = if (changedQuery) 0 else previous?.lastNewMatches ?: 0,
        )
        existing.removeAll { it.id == targetId }
        existing.add(updated)
        persist(existing)
        updated
    }

    fun setEnabled(id: String, enabled: Boolean): SavedSearch? = synchronized(lock) {
        val items = parseSavedSearches(prefs.getString(KEY_SEARCHES, null)).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val updated = items[index].copy(enabled = enabled)
        items[index] = updated
        persist(items)
        updated
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val items = parseSavedSearches(prefs.getString(KEY_SEARCHES, null)).toMutableList()
        val removed = items.removeAll { it.id == id }
        if (removed) persist(items)
        removed
    }

    fun due(nowMs: Long): List<SavedSearch> = list().filter { search ->
        search.enabled && (search.lastRunAt == 0L || nowMs - search.lastRunAt >= search.intervalMinutes * 60_000L)
    }

    fun recordRun(id: String, currentIds: List<String>, nowMs: Long): SearchRunUpdate? = synchronized(lock) {
        val items = parseSavedSearches(prefs.getString(KEY_SEARCHES, null)).toMutableList()
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null

        val previous = items[index]
        val baseline = previous.lastRunAt == 0L
        val previousSeen = previous.seenIds.toHashSet()
        val boundedCurrent = currentIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SEEN_IDS)
            .toList()
        val newIds = if (baseline) emptyList() else boundedCurrent.filterNot { it in previousSeen }

        val mergedSeen = LinkedHashSet<String>()
        boundedCurrent.forEach { mergedSeen.add(it) }
        previous.seenIds.forEach {
            if (mergedSeen.size < MAX_SEEN_IDS) mergedSeen.add(it)
        }

        val updated = previous.copy(
            lastRunAt = nowMs,
            seenIds = mergedSeen.take(MAX_SEEN_IDS),
            lastNewMatches = newIds.size,
        )
        items[index] = updated
        persist(items)
        SearchRunUpdate(updated, newIds, baseline)
    }

    fun activeCount(): Int = list().count { it.enabled }

    fun latestNewMatchCount(): Int = list().sumOf { it.lastNewMatches }.coerceAtMost(999)

    private fun persist(items: List<SavedSearch>) {
        val bounded = items.takeLast(MAX_SEARCHES)
        prefs.edit().putString(KEY_SEARCHES, encodeSavedSearches(bounded)).apply()
    }

    companion object {
        val SUPPORTED_INTERVALS = listOf(15, 30, 60, 360, 1_440)
        private const val PREFS = "starintel_wear_searches"
        private const val KEY_SEARCHES = "saved_searches"
        private const val MAX_SEARCHES = 32
        private const val MAX_SEEN_IDS = 500
        private const val MAX_QUERY_LENGTH = 512
        private const val MAX_LABEL_LENGTH = 64
    }
}

internal fun encodeSavedSearches(items: List<SavedSearch>): String {
    val array = JSONArray()
    items.forEach { item ->
        array.put(
            JSONObject()
                .put("id", item.id)
                .put("label", item.label)
                .put("query", item.query)
                .put("interval_minutes", item.intervalMinutes)
                .put("enabled", item.enabled)
                .put("last_run_at", item.lastRunAt)
                .put("last_new_matches", item.lastNewMatches)
                .put("seen_ids", JSONArray(item.seenIds)),
        )
    }
    return array.toString()
}

internal fun parseSavedSearches(raw: String?): List<SavedSearch> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val query = item.optString("query").trim()
                if (id.isBlank() || query.isBlank()) continue
                val interval = item.optInt("interval_minutes", 60)
                    .takeIf { it in SavedSearchStore.SUPPORTED_INTERVALS } ?: 60
                val seen = item.optJSONArray("seen_ids") ?: JSONArray()
                val seenIds = buildList {
                    for (seenIndex in 0 until seen.length()) {
                        val value = seen.optString(seenIndex).trim()
                        if (value.isNotBlank()) add(value)
                    }
                }.distinct().take(500)
                add(
                    SavedSearch(
                        id = id,
                        label = item.optString("label").ifBlank { query.take(48) }.take(64),
                        query = query.take(512),
                        intervalMinutes = interval,
                        enabled = item.optBoolean("enabled", true),
                        lastRunAt = item.optLong("last_run_at", 0L).coerceAtLeast(0L),
                        seenIds = seenIds,
                        lastNewMatches = item.optInt("last_new_matches", 0).coerceAtLeast(0),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())
}
