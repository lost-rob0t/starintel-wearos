package actor.starintel.quasar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StarServerClientTest {
    @Test
    fun searchRowPrefersEmbeddedDocumentIdentity() {
        val row = JSONObject("""{"doc":{"_id":"person:alice","dtype":"person"}}""")
        assertEquals("person:alice", resultId(row))
        assertEquals("person", resultDocument(row).getString("dtype"))
    }

    @Test
    fun loginResponseCapturesMintedBearerAndPasswordState() {
        val login = parseLoginResponse(
            JSONObject(
                """{"api_key":"star_sk_v1_credential_secret","user":{"username":"alice","must_change_password":true}}""",
            ),
        )
        assertEquals("star_sk_v1_credential_secret", login.apiKey)
        assertEquals("alice", login.username)
        assertEquals(true, login.mustChangePassword)
    }
}
