package actor.starintel.wear.sync

import actor.starintel.wear.BuildConfig
import actor.starintel.wear.data.StarIntelRepository
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CompanionConfigService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configMutex = Mutex()

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != CompanionConfigProtocol.CONFIG_PATH) return
        if (messageEvent.data.size > CompanionConfigProtocol.MAX_PAYLOAD_BYTES) {
            sendAck(messageEvent.sourceNodeId, "", false, CompanionConfigProtocol.CODE_INVALID_PAYLOAD)
            return
        }

        val data = runCatching { DataMap.fromByteArray(messageEvent.data) }.getOrNull()
        val requestId = data?.getString("request_id").orEmpty()
        if (data == null) {
            sendAck(messageEvent.sourceNodeId, requestId, false, CompanionConfigProtocol.CODE_INVALID_PAYLOAD)
            return
        }
        if (data.getInt("version", 0) != CompanionConfigProtocol.VERSION) {
            sendAck(messageEvent.sourceNodeId, requestId, false, CompanionConfigProtocol.CODE_UNSUPPORTED)
            return
        }
        if (!CompanionConfigProtocol.validRequestId(requestId)) {
            sendAck(messageEvent.sourceNodeId, requestId, false, CompanionConfigProtocol.CODE_INVALID_PAYLOAD)
            return
        }

        val serverUrl = CompanionConfigProtocol.normalizeServerUrl(
            data.getString("server_url").orEmpty(),
            allowCleartext = BuildConfig.DEBUG,
        )
        val apiKey = data.getString("api_key").orEmpty()
        if (serverUrl == null) {
            sendAck(messageEvent.sourceNodeId, requestId, false, CompanionConfigProtocol.CODE_INVALID_URL)
            return
        }
        if (!CompanionConfigProtocol.validApiKey(apiKey)) {
            sendAck(messageEvent.sourceNodeId, requestId, false, CompanionConfigProtocol.CODE_INVALID_KEY)
            return
        }

        scope.launch {
            configMutex.withLock {
                val repository = StarIntelRepository.get(applicationContext)
                val test = repository.testConnection(serverUrl, apiKey)
                if (!test.reachable) {
                    sendAck(
                        messageEvent.sourceNodeId,
                        requestId,
                        false,
                        CompanionConfigProtocol.errorCode(test.error),
                    )
                    return@withLock
                }

                if (!repository.commitConfiguration(serverUrl, apiKey)) {
                    sendAck(
                        messageEvent.sourceNodeId,
                        requestId,
                        false,
                        CompanionConfigProtocol.CODE_SAVE_FAILED,
                    )
                    return@withLock
                }

                sendAck(
                    nodeId = messageEvent.sourceNodeId,
                    requestId = requestId,
                    ok = true,
                    code = CompanionConfigProtocol.CODE_OK,
                    detail = "Configured · ${test.documentsTotal} docs",
                )
                // Configuration success depends on the committed configuration only.
                // Optional platform services must not delay or prevent its acknowledgment.
                runCatching { StarIntelBackgroundSync.ensureScheduled(applicationContext) }
                runCatching { requestStarIntelTileUpdates(applicationContext) }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sendAck(
        nodeId: String,
        requestId: String,
        ok: Boolean,
        code: String,
        detail: String = CompanionConfigProtocol.safeDetail(code),
    ) {
        val payload = DataMap().apply {
            putInt("version", CompanionConfigProtocol.VERSION)
            putString("request_id", requestId)
            putBoolean("ok", ok)
            putString("code", code)
            putString("detail", CompanionConfigProtocol.boundedDetail(detail))
        }.toByteArray()
        Wearable.getMessageClient(this).sendMessage(nodeId, CompanionConfigProtocol.ACK_PATH, payload)
    }
}
