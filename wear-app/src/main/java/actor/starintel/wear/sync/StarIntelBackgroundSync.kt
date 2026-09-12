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

    fun ensureScheduled(context: Context) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return

        val info = JobInfo.Builder(
            JOB_ID,
            ComponentName(appContext, StarIntelSyncJobService::class.java),
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(MIN_PERIOD_MINUTES * 60_000L)
            .build()
        scheduler.schedule(info)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

class StarIntelSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                StarIntelRepository.get(applicationContext).snapshot(forceRefresh = true)
                runSavedSearches(applicationContext)
                requestStarIntelTileUpdates(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal suspend fun runSavedSearches(context: Context, nowMs: Long = System.currentTimeMillis()) {
    val store = SavedSearchStore(context)
    val client = StarIntelSearchClient.get(context)
    store.due(nowMs).forEach { saved ->
        val result = client.search(saved.query, limit = 50)
        if (result.error != null) return@forEach
        val update = store.recordRun(saved.id, result.hits.map { it.id }, nowMs) ?: return@forEach
        if (update.newIds.isEmpty()) return@forEach
        val newHits = result.hits.filter { it.id in update.newIds }
        StarIntelSearchNotifier.notify(context, saved, newHits)
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

        val manager = context.getSystemService(NotificationManager::class.java)
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
    }
}

fun requestStarIntelTileUpdates(context: Context) {
    val updater = TileService.getUpdater(context.applicationContext)
    updater.requestUpdate(OpsTileService::class.java)
    updater.requestUpdate(TargetsTileService::class.java)
    updater.requestUpdate(CorpusTileService::class.java)
    updater.requestUpdate(ActivityTileService::class.java)
    updater.requestUpdate(SearchTileService::class.java)
}
