package actor.starintel.wear.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-establish background sync after a reboot.
 *
 * When a Wear build rejects the persisted periodic job, the fallback job is
 * non-persisted and dies at reboot; without this receiver the watch keeps
 * serving stale cache until the next manual app open.
 */
class StarIntelBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val scheduled = StarIntelBackgroundSync.ensureScheduled(context)
        if (!scheduled) Log.w("StarIntelBoot", "Could not re-schedule background sync after boot")
    }
}
