package actor.starintel.android.api

/** A verified server/principal and frozen credentials; no credentials in identity or display text. */
class BulkImportSession internal constructor(
    val destinationIdentity: String,
    private val sender: (ByteArray) -> ByteArray,
) {
    /** Sends one bounded array once. Any failure has an unknown mutation outcome; never replay. */
    fun sendBatch(documents: ByteArray): ByteArray = sender(documents)

    companion object {
        const val MAX_REQUEST_BYTES = 256 * 1024
        const val MAX_RECEIPT_BYTES = 8192
    }
}
