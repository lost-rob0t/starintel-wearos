package actor.starintel.update

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

const val UPDATE_REPOSITORY = "lost-rob0t/starintel-wearos"

object UpdateSources {
    const val MASTER = "master"
    const val LATEST = "latest"

    fun tag(tag: String): String = "tag:$tag"

    fun label(source: String): String = when {
        source == MASTER -> "MASTER"
        source == LATEST -> "LATEST RELEASE"
        source.startsWith("tag:") -> source.removePrefix("tag:")
        else -> source.uppercase()
    }

    fun taggedManifestUrl(tag: String): URL {
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+~-]*"))) { "Invalid update tag" }
        return URL("https://github.com/$UPDATE_REPOSITORY/releases/download/$tag/update.json")
    }

    fun masterManifestUrl(): URL =
        URL("https://github.com/$UPDATE_REPOSITORY/releases/download/master-channel/update.json")
}

data class UpdateRelease(
    val tagName: String,
    val displayName: String,
    val prerelease: Boolean,
    val publishedAt: String,
)

data class UpdateArtifact(
    val id: String,
    val packageName: String,
    val target: String,
    val installOrder: Int,
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

    fun wearArtifacts(): List<UpdateArtifact> = artifacts.values
        .filter { it.target == "wear" }
        .sortedWith(compareBy<UpdateArtifact> { it.installOrder }.thenBy { it.id })
}

object UpdateFeed {
    private const val SCHEMA = 2
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_MANIFEST_BYTES = 128 * 1024
    private const val MAX_RELEASE_LIST_BYTES = 512 * 1024
    private const val MAX_APK_BYTES = 128L * 1024L * 1024L

    fun fetch(source: String): UpdateManifest {
        val url = when {
            source == UpdateSources.MASTER -> UpdateSources.masterManifestUrl()
            source == UpdateSources.LATEST -> {
                val latest = listReleases().firstOrNull()
                    ?: error("No StarIntel releases are available")
                UpdateSources.taggedManifestUrl(latest.tagName)
            }
            source.startsWith("tag:") -> UpdateSources.taggedManifestUrl(source.removePrefix("tag:"))
            else -> error("Unsupported update source: $source")
        }
        return parse(readText(url, MAX_MANIFEST_BYTES))
    }

    fun listReleases(limit: Int = 20): List<UpdateRelease> {
        require(limit in 1..50)
        val url = URL("https://api.github.com/repos/$UPDATE_REPOSITORY/releases?per_page=$limit")
        val array = JSONArray(readText(url, MAX_RELEASE_LIST_BYTES, githubApi = true))
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.getJSONObject(index)
                if (value.optBoolean("draft", false)) continue
                val tag = value.getString("tag_name")
                if (tag == "master-channel") continue
                if (!tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?"))) continue
                add(
                    UpdateRelease(
                        tagName = tag,
                        displayName = value.optString("name").takeIf { it.isNotBlank() } ?: tag,
                        prerelease = value.optBoolean("prerelease", false),
                        publishedAt = value.optString("published_at"),
                    )
                )
            }
        }
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
                val target = value.getString("target")
                require(target == "phone" || target == "wear") { "Invalid target for $key" }
                put(
                    key,
                    UpdateArtifact(
                        id = key,
                        packageName = value.getString("package"),
                        target = target,
                        installOrder = value.optInt("install_order", 50),
                        url = url,
                        sha256 = sha,
                        size = size,
                    ),
                )
            }
        }
        require(artifacts.containsKey("phone")) { "Manifest is missing phone artifact" }
        require(artifacts.containsKey("wear")) { "Manifest is missing Wear artifact" }

        return UpdateManifest(
            channel = root.getString("channel"),
            ref = root.getString("ref"),
            commit = root.getString("commit"),
            versionCode = root.getLong("version_code"),
            versionName = root.getString("version_name"),
            artifacts = artifacts,
        )
    }

    fun download(context: Context, artifact: UpdateArtifact): File {
        val directory = File(context.cacheDir, "starintel-updates").apply { mkdirs() }
        val safeName = artifact.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val output = File(directory, "$safeName.apk")
        val temporary = File(directory, "$safeName.apk.part")
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

    fun sha256(file: File): String {
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

    private fun readText(url: URL, maxBytes: Int, githubApi: Boolean = false): String {
        val connection = open(url, githubApi)
        try {
            val length = connection.contentLength
            if (length > maxBytes) error("Update response is too large")
            return connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText()
                if (text.toByteArray(Charsets.UTF_8).size > maxBytes) error("Update response is too large")
                text
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL, githubApi: Boolean = false): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", if (githubApi) "application/vnd.github+json" else "application/json, application/octet-stream;q=0.9, */*;q=0.1")
            setRequestProperty("User-Agent", "StarIntel-Companion/1")
            if (githubApi) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connect()
            if (responseCode !in 200..299) {
                error("Update server returned HTTP $responseCode")
            }
        }
}
