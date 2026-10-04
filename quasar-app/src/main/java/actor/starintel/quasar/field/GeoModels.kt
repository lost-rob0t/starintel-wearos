package actor.starintel.quasar.field

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
    }
}

enum class GeoLayer(val label: String) {
    PLACES("Places"),
    EVENTS("Events"),
    SIGNALS("Signals"),
    PEOPLE("People"),
    TARGETS("Targets"),
    DOCUMENTS("Documents"),
}

data class GeoFeature(
    val id: String,
    val title: String,
    val dtype: String,
    val point: GeoPoint,
    val layer: GeoLayer,
    val links: List<String> = emptyList(),
    val summary: String = "",
    val source: String = "",
    val observedAt: String = "",
)

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = lat2 - lat1
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
            cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    fun initialBearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun coordinateReadout(point: GeoPoint): String = String.format(
        Locale.US,
        "%08.5f %s  ·  %09.5f %s",
        kotlin.math.abs(point.latitude),
        if (point.latitude >= 0) "N" else "S",
        kotlin.math.abs(point.longitude),
        if (point.longitude >= 0) "E" else "W",
    )

    fun distanceReadout(meters: Double): String = when {
        meters < 1_000.0 -> "${meters.roundToInt()} m"
        meters < 100_000.0 -> String.format(Locale.US, "%.1f km", meters / 1_000.0)
        else -> "${(meters / 1_000.0).roundToInt()} km"
    }

    fun bearingReadout(degrees: Double): String {
        val normalized = (degrees + 360.0) % 360.0
        val compass = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val direction = compass[((normalized + 22.5) / 45.0).toInt() % compass.size]
        return String.format(Locale.US, "%03d° %s", normalized.roundToInt() % 360, direction)
    }
}

object GeoFeatureFilter {
    fun apply(features: List<GeoFeature>, query: String, visibleLayers: Set<GeoLayer>): List<GeoFeature> =
        features.filter { it.layer in visibleLayers && matches(it, query) }

    fun matches(feature: GeoFeature, query: String): Boolean {
        val terms = query.trim().lowercase(Locale.US).split(Regex("\\s+")).filter(String::isNotBlank)
        if (terms.isEmpty()) return true
        val haystack = listOf(feature.id, feature.title, feature.dtype, feature.summary, feature.source)
            .joinToString(" ")
            .lowercase(Locale.US)
        return terms.all(haystack::contains)
    }
}
