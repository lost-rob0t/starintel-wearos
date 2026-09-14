package actor.starintel.update

import org.json.JSONObject

object PackageTransferProtocol {
    const val VERSION = 1
    const val CAPABILITY = "starintel_package_receiver_v1"
    const val CHANNEL_PREFIX = "/starintel/packages/v1/"
    const val STATUS_PATH = "/starintel/packages/status/v1"
    const val MAX_HEADER_BYTES = 16 * 1024

    data class Header(
        val transferId: String,
        val artifactId: String,
        val packageName: String,
        val versionCode: Long,
        val size: Long,
        val sha256: String,
    ) {
        fun toBytes(): ByteArray = JSONObject()
            .put("version", VERSION)
            .put("transfer_id", transferId)
            .put("artifact_id", artifactId)
            .put("package", packageName)
            .put("version_code", versionCode)
            .put("bytes", size)
            .put("sha256", sha256.lowercase())
            .toString()
            .toByteArray(Charsets.UTF_8)

        companion object {
            fun parse(bytes: ByteArray): Header {
                require(bytes.size in 2..MAX_HEADER_BYTES) { "Invalid transfer header size" }
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                require(root.getInt("version") == VERSION) { "Unsupported transfer protocol" }
                val transferId = root.getString("transfer_id")
                val artifactId = root.getString("artifact_id")
                val packageName = root.getString("package")
                val versionCode = root.getLong("version_code")
                val size = root.getLong("bytes")
                val sha256 = root.getString("sha256").lowercase()
                require(transferId.matches(Regex("[0-9a-fA-F-]{16,64}"))) { "Invalid transfer id" }
                require(artifactId.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid artifact id" }
                require(packageName.matches(Regex("[A-Za-z0-9_.]{3,160}"))) { "Invalid package name" }
                require(versionCode > 0) { "Invalid version code" }
                require(size in 1..(128L * 1024L * 1024L)) { "Invalid package size" }
                require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid package SHA-256" }
                return Header(transferId, artifactId, packageName, versionCode, size, sha256)
            }
        }
    }

    data class Status(
        val transferId: String,
        val artifactId: String,
        val state: String,
        val detail: String? = null,
    ) {
        fun toBytes(): ByteArray = JSONObject()
            .put("version", VERSION)
            .put("transfer_id", transferId)
            .put("artifact_id", artifactId)
            .put("state", state)
            .apply { if (!detail.isNullOrBlank()) put("detail", detail) }
            .toString()
            .toByteArray(Charsets.UTF_8)

        companion object {
            const val RECEIVING = "receiving"
            const val VERIFYING = "verifying"
            const val WAITING_USER = "waiting_user"
            const val INSTALLING = "installing"
            const val SUCCESS = "success"
            const val FAILURE = "failure"

            fun parse(bytes: ByteArray): Status {
                val root = JSONObject(bytes.toString(Charsets.UTF_8))
                require(root.getInt("version") == VERSION) { "Unsupported status protocol" }
                return Status(
                    transferId = root.getString("transfer_id"),
                    artifactId = root.getString("artifact_id"),
                    state = root.getString("state"),
                    detail = root.optString("detail").takeIf { it.isNotBlank() },
                )
            }
        }
    }
}
