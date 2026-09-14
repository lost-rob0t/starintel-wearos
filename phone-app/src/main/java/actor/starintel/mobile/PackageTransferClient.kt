package actor.starintel.mobile

import actor.starintel.update.PackageTransferProtocol
import actor.starintel.update.UpdateArtifact
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.wearable.Wearable
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

class PackageTransferClient(context: Context) {
    private val channelClient = Wearable.getChannelClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())

    fun send(
        nodeId: String,
        artifact: UpdateArtifact,
        versionCode: Long,
        apk: File,
        onProgress: (Int) -> Unit,
        onReady: (String) -> Unit,
        onFailure: (String, Throwable) -> Unit,
    ): String {
        val transferId = UUID.randomUUID().toString()
        val path = PackageTransferProtocol.CHANNEL_PREFIX + transferId
        val header = PackageTransferProtocol.Header(
            transferId = transferId,
            artifactId = artifact.id,
            packageName = artifact.packageName,
            versionCode = versionCode,
            size = artifact.size,
            sha256 = artifact.sha256,
        ).toBytes()

        channelClient.openChannel(nodeId, path)
            .addOnSuccessListener { channel ->
                channelClient.getOutputStream(channel)
                    .addOnSuccessListener { raw ->
                        Thread {
                            try {
                                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                                    output.writeInt(header.size)
                                    output.write(header)
                                    apk.inputStream().use { input ->
                                        val buffer = ByteArray(32 * 1024)
                                        var sent = 0L
                                        var lastPercent = -1
                                        while (true) {
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            output.write(buffer, 0, count)
                                            sent += count
                                            val percent = ((sent * 100L) / artifact.size.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
                                            if (percent != lastPercent) {
                                                lastPercent = percent
                                                main.post { onProgress(percent) }
                                            }
                                        }
                                        require(sent == artifact.size) { "Downloaded APK size changed before transfer" }
                                    }
                                    output.flush()
                                }
                                channelClient.close(channel)
                                main.post { onReady(transferId) }
                            } catch (error: Throwable) {
                                channelClient.close(channel)
                                main.post { onFailure(transferId, error) }
                            }
                        }.start()
                    }
                    .addOnFailureListener { error ->
                        channelClient.close(channel)
                        onFailure(transferId, error)
                    }
            }
            .addOnFailureListener { error -> onFailure(transferId, error) }
        return transferId
    }
}
