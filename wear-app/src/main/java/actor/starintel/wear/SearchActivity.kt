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
import actor.starintel.wear.data.SearchHit
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

class SearchActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: StarIntelSearchClient
    private lateinit var savedStore: SavedSearchStore
    private lateinit var query: EditText
    private lateinit var label: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var monitors: LinearLayout
    private lateinit var search: Button
    private lateinit var interval: Button
    private var intervalMinutes = 60
    private var editingSavedId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = StarIntelSearchClient.get(this)
        savedStore = SavedSearchStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(palette.background)
        }

        root.addView(TextView(this).apply {
            text = "SEARCH"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())

        query = EditText(this).apply {
            setSingleLine(true)
            hint = "name, handle, URL, FTS query…"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            setText(intent.getStringExtra(EXTRA_QUERY).orEmpty())
            applyStarIntelInput(palette)
        }
        root.addView(query, matchWrap(top = 6))

        search = Button(this).apply {
            text = "RUN SEARCH"
            applyStarIntelTheme(palette)
            setOnClickListener { runSearch() }
        }
        root.addView(search, matchWrap(top = 3))

        status = TextView(this).apply {
            text = "Manual search + background search monitors"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, matchWrap(top = 5))

        root.addView(TextView(this).apply {
            text = "SAVE AS MONITOR"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap(top = 10))

        label = EditText(this).apply {
            setSingleLine(true)
            hint = "monitor name (optional)"
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

        root.addView(Button(this).apply {
            text = "SAVE + ENABLE MONITOR"
            applyStarIntelTheme(palette)
            setOnClickListener { saveMonitor() }
        }, matchWrap(top = 3))

        monitors = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(monitors, matchWrap(top = 8))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        intent.getStringExtra(EXTRA_SAVED_SEARCH_ID)?.let { loadSavedMonitor(it) }
        renderMonitors()
        if (query.text.isNotBlank()) runSearch()
    }

    override fun onResume() {
        super.onResume()
        if (::monitors.isInitialized) renderMonitors()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runSearch() {
        val q = query.text.toString().trim()
        if (q.isBlank()) {
            status.text = "Enter a search query"
            status.setTextColor(palette.warning)
            return
        }

        search.isEnabled = false
        status.text = "Searching…"
        status.setTextColor(palette.muted)
        results.removeAllViews()

        scope.launch {
            val response = client.search(q, limit = 50)
            search.isEnabled = true
            if (response.error != null) {
                status.text = response.error
                status.setTextColor(palette.warning)
                return@launch
            }

            val total = response.totalRows?.let { "${response.hits.size} of $it" } ?: response.hits.size.toString()
            status.text = "$total result${if (response.hits.size == 1) "" else "s"} · tap to open"
            status.setTextColor(palette.accent)
            if (response.hits.isEmpty()) {
                results.addView(resultCard(null, "NO MATCHES", "Try another term"), matchWrap(top = 5))
            } else {
                response.hits.forEachIndexed { index, hit ->
                    results.addView(resultCard(hit, "${index + 1}. ${hit.title}", subtitle(hit)), matchWrap(top = 4))
                }
            }
        }
    }

    private fun saveMonitor() {
        val q = query.text.toString().trim()
        if (q.isBlank()) {
            status.text = "Enter a query before saving a monitor"
            status.setTextColor(palette.warning)
            return
        }
        val saved = runCatching {
            savedStore.upsert(
                id = editingSavedId,
                label = label.text.toString(),
                query = q,
                intervalMinutes = intervalMinutes,
                enabled = true,
            )
        }.getOrElse {
            status.text = it.message ?: "Could not save monitor"
            status.setTextColor(palette.warning)
            return
        }
        editingSavedId = saved.id
        StarIntelBackgroundSync.ensureScheduled(this)
        requestNotificationPermissionIfNeeded()
        status.text = "Monitor saved · ${intervalLabel(saved.intervalMinutes)}"
        status.setTextColor(palette.accent)
        renderMonitors()
    }

    private fun loadSavedMonitor(id: String) {
        val saved = savedStore.get(id) ?: return
        editingSavedId = saved.id
        query.setText(saved.query)
        label.setText(saved.label)
        intervalMinutes = saved.intervalMinutes
        interval.text = intervalLabel(intervalMinutes)
    }

    private fun renderMonitors() {
        monitors.removeAllViews()
        monitors.addView(TextView(this).apply {
            text = "SAVED MONITORS · ${savedStore.activeCount()} ACTIVE"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())

        val saved = savedStore.list()
        if (saved.isEmpty()) {
            monitors.addView(TextView(this).apply {
                text = "No saved search monitors"
                textSize = 10f
                setTextColor(palette.muted)
                gravity = Gravity.CENTER
            }, matchWrap(top = 4))
            return
        }

        saved.forEach { item -> monitors.addView(monitorCard(item), matchWrap(top = 4)) }
    }

    private fun monitorCard(item: SavedSearch): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(palette.surface)

        addView(TextView(this@SearchActivity).apply {
            text = item.label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            setOnClickListener {
                loadSavedMonitor(item.id)
                runSearch()
            }
        })
        addView(TextView(this@SearchActivity).apply {
            text = "${item.query} · ${intervalLabel(item.intervalMinutes)} · ${if (item.enabled) "ON" else "OFF"}" +
                if (item.lastNewMatches > 0) " · +${item.lastNewMatches} new" else ""
            textSize = 9f
            setTextColor(if (item.enabled) palette.accent else palette.muted)
            setOnClickListener {
                loadSavedMonitor(item.id)
                runSearch()
            }
        })

        val actions = LinearLayout(this@SearchActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(Button(this@SearchActivity).apply {
            text = if (item.enabled) "PAUSE" else "ENABLE"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener {
                savedStore.setEnabled(item.id, !item.enabled)
                if (!item.enabled) requestNotificationPermissionIfNeeded()
                StarIntelBackgroundSync.ensureScheduled(this@SearchActivity)
                renderMonitors()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(Button(this@SearchActivity).apply {
            text = "DELETE"
            textSize = 9f
            applyStarIntelTheme(palette)
            setOnClickListener {
                savedStore.delete(item.id)
                if (editingSavedId == item.id) editingSavedId = null
                renderMonitors()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(actions, matchWrap(top = 2))
    }

    private fun resultCard(hit: SearchHit?, primary: String, secondary: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(palette.surface)
        isClickable = hit != null
        isFocusable = hit != null
        if (hit != null) {
            setOnClickListener {
                startActivity(
                    Intent(this@SearchActivity, DocumentViewerActivity::class.java)
                        .putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, hit.id),
                )
            }
        }
        addView(TextView(this@SearchActivity).apply {
            text = primary
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        })
        addView(TextView(this@SearchActivity).apply {
            text = secondary
            textSize = 9f
            setTextColor(palette.muted)
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun subtitle(hit: SearchHit): String = buildString {
        if (hit.secondary.isNotBlank()) append(hit.secondary)
        if (isNotEmpty()) append(" · ")
        append(hit.id)
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
        private const val REQUEST_NOTIFICATIONS = 7001
    }
}
