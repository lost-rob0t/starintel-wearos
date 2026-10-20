package actor.starintel.quasar

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MutationPreflightTest {
    @Test fun malformedLocalPayloadIsDistinguishedBeforeTransport() {
        var sent = false
        assertThrows(LocalPayloadFailure::class.java) {
            prepareMutationPayload { JSONObject("not-json") }
            sent = true
        }
        assertFalse(sent)
        assertThrows(LocalPayloadFailure::class.java) { prepareMutationPayload { JSONArray("not-json") } }
    }
    @Test fun responseParsingOutsidePreflightCannotBeClassifiedUnsent() {
        var sent = false
        val failure = assertThrows(JSONException::class.java) {
            prepareMutationPayload { JSONObject("{}") }
            sent = true
            JSONObject("malformed successful response")
        }
        assertTrue(sent)
        assertFalse((failure as Throwable) is LocalPayloadFailure)
    }
}
