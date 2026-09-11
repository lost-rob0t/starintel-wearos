package actor.starintel.wear.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionConfigProtocolTest {
    @Test
    fun acceptsHttpsAndNormalizesSlash() {
        assertEquals(
            "https://starintel.example",
            CompanionConfigProtocol.normalizeServerUrl("https://starintel.example/", allowCleartext = false),
        )
    }

    @Test
    fun rejectsCleartextOutsideDebug() {
        assertNull(CompanionConfigProtocol.normalizeServerUrl("http://10.0.0.2:8080", allowCleartext = false))
        assertEquals(
            "http://10.0.0.2:8080",
            CompanionConfigProtocol.normalizeServerUrl("http://10.0.0.2:8080", allowCleartext = true),
        )
    }

    @Test
    fun rejectsNonStarIntelCredentialShape() {
        assertTrue(CompanionConfigProtocol.validApiKey("star_sk_v1_credential_0123456789"))
        assertFalse(CompanionConfigProtocol.validApiKey("hunter2"))
    }

    @Test
    fun redactsServerErrorsFromAck() {
        assertEquals("Authentication rejected", CompanionConfigProtocol.safeDetail("HTTP 401 unauthorized"))
        assertEquals("Could not reach StarIntel", CompanionConfigProtocol.safeDetail("socket failed with secret text"))
    }
}
