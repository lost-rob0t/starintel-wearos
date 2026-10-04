package actor.starintel.quasar.field

import org.json.JSONArray
import org.json.JSONObject

object GeoDocumentParser {
    private const val MAX_ROWS = 2_000
    private const val MAX_LINKS = 256

    fun parseSearchRows(rows: JSONArray): List<GeoFeature> = buildList {
        for (index in 0 until rows.length().coerceAtMost(MAX_ROWS)) {
            val row = rows.optJSONObject(index) ?: continue
            val document = row.optJSONObject("doc")
                ?: row.optJSONObject("document")
                ?: row
            parse(document)?.let(::add)
        }
    }.distinctBy(GeoFeature::id)

    fun parse(document: JSONObject): GeoFeature? {
        val point = point(document) ?: return null
        val id = firstText(document, "_id", "id", "@id").ifBlank { return null }
        val dtype = typeOf(document)
        val title = firstText(document, "name", "title", "label").ifBlank { id }
        return GeoFeature(
            id = id,
            title = title,
            dtype = dtype,
            point = point,
            layer = layerFor(dtype),
            links = links(document).filterNot { it == id }.distinct().take(MAX_LINKS),
            summary = firstText(document, "summary", "description", "content").take(500),
            source = source(document),
            observedAt = firstText(document, "observed_at", "observedAt", "created_at", "timestamp"),
        )
    }

    private fun point(document: JSONObject): GeoPoint? {
        val geometry = document.optJSONObject("geometry")
            ?: document.optJSONObject("location")?.optJSONObject("geometry")
        if (geometry?.optString("type", "").equals("Point", ignoreCase = true)) {
            val coordinates = geometry?.optJSONArray("coordinates")
            coordinate(coordinates?.optDouble(1), coordinates?.optDouble(0))?.let { return it }
        }

        val geo = document.optJSONObject("geo") ?: document.optJSONObject("location")
        coordinate(
            firstNumber(geo, "latitude", "lat"),
            firstNumber(geo, "longitude", "lon", "lng"),
        )?.let { return it }

        return coordinate(
            firstNumber(document, "latitude", "lat"),
            firstNumber(document, "longitude", "lon", "lng"),
        )
    }

    private fun coordinate(latitude: Double?, longitude: Double?): GeoPoint? {
        if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) return null
        return runCatching { GeoPoint(latitude, longitude) }.getOrNull()
    }

    private fun typeOf(document: JSONObject): String {
        val explicit = firstText(document, "dtype", "type")
        if (explicit.isNotBlank()) return explicit
        val jsonLd = document.opt("@type")
        return when (jsonLd) {
            is String -> jsonLd
            is JSONArray -> jsonLd.optString(0, "document")
            else -> "document"
        }
    }

    private fun layerFor(dtype: String): GeoLayer {
        val normalized = dtype.lowercase()
        return when {
            listOf("wifi", "wireless", "signal", "radio", "ssid", "bssid", "cell").any(normalized::contains) -> GeoLayer.SIGNALS
            listOf("event", "incident", "observation", "activity").any(normalized::contains) -> GeoLayer.EVENTS
            listOf("person", "identity", "profile").any(normalized::contains) -> GeoLayer.PEOPLE
            listOf("target", "task", "mission").any(normalized::contains) -> GeoLayer.TARGETS
            listOf("place", "location", "site", "address", "venue").any(normalized::contains) -> GeoLayer.PLACES
            else -> GeoLayer.DOCUMENTS
        }
    }

    private fun links(document: JSONObject): List<String> = buildList {
        listOf("links", "relations", "relationships", "related").forEach { key ->
            when (val value = document.opt(key)) {
                is JSONArray -> for (index in 0 until value.length().coerceAtMost(MAX_LINKS)) {
                    linkId(value.opt(index))?.let(::add)
                }
                else -> linkId(value)?.let(::add)
            }
        }
    }

    private fun linkId(value: Any?): String? = when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is JSONObject -> firstText(value, "target", "target_id", "id", "_id", "@id").takeIf(String::isNotBlank)
        else -> null
    }

    private fun source(document: JSONObject): String {
        val source = document.opt("source")
        return when (source) {
            is String -> source
            is JSONObject -> firstText(source, "name", "url", "id", "@id")
            else -> ""
        }
    }

    private fun firstText(root: JSONObject, vararg names: String): String {
        names.forEach { name ->
            val value = root.opt(name)
            if (value is String && value.isNotBlank()) return value
        }
        return ""
    }

    private fun firstNumber(root: JSONObject?, vararg names: String): Double? {
        if (root == null) return null
        names.forEach { name ->
            when (val value = root.opt(name)) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }
}
