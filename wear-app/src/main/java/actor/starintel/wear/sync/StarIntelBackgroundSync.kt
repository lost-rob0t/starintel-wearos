package actor.starintel.wear.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.wear.tiles.TileService
import actor.starintel.wear.DocumentViewerActivity
import actor.starintel.wear.R
import actor.starintel.wear.SearchActivity
import actor.starintel.wear.data.SavedSearch
import actor.starintel.wear.data.SavedSearchStore
import actor.starintel.wear.data.SearchHit
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSearchClient
import actor.starintel.wear.tiles.ActivityTileService
import actor.starintel.wear.tiles.CorpusTileService
import actor.starintel.wear.tiles.OpsTileService
import actor.starintel.wear.tiles.SearchTileService
import actor.starintel.wear.tiles.TargetsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object StarIntelBackgroundSync {
    const val MIN_PERIOD_MINUTES = 15
    private const val JOB_ID = 0x535449
    private const val TAG = "StarIntelSync"

    /**
     * Background sync is an optimization, never a prerequisite for opening the UI.
     *
     * Some Wear OS vendor builds reject persisted jobs even when the manifest contains
     * RECEIVE_BOOT_COMPLETED. A scheduler exception used to escape from Activity.onCreate(),
     * which made every launcher surface crash. Keep the persisted job when supported and
     * fall back to a non-persisted periodic job when the platform rejects it.
     */
    fun ensureScheduled(context: Context): Boolean {
        val appContext = context.applicationContext
        val scheduler = runCatching { appContext.getSystemService(JobScheduler::class.java) }
            .onFailure { Log.w(TAG, "JobScheduler unavailable", it) }
            .getOrNull()
            ?: return false

        val existing = runCatching { scheduler.getPendingJob(JOB_ID) }
            .onFailure { Log.w(TAG, "Could not inspect pending sync job", it) }
            .getOrNull()
        if (existing != null) return true

        if (schedule(scheduler, appContext, persisted = true)) return true

        // Samsung/Wear OS builds can reject persisted periodic jobs. A non-persisted job is
        // still useful and will be re-established the next time any StarIntel surface opens.
        runCatching { scheduler.cancel(JOB_ID) }
        return schedule(scheduler, appContext, persisted = false)
    }

    fun cancel(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }.onFailure { Log.w(TAG, "Could not cancel sync job", it) }
    }

    private fun schedule(
        scheduler: JobScheduler,
        context: Context,
        persisted: Boolean,
    ): Boolean = runCatching {
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, StarIntelSyncJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(MIN_PERIOD_MINUTES * 60_000L)
        if (persisted) builder.setPersisted(true)

        scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }.onFailure {
        Log.w(TAG, "Could not schedule ${if (persisted) "persisted" else "fallback"} sync job", it)
    }.getOrDefault(false)
}

class StarIntelSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                runCatching {
                    StarIntelRepository.get(applicationContext).snapshot(forceRefresh = true)
                }.onFailure { Log.w(TAG, "Background stats refresh failed", it) }

                runCatching {
                    runSavedSearches(applicationContext)
                }.onFailure { Log.w(TAG, "Saved-search refresh failed", it) }

                requestStarIntelTileUpdates(applicationContext)
            } finally {
                runCatching { jobFinished(params, false) }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StarIntelSyncJob"
    }
}

internal suspend fun runSavedSearches(context: Context, nowMs: Long = System.currentTimeMillis()) {
    val store = SavedSearchStore(context)
    val client = StarIntelSearchClient.get(context)
    store.due(nowMs).forEach { saved ->
        runCatching {
            val result = client.search(saved.query, limit = 50)
            if (result.error != null) return@runCatching
            val update = store.recordRun(saved.id, result.hits.map { it.id }, nowMs) ?: return@runCatching
            if (update.newIds.isEmpty()) return@runCatching
            val newHits = result.hits.filter { it.id in update.newIds }
            StarIntelSearchNotifier.notify(context, saved, newHits)
        }.onFailure {
            Log.w("StarIntelSearch", "Saved search ${saved.id} failed", it)
        }
    }
}

object StarIntelSearchNotifier {
    private const val CHANNEL_ID = "starintel_search_matches"
    private const val CHANNEL_NAME = "StarIntel search matches"

    fun notify(context: Context, saved: SavedSearch, hits: List<SearchHit>) {
        if (hits.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "New matches from saved StarIntel searches"
                    },
                )
            }

            val destination = if (hits.size == 1) {
                Intent(context, DocumentViewerActivity::class.java)
                    .putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, hits.first().id)
            } else {
                Intent(context, SearchActivity::class.java)
                    .putExtra(SearchActivity.EXTRA_QUERY, saved.query)
                    .putExtra(SearchActivity.EXTRA_SAVED_SEARCH_ID, saved.id)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

            val pendingIntent = PendingIntent.getActivity(
                context,
                saved.id.hashCode(),
                destination,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val title = if (hits.size == 1) "${saved.label}: new match" else "${saved.label}: ${hits.size} new matches"
            val text = if (hits.size == 1) hits.first().title else hits.take(3).joinToString(" · ") { it.title }
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_starintel)
                .setContentTitle(title.take(80))
                .setContentText(text.take(180))
                .setCategory(Notification.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(saved.id.hashCode(), notification)
        }.onFailure {
            Log.w("StarIntelSearch", "Could not post saved-search notification", it)
        }
    }
}

fun requestStarIntelTileUpdates(context: Context): Boolean {
    val updater = runCatching { TileService.getUpdater(context.applicationContext) }
        .onFailure { Log.w("StarIntelTiles", "Tile updater unavailable", it) }
        .getOrNull()
        ?: return false

    var requested = false
    listOf(
        OpsTileService::class.java,
        TargetsTileService::class.java,
        CorpusTileService::class.java,
        ActivityTileService::class.java,
        SearchTileService::class.java,
    ).forEach { service ->
        runCatching { updater.requestUpdate(service) }
            .onSuccess { requested = true }
            .onFailure { Log.w("StarIntelTiles", "Tile refresh failed for ${service.simpleName}", it) }
    }
    return requested
}
