package actor.starintel.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test
    fun normalizesServerUrl() {
        assertEquals("https://starintel.example", CompanionProtocol.normalizeServerUrl(" https://starintel.example/ "))
    }

    @Test
    fun rejectsInvalidServerUrl() {
        assertNull(CompanionProtocol.normalizeServerUrl("starintel.example"))
    }

    @Test
    fun validatesApiKeyShape() {
        assertTrue(CompanionProtocol.validApiKey("star_sk_v1_credential_0123456789"))
        assertFalse(CompanionProtocol.validApiKey("password"))
    }
}
