package actor.starintel.wear.packages

import actor.starintel.update.PackageTransferProtocol
import android.content.Context
import com.google.android.gms.wearable.Wearable

internal object PackageStatusMessenger {
    private const val PREFS = "starintel_package_transfer_status"

    fun send(context: Context, nodeId: String, status: PackageTransferProtocol.Status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(status.transferId, status.toBytes().toString(Charsets.UTF_8))
            .apply()
        Wearable.getMessageClient(context)
            .sendMessage(nodeId, PackageTransferProtocol.STATUS_PATH, status.toBytes())
    }

    fun read(context: Context, transferId: String): PackageTransferProtocol.Status? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(transferId, null)
            ?.toByteArray(Charsets.UTF_8)
            ?.let { runCatching { PackageTransferProtocol.Status.parse(it) }.getOrNull() }
}
