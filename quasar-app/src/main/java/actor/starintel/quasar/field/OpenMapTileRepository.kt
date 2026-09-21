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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class BasemapState(val label: String) {
    ONLINE("MAP LIVE"),
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
    private val onChanged: (BasemapState) -> Unit,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val sharedConfig = StarIntelSharedConfig(applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val closed = AtomicBoolean(false)
    private val inFlight = mutableSetOf<String>()
    private val failedUntil = mutableMapOf<String, Long>()
    private val memory = object : LruCache<String, Bitmap>(12 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
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

    fun tile(zoom: Int, x: Int, y: Int): Bitmap? {
        val template = sharedConfig.get(StarIntelSharedConfig.KEY_MAP_TILES_BASE_URL, "")
        val url = OpenMapTilePolicy.tileUrl(
            template = template,
            zoom = zoom,
            x = x,
            y = y,
            allowCleartext = BuildConfig.DEBUG,
        ) ?: return null
        memory.get(url)?.let { return it }
        synchronized(inFlight) {
            if ((failedUntil[url] ?: 0L) > System.currentTimeMillis()) return null
            if (closed.get() || !inFlight.add(url)) return null
        }
        executor.execute { load(url) }
        return null
    }

    private fun load(url: String) {
        var connection: HttpURLConnection? = null
        var state = BasemapState.UNAVAILABLE
        try {
            val online = hasNetwork()
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
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
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val declared = connection.contentLengthLong
                require(declared < 0 || declared <= OpenMapTilePolicy.maxTileBytes) { "Tile is too large" }
                val bytes = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= OpenMapTilePolicy.maxTileBytes) { "Tile is too large" }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { memory.put(url, it) }
                synchronized(inFlight) { failedUntil.remove(url) }
                state = if (online) BasemapState.ONLINE else BasemapState.CACHED_ONLY
            }
        } catch (_: Exception) {
            synchronized(inFlight) { failedUntil[url] = System.currentTimeMillis() + RETRY_DELAY_MILLIS }
            state = if (memory.size() > 0) {
                if (hasNetwork()) BasemapState.ONLINE else BasemapState.CACHED_ONLY
            } else {
                BasemapState.UNAVAILABLE
            }
        } finally {
            connection?.disconnect()
            synchronized(inFlight) { inFlight.remove(url) }
            if (!closed.get()) main.post { onChanged(state) }
        }
    }

    private fun hasNetwork(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
        memory.evictAll()
    }

    private companion object {
        const val RETRY_DELAY_MILLIS = 30_000L
    }
}
