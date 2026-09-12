package actor.starintel.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.SavedSearch
import actor.starintel.wear.data.SavedSearchStore
import actor.starintel.wear.data.StarIntelSearchClient
import actor.starintel.wear.sync.StarIntelBackgroundSync
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelInput
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SearchAlertsActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var store: SavedSearchStore
    private lateinit var query: EditText
    private lateinit var label: EditText
    private lateinit var status: TextView
    private lateinit var monitors: LinearLayout
    private lateinit var interval: Button
    private var intervalMinutes = 60
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SavedSearchStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "SEARCH ALERTS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Notify when a saved query gets new matches"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        query = EditText(this).apply {
            setSingleLine(true)
            hint = "search query"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            setText(intent.getStringExtra(EXTRA_QUERY).orEmpty())
            applyStarIntelInput(palette)
        }
        root.addView(query, matchWrap(top = 7))

        label = EditText(this).apply {
            setSingleLine(true)
            hint = "alert name (optional)"
            textSize = 11f
            inputType = InputType.TYPE_CLASS_TEXT
            applyStarIntelInput(palette)
        }
        root.addView(label, matchWrap(top = 3))

        interval = Button(this).apply {
            text = intervalLabel(intervalMinutes)
            applyStarIntelTheme(palette)
            setOnClickListener {
                intervalMinutes = nextInterval(intervalMinutes)
                text = intervalLabel(intervalMinutes)
            }
        }
        root.addView(interval, matchWrap(top = 3))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "TEST"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener { testQuery() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this).apply {
            text = "SAVE + ON"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener { saveAlert() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(actions, matchWrap(top = 3))

        status = TextView(this).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
            visibility = android.view.View.GONE
        }
        root.addView(status, matchWrap(top = 4))

        monitors = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(monitors, matchWrap(top = 8))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        intent.getStringExtra(EXTRA_SAVED_SEARCH_ID)?.let(::loadAlert)
        renderAlerts()
    }

    override fun onResume() {
        super.onResume()
        if (::monitors.isInitialized) renderAlerts()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun testQuery() {
        val q = query.text.toString().trim()
        if (q.isBlank()) return showStatus("Enter a query", warning = true)
        showStatus("Testing…")
        scope.launch {
            val response = StarIntelSearchClient.get(this@SearchAlertsActivity).search(q, limit = 8)
            if (response.error != null) {
                showStatus(response.error, warning = true)
            } else {
                val total = response.totalRows ?: response.hits.size.toLong()
                showStatus("${response.hits.size} preview · $total total match${if (total == 1L) "" else "es"}", accent = true)
            }
        }
    }

    private fun saveAlert() {
        val q = query.text.toString().trim()
        if (q.isBlank()) return showStatus("Enter a query before saving", warning = true)
        val saved = runCatching {
            store.upsert(
                id = editingId,
                label = label.text.toString(),
                query = q,
                intervalMinutes = intervalMinutes,
                enabled = true,
            )
        }.getOrElse {
            showStatus(it.message ?: "Could not save alert", warning = true)
            return
        }
        editingId = saved.id
        StarIntelBackgroundSync.ensureScheduled(this)
        requestNotificationPermissionIfNeeded()
        showStatus("Saved · ${intervalLabel(saved.intervalMinutes)}", accent = true)
        renderAlerts()
    }

    private fun loadAlert(id: String) {
        val saved = store.get(id) ?: return
        editingId = saved.id
        query.setText(saved.query)
        label.setText(saved.label)
        intervalMinutes = saved.intervalMinutes
        interval.text = intervalLabel(intervalMinutes)
    }

    private fun renderAlerts() {
        monitors.removeAllViews()
        monitors.addView(TextView(this).apply {
            text = "ALERTS · ${store.activeCount()} ACTIVE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())

        val saved = store.list()
        if (saved.isEmpty()) {
            monitors.addView(TextView(this).apply {
                text = "No search alerts"
                textSize = 10f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
            }, matchWrap(top = 4))
            return
        }
        saved.forEach { monitors.addView(alertCard(it), matchWrap(top = 4)) }
    }

    private fun alertCard(item: SavedSearch): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(palette.surface)
        addView(TextView(this@SearchAlertsActivity).apply {
            text = item.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            maxLines = 2
            setOnClickListener { loadAlert(item.id) }
        })
        addView(TextView(this@SearchAlertsActivity).apply {
            text = "${item.query} · ${intervalLabel(item.intervalMinutes)} · ${if (item.enabled) "ON" else "OFF"}" +
                if (item.lastNewMatches > 0) " · +${item.lastNewMatches} new" else ""
            textSize = 9f
            setTextColor(if (item.enabled) palette.accent else palette.muted)
            maxLines = 3
            setOnClickListener { loadAlert(item.id) }
        })

        val row = LinearLayout(this@SearchAlertsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this@SearchAlertsActivity).apply {
            text = if (item.enabled) "PAUSE" else "ENABLE"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener {
                store.setEnabled(item.id, !item.enabled)
                if (!item.enabled) requestNotificationPermissionIfNeeded()
                StarIntelBackgroundSync.ensureScheduled(this@SearchAlertsActivity)
                renderAlerts()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this@SearchAlertsActivity).apply {
            text = "OPEN"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener {
                startActivity(Intent(this@SearchAlertsActivity, SearchActivity::class.java).putExtra(SearchActivity.EXTRA_QUERY, item.query))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this@SearchAlertsActivity).apply {
            text = "DELETE"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener {
                store.delete(item.id)
                if (editingId == item.id) editingId = null
                renderAlerts()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(row, matchWrap(top = 2))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun showStatus(text: String, warning: Boolean = false, accent: Boolean = false) {
        status.text = text
        status.visibility = if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        status.setTextColor(when {
            warning -> palette.warning
            accent -> palette.accent
            else -> palette.muted
        })
    }

    private fun intervalLabel(minutes: Int): String = when (minutes) {
        15 -> "EVERY 15 MIN"
        30 -> "EVERY 30 MIN"
        60 -> "EVERY 1 HOUR"
        360 -> "EVERY 6 HOURS"
        1_440 -> "EVERY 24 HOURS"
        else -> "EVERY $minutes MIN"
    }

    private fun nextInterval(current: Int): Int {
        val intervals = SavedSearchStore.SUPPORTED_INTERVALS
        val index = intervals.indexOf(current).takeIf { it >= 0 } ?: 0
        return intervals[(index + 1) % intervals.size]
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUERY = "query"
        const val EXTRA_SAVED_SEARCH_ID = "saved_search_id"
        private const val REQUEST_NOTIFICATIONS = 7002
    }
}
