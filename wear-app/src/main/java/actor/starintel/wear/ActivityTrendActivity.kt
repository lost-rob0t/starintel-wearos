package actor.starintel.wear

import android.os.Bundle
import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import actor.starintel.wear.complications.ActivityGraphComplicationService
import android.view.Gravity
import android.widget.*
import actor.starintel.wear.complications.ActivityGraphPreferences
import actor.starintel.wear.complications.ActivityGraphRenderer
import actor.starintel.wear.data.*
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelTheme
import actor.starintel.wear.sync.requestStarIntelTileUpdates
import kotlinx.coroutines.*

class ActivityTrendActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var refresh: Job? = null
    private lateinit var chart: ImageView
    private lateinit var status: TextView
    private lateinit var ranges: LinearLayout
    private lateinit var prefs: ActivityGraphPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ActivityGraphPreferences(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 64); setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply { text = "INGEST TREND"; textSize = 18f; setTextColor(palette.accent) })
        chart = ImageView(this).apply { adjustViewBounds = true; minimumHeight = 130 }
        root.addView(chart, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply { textSize = 12f; setTextColor(palette.muted); gravity = Gravity.CENTER }
        root.addView(status)
        ranges = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ranges)
        ActivityRange.entries.forEach { range ->
            ranges.addView(Button(this).apply {
                text = range.label; applyStarIntelTheme(palette)
                setOnClickListener {
                    prefs.setRange(range)
                    render()
                    ComplicationDataSourceUpdateRequester.create(this@ActivityTrendActivity,
                        ComponentName(this@ActivityTrendActivity, ActivityGraphComplicationService::class.java)).requestUpdateAll()
                    requestStarIntelTileUpdates(this@ActivityTrendActivity)
                }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() {
        super.onResume()
        render()
        refresh?.cancel()
        refresh = scope.launch {
            while (isActive) {
                StarIntelRepository.get(this@ActivityTrendActivity).snapshot(forceRefresh = true)
                render(); delay(60_000)
            }
        }
    }
    private fun render() {
        val range = prefs.range()
        val points = ActivityHistoryStore.get(this).points(range)
        chart.setImageBitmap(ActivityGraphRenderer.render(points, range).active)
        chart.contentDescription = "Document additions per observation, ${range.label}. ${points.count { it.documentsAdded != null }} observations."
        status.text = if (points.isEmpty()) "Collecting observations · ${range.label}" else "${range.label} · additions per observation\nGaps and counter resets stay disconnected"
        for (index in 0 until ranges.childCount) ranges.getChildAt(index).isEnabled = ActivityRange.entries[index] != range
    }
    override fun onPause() { refresh?.cancel(); refresh = null; super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
