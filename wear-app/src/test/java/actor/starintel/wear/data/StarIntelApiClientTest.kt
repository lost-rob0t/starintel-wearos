package actor.starintel.wear.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StarIntelApiClientTest {
    @Test
    fun parsesDocumentAndTargetCapabilities() {
        val caps = parseCapabilities(
            """
            {
              "status": "ok",
              "data": {
                "endpoints": [
                  {"id":"document_read","method":"GET","path":"/document/:id","legacy":true},
                  {"id":"target_create_v1","method":"POST","path":"/api/v1/targets","legacy":false},
                  {"id":"target_create","method":"POST","path":"/new/target/:actor","legacy":true}
                ],
                "compatibility": {"legacy_routes": true}
              }
            }
            """.trimIndent(),
        )

        assertTrue(caps.legacyRoutes)
        assertEquals("/document/:id", caps.endpointById("document_read")?.path)
        val v1 = caps.endpoint("POST", "/api/v1/targets")
        assertNotNull(v1)
        assertFalse(v1!!.legacy)
    }

    @Test
    fun missingOptionalSectionsProduceSafeEmptyCapabilities() {
        val caps = parseCapabilities("{\"status\":\"ok\",\"data\":{}}")

        assertTrue(caps.endpoints.isEmpty())
        assertFalse(caps.legacyRoutes)
    }
}
