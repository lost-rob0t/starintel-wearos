package actor.starintel.update

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal const val UPDATE_REPOSITORY = "lost-rob0t/starintel-wearos"

object UpdateSources {
    const val MASTER = "master"
    const val TAGGED = "tagged"

    fun label(source: String): String = when {
        source == MASTER -> "MASTER"
        source == TAGGED -> "LATEST TAG"
        source.startsWith("tag:") -> source.removePrefix("tag:")
        else -> source.uppercase()
    }

    fun manifestUrl(source: String): URL = when {
        source == MASTER -> URL("https://github.com/$UPDATE_REPOSITORY/releases/download/master-channel/update.json")
        source == TAGGED -> URL("https://github.com/$UPDATE_REPOSITORY/releases/latest/download/update.json")
        source.startsWith("tag:") -> {
            val tag = source.removePrefix("tag:")
            require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+~-]*"))) { "Invalid update tag" }
            URL("https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/update.json")
        }
        else -> error("Unsupported update source: $source")
    }
}

data class UpdateArtifact(
    val packageName: String,
    val url: String,
    val sha256: String,
    val size: Long,
)

data class UpdateManifest(
    val channel: String,
    val ref: String,
    val commit: String,
    val versionCode: Long,
    val versionName: String,
    val artifacts: Map<String, UpdateArtifact>,
) {
    fun artifact(name: String): UpdateArtifact =
        artifacts[name] ?: error("Update manifest does not contain artifact: $name")
}

object UpdateFeed {
    private const val SCHEMA = 1
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_APK_BYTES = 96L * 1024L * 1024L

    fun fetch(source: String): UpdateManifest {
        val text = readText(UpdateSources.manifestUrl(source), MAX_MANIFEST_BYTES)
        return parse(text)
    }

    fun parse(text: String): UpdateManifest {
        val root = JSONObject(text)
        require(root.getInt("schema") == SCHEMA) { "Unsupported update manifest schema" }

        val artifactsObject = root.getJSONObject("artifacts")
        val artifacts = buildMap {
            for (key in artifactsObject.keys()) {
                val value = artifactsObject.getJSONObject(key)
                val sha = value.getString("sha256").lowercase()
                require(sha.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for $key" }
                val size = value.getLong("bytes")
                require(size in 1..MAX_APK_BYTES) { "Invalid APK size for $key" }
                val url = value.getString("url")
                require(URI(url).scheme == "https") { "Update APK must use HTTPS" }
                put(
                    key,
                    UpdateArtifact(
                        packageName = value.getString("package"),
                        url = url,
                        sha256 = sha,
                        size = size,
                    ),
                )
            }
        }

        return UpdateManifest(
            channel = root.getString("channel"),
            ref = root.getString("ref"),
            commit = root.getString("commit"),
            versionCode = root.getLong("version_code"),
            versionName = root.getString("version_name"),
            artifacts = artifacts,
        )
    }

    fun download(context: Context, name: String, artifact: UpdateArtifact): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val output = File(directory, "$name.apk")
        val temporary = File(directory, "$name.apk.part")
        temporary.delete()

        val connection = open(URL(artifact.url))
        try {
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_APK_BYTES) error("Update APK is too large")
            if (contentLength > 0 && contentLength != artifact.size) {
                error("Update APK size does not match manifest")
            }

            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { outputStream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) error("Update APK exceeded size limit")
                        outputStream.write(buffer, 0, count)
                    }
                    outputStream.fd.sync()
                    if (total != artifact.size) error("Update APK is truncated")
                }
            }
        } finally {
            connection.disconnect()
        }

        val actual = sha256(temporary)
        if (!actual.equals(artifact.sha256, ignoreCase = true)) {
            temporary.delete()
            error("Update APK checksum mismatch")
        }

        output.delete()
        if (!temporary.renameTo(output)) {
            temporary.copyTo(output, overwrite = true)
            temporary.delete()
        }
        return output
    }

    private fun readText(url: URL, maxBytes: Int): String {
        val connection = open(url)
        try {
            val length = connection.contentLength
            if (length > maxBytes) error("Update manifest is too large")
            return connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.toByteArray(Charsets.UTF_8).size > maxBytes) error("Update manifest is too large")
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.1")
            setRequestProperty("User-Agent", "StarIntel-WearOS-Updater/1")
            connect()
            if (responseCode !in 200..299) {
                error("Update server returned HTTP $responseCode")
            }
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
