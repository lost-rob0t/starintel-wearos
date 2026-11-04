package actor.starintel.android.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActorCatalogTest {
    @Test
    fun `watchlist is a known monitor actor`() {
        val watchlist = ActorCatalog.byName("watchlist")
        assertNotNull(watchlist)
        assertTrue(watchlist!!.monitor)
        assertEquals("monitor", watchlist.kind)
        assertEquals(listOf("message", "social-media-post"), watchlist.accepts)
        assertTrue(ActorCatalog.isMonitor("watchlist"))
        assertTrue(ActorCatalog.isMonitor(" Watchlist "))
    }

    @Test
    fun `unknown actors are not monitors`() {
        assertNull(ActorCatalog.byName("user-hunt"))
        assertFalse(ActorCatalog.isMonitor("user-hunt"))
    }

    @Test
    fun `target hint reflects the accept contract`() {
        val hint = ActorCatalog.targetHint("user-hunt", listOf("user"))
        assertTrue(hint.startsWith("Accepts user."))
        assertTrue(ActorCatalog.targetHint("", emptyList()).contains("identifier, URL, or query"))
        val fromRegistry = ActorCatalog.targetHint("collect-x", listOf("url", "user"))
        assertEquals("Accepts url, user. Paste the url identifier, URL, or query it should work on.", fromRegistry)
        assertTrue(ActorCatalog.targetHint("watchlist", emptyList()).contains("dispatch is not supported"))
    }
}
