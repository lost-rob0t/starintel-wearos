package actor.starintel.wear

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.RecentDocuments
import actor.starintel.wear.data.RadarFeedState
import actor.starintel.wear.data.StarIntelApiClient
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.*
import kotlin.math.*

/** Foreground-only bounded document stream. Blips represent age, not geography. */
class RadarActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private lateinit var status: TextView
    private lateinit var rows: LinearLayout
    private lateinit var radar: RadarView
    private var feed = RadarFeedState()
    private val documents get() = feed.documents
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(40))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply { text = "DOCUMENT RADAR"; textSize = 17f; setTextColor(palette.accent); gravity = Gravity.CENTER })
        status = TextView(this).apply { text = "Connecting…"; textSize = 12f; setTextColor(palette.muted); gravity = Gravity.CENTER }
        root.addView(status)
        radar = RadarView()
        root.addView(radar, LinearLayout.LayoutParams(-1, dp(150)))
        root.addView(TextView(this).apply { text = "Recent arrivals · distance = age"; textSize = 11f; setTextColor(palette.muted); gravity = Gravity.CENTER })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rows, LinearLayout.LayoutParams(-1, -2))
        setContentView(ScrollView(this).apply { addView(root); isVerticalScrollBarEnabled = false })
    }
    override fun onResume() {
        super.onResume()
        polling?.cancel()
        polling = scope.launch {
            while (isActive) {
                val result = StarIntelApiClient.get(this@RadarActivity).recentDocuments()
                renderFeed(result)
                delay(15_000)
            }
        }
    }
    internal fun renderFeed(result: RecentDocuments) {
        feed = feed.update(result)
        if (result.error == null) {
            status.text = if (documents.isEmpty()) "No recent documents" else "${documents.size} arrivals · ${if (result.partial) "bounded window" else "live"}"
            status.setTextColor(palette.accent)
            rows.removeAllViews()
            documents.forEach { doc ->
                rows.addView(Button(this@RadarActivity).apply {
                    text = "${doc.title}\n${doc.dtype} · ${doc.dataset}"
                    isAllCaps = false
                    applyStarIntelTheme(palette)
                    contentDescription = "Open ${doc.title}, ${doc.dtype}, ${doc.dataset}"
                    setOnClickListener { startActivity(Intent(this@RadarActivity, DocumentViewerActivity::class.java).putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, doc.id)) }
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        } else {
            status.text = "${if (documents.isEmpty()) "Unavailable" else "Stale"} · ${result.error} · retrying"
            status.setTextColor(palette.warning)
        }
        radar.contentDescription = "Document radar, ${documents.size} arrivals. Select a document from the list below."
        radar.invalidate()
    }
    override fun onPause() { polling?.cancel(); polling = null; super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private inner class RadarView : View(this@RadarActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val radius = min(cx, cy) - dp(10)
            paint.color = palette.accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.alpha = 75
            for (ring in 1..3) canvas.drawCircle(cx, cy, radius * ring / 3f, paint)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint)
            paint.style = Paint.Style.FILL; paint.alpha = 255
            val now = System.currentTimeMillis() / 1000
            val span = max(3600L, now - (documents.minOfOrNull { it.added } ?: now))
            documents.forEach { doc ->
                val angle = (doc.id.hashCode().toLong() and 0xffffffffL) % 360 * PI / 180
                val distance = radius * (0.18f + 0.75f * ((now - doc.added).toFloat() / span).coerceIn(0f, 1f))
                canvas.drawCircle(cx + cos(angle).toFloat() * distance, cy + sin(angle).toFloat() * distance, dp(3).toFloat(), paint)
            }
        }
    }
}
