package actor.starintel.quasar.field

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real Android decoding/bitmap cache, deterministic fake HTTP responses. */
class OpenMapTileRepositoryTest {
    private class Response(val bytes: ByteArray, val status: Int = 200) : HttpURLConnection(URL("https://tiles.test/")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = -1L
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }

    private fun png(width: Int = 256, height: Int = 256): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    private fun responseTest(bytes: ByteArray, expected: BasemapState, online: Boolean = true, status: Int = 200) {
        val complete = CountDownLatch(1)
        val requests = AtomicInteger()
        val response = Response(bytes, status)
        var observed: BasemapState? = null
        val repo = OpenMapTileRepository(
            InstrumentationRegistry.getInstrumentation().targetContext,
            openConnection = { requests.incrementAndGet(); response },
            networkAvailable = { online },
            tileTemplate = { "https://tiles.test/{z}/{x}/{y}.png" },
        ) { observed = it; complete.countDown() }
        try {
            repo.setViewport(3, 1..1, 1..1)
            assertNull(repo.tile(3, 1, 1))
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(expected, observed)
            if (expected != BasemapState.UNAVAILABLE) {
                assertNotNull(repo.tile(3, 1, 1))
                if (!online) assertTrue(response.getRequestProperty("Cache-Control").startsWith("only-if-cached"))
            } else {
                assertNull(repo.tile(3, 1, 1))
            }
            assertEquals(1, requests.get()) // memory hit or failure cooldown, no duplicate request
        } finally { repo.close() }
        assertNull(repo.tile(3, 1, 1))
    }

    @Test fun cachedResponseRemainsAvailableOffline() = responseTest(png(), BasemapState.CACHED_ONLY, online = false)
    @Test fun validImageDecodesAndUsesMemoryCache() = responseTest(png(), BasemapState.ONLINE)
    @Test fun malformedImageFails() = responseTest(byteArrayOf(1, 2, 3), BasemapState.UNAVAILABLE)
    @Test fun oversizedDimensionsFailBeforePixelDecode() = responseTest(png(1025, 1), BasemapState.UNAVAILABLE)
    @Test fun truncatedImageFails() = responseTest(png().copyOf(40), BasemapState.UNAVAILABLE)
    @Test fun streamedCompressedBytesAreBounded() = responseTest(ByteArray(1024 * 1024 + 1), BasemapState.UNAVAILABLE)
    @Test fun httpFailureIsThrottled() = responseTest(byteArrayOf(), BasemapState.UNAVAILABLE, status = 503)
}
