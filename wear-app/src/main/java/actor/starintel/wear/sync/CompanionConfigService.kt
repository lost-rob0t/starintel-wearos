package actor.starintel.wear.sync

import androidx.wear.tiles.TileService
import actor.starintel.wear.BuildConfig
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.tiles.CorpusTileService
import actor.starintel.wear.tiles.OpsTileService
import actor.starintel.wear.tiles.TargetsTileService
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CompanionConfigService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != CompanionConfigProtocol.CONFIG_PATH) return

        val data = runCatching { DataMap.fromByteArray(messageEvent.data) }.getOrNull()
        val nonce = data?.getString("nonce").orEmpty()
        if (data == null || data.getInt("version", 0) != CompanionConfigProtocol.VERSION) {
            sendAck(messageEvent.sourceNodeId, nonce, false, "Unsupported configuration payload")
            return
        }

        val serverUrl = CompanionConfigProtocol.normalizeServerUrl(
            data.getString("server_url").orEmpty(),
            allowCleartext = BuildConfig.DEBUG,
        )
        val apiKey = data.getString("api_key").orEmpty()

        if (serverUrl == null) {
            sendAck(messageEvent.sourceNodeId, nonce, false, "Invalid StarIntel server URL")
            return
        }
        if (!CompanionConfigProtocol.validApiKey(apiKey)) {
            sendAck(messageEvent.sourceNodeId, nonce, false, "Invalid StarIntel API key")
            return
        }

        scope.launch {
            val repository = StarIntelRepository.get(applicationContext)
            val test = repository.testConnection(serverUrl, apiKey)
            if (!test.reachable) {
                sendAck(
                    messageEvent.sourceNodeId,
                    nonce,
                    false,
                    CompanionConfigProtocol.safeDetail(test.error),
                )
                return@launch
            }

            repository.setBaseUrl(serverUrl)
            repository.setApiKey(apiKey)
            requestTileUpdates()
            sendAck(
                messageEvent.sourceNodeId,
                nonce,
                true,
                "Authenticated · ${test.documentsTotal} docs",
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sendAck(nodeId: String, nonce: String, ok: Boolean, detail: String) {
        val payload = DataMap().apply {
            putInt("version", CompanionConfigProtocol.VERSION)
            putString("nonce", nonce)
            putBoolean("ok", ok)
            putString("detail", detail)
        }.toByteArray()
        Wearable.getMessageClient(this)
            .sendMessage(nodeId, CompanionConfigProtocol.ACK_PATH, payload)
    }

    private fun requestTileUpdates() {
        val updater = TileService.getUpdater(applicationContext)
        updater.requestUpdate(OpsTileService::class.java)
        updater.requestUpdate(TargetsTileService::class.java)
        updater.requestUpdate(CorpusTileService::class.java)
    }
}
