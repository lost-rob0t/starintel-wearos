package actor.starintel.wear

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.update.UpdateActivity
import actor.starintel.wear.data.SavedSearchStore
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.ageLabel
import actor.starintel.wear.data.compactCount
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.StarIntelThemeStore
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: StarIntelRepository
    private lateinit var status: TextView
    private lateinit var documents: TextView
    private lateinit var targets: TextView
    private lateinit var types: TextView
    private lateinit var freshness: TextView
    private lateinit var monitors: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = StarIntelRepository.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(palette.background)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())

        status = metric("CONNECTING")
        documents = metric("—")
        targets = metric("—")
        types = TextView(this).apply {
            textSize = 11f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        freshness = TextView(this).apply {
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        monitors = TextView(this).apply {
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }

        root.addView(status, matchWrap(top = 8))
        root.addView(label("DOCUMENTS"), matchWrap(top = 10))
        root.addView(documents, matchWrap())
        root.addView(label("TARGETS"), matchWrap(top = 8))
        root.addView(targets, matchWrap())
        root.addView(types, matchWrap(top = 6))
        root.addView(freshness, matchWrap(top = 4))
        root.addView(monitors, matchWrap(top = 3))

        root.addView(appButton("SEARCH") { SearchActivity::class.java }, matchWrap(top = 9))
        root.addView(appButton("EXPLORER") { ExplorerActivity::class.java }, matchWrap(top = 3))
        root.addView(appButton("TARGETS") { TargetsActivity::class.java }, matchWrap(top = 3))
        root.addView(appButton("ACTIVITY GRAPH") { GraphActivity::class.java }, matchWrap(top = 3))
        root.addView(appButton("UPDATES") { UpdateActivity::class.java }, matchWrap(top = 3))

        refresh = Button(this).apply {
            text = "REFRESH NOW"
            applyStarIntelTheme(palette)
            setOnClickListener { load(force = true) }
        }
        root.addView(refresh, matchWrap(top = 3))

        root.addView(Button(this).apply {
            text = "THEME · ${palette.id.name}"
            applyStarIntelTheme(palette)
            setOnClickListener {
                StarIntelThemeStore(this@MainActivity).cycle()
                recreate()
            }
        }, matchWrap(top = 3))

        root.addView(Button(this).apply {
            text = "SETTINGS"
            applyStarIntelTheme(palette)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigActivity::class.java)) }
        }, matchWrap(top = 3))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::monitors.isInitialized) renderMonitorState()
        load(force = false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(force: Boolean) {
        refresh.isEnabled = false
        status.text = "SYNCING"
        status.setTextColor(palette.muted)
        scope.launch {
            val snapshot = repository.snapshot(forceRefresh = force)
            render(snapshot)
            refresh.isEnabled = true
        }
    }

    private fun render(snapshot: StarIntelSnapshot) {
        if (!snapshot.configured) {
            status.text = "SETUP REQUIRED"
            status.setTextColor(palette.warning)
            documents.text = "—"
            targets.text = "—"
            types.text = "Open SETTINGS to pair this watch with StarIntel"
            freshness.text = snapshot.error.orEmpty()
            renderMonitorState()
            return
        }

        status.text = when {
            snapshot.reachable && !snapshot.stale -> "ONLINE · v${snapshot.version}"
            snapshot.reachable -> "STALE · v${snapshot.version}"
            else -> "OFFLINE · CACHED"
        }
        status.setTextColor(if (snapshot.reachable && !snapshot.stale) palette.accent else palette.warning)
        documents.text = snapshot.documentsTotal.compactCount()
        targets.text = snapshot.targetsTotal.compactCount()
        types.text = snapshot.documentsByType.entries
            .sortedByDescending { it.value }
            .take(4)
            .joinToString("  ·  ") { "${it.key} ${it.value.compactCount()}" }
            .ifBlank { "No document type breakdown" }
        freshness.text = buildString {
            append(snapshot.ageLabel())
            if (snapshot.error != null) append(" · ${snapshot.error}")
        }
        renderMonitorState()
    }

    private fun renderMonitorState() {
        val store = SavedSearchStore(this)
        val active = store.activeCount()
        val recentNew = store.latestNewMatchCount()
        monitors.text = buildString {
            append(active).append(" search monitor")
            if (active != 1) append('s')
            if (recentNew > 0) append(" · +").append(recentNew).append(" new")
        }
        monitors.setTextColor(if (recentNew > 0) palette.accent else palette.muted)
    }

    private fun appButton(text: String, destination: () -> Class<*>): Button = Button(this).apply {
        this.text = text
        applyStarIntelTheme(palette)
        setOnClickListener { startActivity(Intent(this@MainActivity, destination())) }
    }

    private fun metric(initial: String) = TextView(this).apply {
        text = initial
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.text)
        gravity = Gravity.CENTER
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 9f
        setTextColor(palette.muted)
        gravity = Gravity.CENTER
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
