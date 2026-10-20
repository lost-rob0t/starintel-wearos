package actor.starintel.quasar

import org.json.JSONException

/** Only JSON construction before invoking the network operation can establish an unsent request. */
internal class LocalPayloadFailure(cause: JSONException) : RuntimeException(cause)

internal fun <T> prepareMutationPayload(parse: () -> T): T = try {
    parse()
} catch (failure: JSONException) {
    throw LocalPayloadFailure(failure)
}
