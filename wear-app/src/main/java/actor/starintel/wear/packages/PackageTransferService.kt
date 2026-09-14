package actor.starintel.wear.packages

import actor.starintel.update.PackageTransferProtocol
import actor.starintel.update.UpdateFeed
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PackageTransferService : WearableListenerService() {
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (!channel.path.startsWith(PackageTransferProtocol.CHANNEL_PREFIX)) return
        val channelClient = Wearable.getChannelClient(this)
        channelClient.getInputStream(channel)
            .addOnSuccessListener { raw ->
                Thread {
                    try {
                        receive(channel, DataInputStream(BufferedInputStream(raw)))
                    } catch (error: Throwable) {
                        sendStatus(
                            channel.nodeId,
                            PackageTransferProtocol.Status(
                                transferId = channel.path.substringAfterLast('/'),
                                artifactId = "unknown",
                                state = PackageTransferProtocol.Status.FAILURE,
                                detail = error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    } finally {
                        runCatching { raw.close() }
                        channelClient.close(channel)
                    }
                }.start()
            }
            .addOnFailureListener { error ->
                sendStatus(
                    channel.nodeId,
                    PackageTransferProtocol.Status(
                        transferId = channel.path.substringAfterLast('/'),
                        artifactId = "unknown",
                        state = PackageTransferProtocol.Status.FAILURE,
                        detail = "Could not open package stream: ${error.message}",
                    ),
                )
            }
    }

    private fun receive(channel: ChannelClient.Channel, input: DataInputStream) {
        val headerSize = input.readInt()
        require(headerSize in 2..PackageTransferProtocol.MAX_HEADER_BYTES) { "Invalid package header length" }
        val headerBytes = ByteArray(headerSize)
        input.readFully(headerBytes)
        val header = PackageTransferProtocol.Header.parse(headerBytes)
        require(channel.path.endsWith(header.transferId)) { "Transfer id/path mismatch" }

        sendStatus(channel.nodeId, header, PackageTransferProtocol.Status.RECEIVING)
        val directory = File(cacheDir, "incoming-packages").apply { mkdirs() }
        val part = File(directory, "${header.transferId}.apk.part")
        val apk = File(directory, "${header.transferId}.apk")
        part.delete()
        apk.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(part).use { output ->
            val buffer = ByteArray(32 * 1024)
            var remaining = header.size
            while (remaining > 0L) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) error("Package stream ended early")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        require(part.length() == header.size) { "Received package size mismatch" }
        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        require(actualHash.equals(header.sha256, ignoreCase = true)) { "Received package checksum mismatch" }

        sendStatus(channel.nodeId, header, PackageTransferProtocol.Status.VERIFYING)
        val archive = archiveInfo(part) ?: error("Android could not inspect the received APK")
        require(archive.packageName == header.packageName) {
            "Package identity mismatch: expected ${header.packageName}, got ${archive.packageName}"
        }
        require(archive.longVersionCode == header.versionCode) {
            "Package version mismatch: expected ${header.versionCode}, got ${archive.longVersionCode}"
        }
        verifyInstalledContinuity(archive)

        if (!part.renameTo(apk)) {
            part.copyTo(apk, overwrite = true)
            part.delete()
        }
        sendStatus(
            channel.nodeId,
            header,
            PackageTransferProtocol.Status.WAITING_USER,
            "Package verified. Approve installation on the watch if Wear OS asks.",
        )

        startActivity(
            Intent(this, PackageInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(PackageInstallActivity.EXTRA_APK, apk.absolutePath)
                .putExtra(PackageInstallActivity.EXTRA_PACKAGE, header.packageName)
                .putExtra(PackageInstallActivity.EXTRA_NODE, channel.nodeId)
                .putExtra(PackageInstallActivity.EXTRA_TRANSFER, header.transferId)
                .putExtra(PackageInstallActivity.EXTRA_ARTIFACT, header.artifactId),
        )
    }

    private fun archiveInfo(file: File): PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

    private fun installedInfo(packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun verifyInstalledContinuity(archive: PackageInfo) {
        val installed = installedInfo(archive.packageName) ?: return
        require(archive.longVersionCode >= installed.longVersionCode) {
            "Downgrade rejected: installed ${installed.longVersionCode}, received ${archive.longVersionCode}"
        }
        val oldSigners = signerDigests(installed)
        val newSigners = signerDigests(archive)
        require(oldSigners.isNotEmpty() && newSigners.isNotEmpty() && oldSigners.intersect(newSigners).isNotEmpty()) {
            "Signing certificate mismatch"
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signing = info.signingInfo ?: return emptySet()
        val certificates = if (signing.hasMultipleSigners()) {
            signing.apkContentsSigners
        } else {
            signing.signingCertificateHistory
        }
        return certificates.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun sendStatus(nodeId: String, header: PackageTransferProtocol.Header, state: String, detail: String? = null) {
        sendStatus(
            nodeId,
            PackageTransferProtocol.Status(header.transferId, header.artifactId, state, detail),
        )
    }

    private fun sendStatus(nodeId: String, status: PackageTransferProtocol.Status) {
        Wearable.getMessageClient(this)
            .sendMessage(nodeId, PackageTransferProtocol.STATUS_PATH, status.toBytes())
    }
}
