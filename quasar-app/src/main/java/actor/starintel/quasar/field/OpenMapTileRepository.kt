package actor.starintel.quasar.field

import actor.starintel.android.config.StarIntelSharedConfig
import actor.starintel.quasar.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

enum class BasemapState(val label: String) {
    ONLINE("MAP AVAILABLE"),
    CACHED_ONLY("CACHED MAP"),
    UNAVAILABLE("NO BASEMAP"),
}

/**
 * Small, policy-conscious raster tile client. The platform HTTP cache honors
 * server cache headers and conditional requests; there is deliberately no
 * region download or prefetch API.
 */
internal class OpenMapTileRepository(
    context: Context,
    private val openConnection: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val networkAvailable: (() -> Boolean)? = null,
    private val tileTemplate: (() -> String)? = null,
    private val onChanged: (BasemapState) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val sharedConfig = StarIntelSharedConfig(applicationContext)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastState = BasemapState.UNAVAILABLE
    private val work = TileWorkQueue(onSettled = ::notifySettled, load = ::load)
    private val closed = AtomicBoolean(false)
    private val failures = TileFailureCache()
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount + 1023) / 1024
    }

    init {
        runCatching {
            if (HttpResponseCache.getInstalled() == null) {
                HttpResponseCache.install(
                    applicationContext.cacheDir.resolve("starintel-map-http-v1"),
                    OpenMapTilePolicy.maxCacheBytes,
                )
            }
        }
    }

    private fun template(): String = tileTemplate?.invoke()
        ?: sharedConfig.get(StarIntelSharedConfig.KEY_MAP_TILES_BASE_URL, "")

    private fun url(zoom: Int, x: Int, y: Int, template: String = template()): String? = OpenMapTilePolicy.tileUrl(
        template = template,
        zoom = zoom, x = x, y = y, allowCleartext = BuildConfig.DEBUG,
    )

    /** Call once before drawing each viewport. Only visible tiles may be requested. */
    fun setViewport(zoom: Int, xs: IntRange, ys: IntRange) {
        if (closed.get()) return
        val template = template()
        val visible = mutableSetOf<String>()
        for (y in ys) for (x in xs) url(zoom, x, y, template)?.let(visible::add)
        work.retain(visible)
    }

    fun tile(zoom: Int, x: Int, y: Int): Bitmap? {
        if (closed.get()) return null
        val url = url(zoom, x, y) ?: return null
        memory.get(url)?.let { return it }
        if (!failures.contains(url)) work.request(url)
        return null
    }

    private fun load(url: String, ticket: TileWorkQueue.Ticket) {
        var connection: HttpURLConnection? = null
        var state = BasemapState.UNAVAILABLE
        try {
            val online = hasNetwork()
            connection = openConnection(url).apply {
                connectTimeout = 7_000
                readTimeout = 8_000
                useCaches = true
                setRequestProperty("User-Agent", OpenMapTilePolicy.requestUserAgent)
                setRequestProperty("Accept", "image/png,image/*;q=0.8")
                if (!online) {
                    setRequestProperty(
                        "Cache-Control",
                        "only-if-cached,max-stale=${OpenMapTilePolicy.cacheMaxAgeMillis / 1_000}",
                    )
                }
            }
            val transport = connection
            ticket.attach { transport.disconnect() }
            if (ticket.cancelled) return
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val declared = connection.contentLengthLong
                require(declared < 0 || declared <= OpenMapTilePolicy.maxTileBytes) { "Tile is too large" }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        if (ticket.cancelled) return
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= OpenMapTilePolicy.maxTileBytes) { "Tile is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                if (ticket.cancelled) return
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(TileDecodeLimits.valid(bounds.outWidth, bounds.outHeight)) { "Invalid tile dimensions" }
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)) {
                    "Tile decoding failed"
                }
                var published = false
                work.publish(ticket) {
                    memory.put(url, bitmap)
                    failures.remove(url)
                    published = true
                }
                if (!published) bitmap.recycle()
                state = if (online) BasemapState.ONLINE else BasemapState.CACHED_ONLY
            } else {
                throw java.io.IOException("Tile response $code")
            }
        } catch (_: Exception) {
            work.publish(ticket) { failures.record(url) }
            state = if (memory.size() > 0) {
                BasemapState.CACHED_ONLY
            } else {
                BasemapState.UNAVAILABLE
            }
        } finally {
            ticket.detach()
            connection?.disconnect()
            work.publish(ticket) { lastState = state }
        }
    }

    private fun notifySettled() {
        // The queue has released its slot before this wakeup. Otherwise a fast
        // redraw could see the old in-flight URL and miss its chance to retry.
        if (!closed.get()) main.post { if (!closed.get()) onChanged(lastState) }
    }

    private fun hasNetwork(): Boolean {
        networkAvailable?.let { return it() }
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            work.close()
            main.removeCallbacksAndMessages(null)
            failures.clear()
            memory.evictAll()
        }
    }
}
