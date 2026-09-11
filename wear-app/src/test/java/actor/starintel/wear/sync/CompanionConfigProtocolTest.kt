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
    fun rejectsPathQueryAndUserInfo() {
        assertNull(CompanionConfigProtocol.normalizeServerUrl("https://starintel.example/api", allowCleartext = false))
        assertNull(CompanionConfigProtocol.normalizeServerUrl("https://starintel.example?q=1", allowCleartext = false))
        assertNull(CompanionConfigProtocol.normalizeServerUrl("https://user:pass@starintel.example", allowCleartext = false))
    }

    @Test
    fun rejectsNonStarIntelCredentialShape() {
        assertTrue(CompanionConfigProtocol.validApiKey("star_sk_v1_credential_0123456789"))
        assertFalse(CompanionConfigProtocol.validApiKey("hunter2"))
        assertFalse(CompanionConfigProtocol.validApiKey("star_sk_v1_bad key_0123456789"))
    }

    @Test
    fun validatesRequestIdShape() {
        assertTrue(CompanionConfigProtocol.validRequestId("12345678-1234-1234-1234-123456789abc"))
        assertFalse(CompanionConfigProtocol.validRequestId("short"))
        assertFalse(CompanionConfigProtocol.validRequestId("1234567890123456/invalid"))
    }

    @Test
    fun mapsServerFailuresWithoutLeakingDetails() {
        assertEquals(
            CompanionConfigProtocol.CODE_AUTH_REJECTED,
            CompanionConfigProtocol.errorCode("HTTP 401 unauthorized with secret material"),
        )
        assertEquals(
            "Authentication rejected",
            CompanionConfigProtocol.safeDetail(CompanionConfigProtocol.CODE_AUTH_REJECTED),
        )
        assertEquals(
            CompanionConfigProtocol.CODE_UNREACHABLE,
            CompanionConfigProtocol.errorCode("socket failed with secret text"),
        )
        assertEquals(
            "Could not reach StarIntel",
            CompanionConfigProtocol.safeDetail(CompanionConfigProtocol.CODE_UNREACHABLE),
        )
        assertEquals(
            "Could not save configuration securely",
            CompanionConfigProtocol.safeDetail(CompanionConfigProtocol.CODE_SAVE_FAILED),
        )
    }

    @Test
    fun boundsAckDetail() {
        val bounded = CompanionConfigProtocol.boundedDetail("ok\n" + "x".repeat(200))
        assertFalse(bounded.contains('\n'))
        assertTrue(bounded.length <= CompanionConfigProtocol.MAX_DETAIL_CHARS)
    }
}
