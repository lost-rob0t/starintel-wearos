package actor.starintel.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {
    @Test
    fun normalizesHttpsOrigin() {
        assertEquals(
            "https://starintel.example",
            CompanionProtocol.normalizeServerUrl(" https://starintel.example/ ", allowCleartext = false),
        )
    }

    @Test
    fun cleartextIsDebugOnly() {
        assertNull(CompanionProtocol.normalizeServerUrl("http://10.0.0.2:8080", allowCleartext = false))
        assertEquals(
            "http://10.0.0.2:8080",
            CompanionProtocol.normalizeServerUrl("http://10.0.0.2:8080", allowCleartext = true),
        )
    }

    @Test
    fun rejectsAnythingBeyondServerOrigin() {
        assertNull(CompanionProtocol.normalizeServerUrl("https://starintel.example/api", allowCleartext = false))
        assertNull(CompanionProtocol.normalizeServerUrl("https://starintel.example?token=nope", allowCleartext = false))
        assertNull(CompanionProtocol.normalizeServerUrl("https://user:pass@starintel.example", allowCleartext = false))
    }

    @Test
    fun validatesApiKeyShapeWithoutWhitespace() {
        assertTrue(CompanionProtocol.validApiKey("star_sk_v1_credential_0123456789"))
        assertFalse(CompanionProtocol.validApiKey("password"))
        assertFalse(CompanionProtocol.validApiKey("star_sk_v1_credential secret 0123456789"))
    }

    @Test
    fun validatesRequestIds() {
        assertTrue(CompanionProtocol.validRequestId("12345678-1234-1234-1234-123456789abc"))
        assertFalse(CompanionProtocol.validRequestId("short"))
        assertFalse(CompanionProtocol.validRequestId("1234567890123456/invalid"))
    }

    @Test
    fun boundsAndSanitizesAcknowledgementText() {
        val detail = "ok\n" + "x".repeat(200)
        val bounded = CompanionProtocol.boundedDetail(detail)
        assertFalse(bounded.contains('\n'))
        assertTrue(bounded.length <= CompanionProtocol.MAX_DETAIL_CHARS)
    }

    @Test
    fun mapsFailureCodesToHumanCopy() {
        assertEquals(
            "StarIntel rejected the API key",
            CompanionProtocol.userMessage(CompanionProtocol.CODE_AUTH_REJECTED, "ignored"),
        )
        assertEquals(
            "Watch could not reach the StarIntel server",
            CompanionProtocol.userMessage(CompanionProtocol.CODE_UNREACHABLE, "ignored"),
        )
    }
}
