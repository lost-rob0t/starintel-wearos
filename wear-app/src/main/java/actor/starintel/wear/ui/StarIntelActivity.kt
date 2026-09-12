package actor.starintel.wear.ui

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.sync.StarIntelBackgroundSync
import actor.starintel.wear.sync.requestStarIntelTileUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

abstract class StarIntelActivity : Activity() {
    protected lateinit var palette: StarIntelPalette
        private set

    private val autoSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoSyncJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        palette = StarIntelThemeStore(this).current()
        StarIntelBackgroundSync.ensureScheduled(this)
    }

    override fun onResume() {
        super.onResume()
        palette = StarIntelThemeStore(this).current()
        autoSyncJob?.cancel()
        autoSyncJob = autoSyncScope.launch {
            while (isActive) {
                runCatching {
                    StarIntelRepository.get(applicationContext).snapshot(forceRefresh = true)
                    requestStarIntelTileUpdates(applicationContext)
                }
                delay(FOREGROUND_SYNC_MS)
            }
        }
    }

    override fun onPause() {
        autoSyncJob?.cancel()
        autoSyncJob = null
        super.onPause()
    }

    override fun onDestroy() {
        autoSyncScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FOREGROUND_SYNC_MS = 60_000L
    }
}
