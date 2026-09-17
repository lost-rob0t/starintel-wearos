package actor.starintel.quasar.field

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

data class WorldPoint(val x: Double, val y: Double)

object WebMercator {
    const val TILE_SIZE = 256
    private const val MAX_LATITUDE = 85.05112878

    fun project(point: GeoPoint, zoom: Int): WorldPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val latitude = point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val sinLatitude = kotlin.math.sin(Math.toRadians(latitude))
        return WorldPoint(
            x = (point.longitude + 180.0) / 360.0 * scale,
            y = (0.5 - ln((1 + sinLatitude) / (1 - sinLatitude)) / (4 * PI)) * scale,
        )
    }

    fun unproject(point: WorldPoint, zoom: Int): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val longitude = point.x / scale * 360.0 - 180.0
        val n = PI - 2.0 * PI * point.y / scale
        val latitude = Math.toDegrees(atan((exp(n) - exp(-n)) / 2.0))
        return GeoPoint(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE), wrapLongitude(longitude))
    }

    fun wrapWorldX(x: Double, zoom: Int): Double {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        return ((x % scale) + scale) % scale
    }

    private fun wrapLongitude(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}

object OpenMapTilePolicy {
    const val minZoom = 2
    const val maxZoom = 19
    const val maxCacheBytes = 48L * 1024L * 1024L
    const val maxTileBytes = 1L * 1024L * 1024L
    const val cacheMaxAgeMillis = 7L * 24L * 60L * 60L * 1_000L
    const val requestUserAgent = "Quasar-Android/0.4 (+https://starintel.actor; field-map)"
    const val attribution = "© OpenStreetMap contributors"
    private const val template = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    fun tileUrl(zoom: Int, x: Int, y: Int): String? {
        if (zoom !in minZoom..maxZoom) return null
        val count = 1 shl zoom
        if (y !in 0 until count) return null
        val wrappedX = ((x % count) + count) % count
        return template
            .replace("{z}", zoom.toString())
            .replace("{x}", wrappedX.toString())
            .replace("{y}", y.toString())
    }
}
