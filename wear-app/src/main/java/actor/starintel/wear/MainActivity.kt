package actor.starintel.wear

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.update.UpdateActivity
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSnapshot
import actor.starintel.wear.data.ageLabel
import actor.starintel.wear.data.compactCount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: StarIntelRepository
    private lateinit var status: TextView
    private lateinit var documents: TextView
    private lateinit var targets: TextView
    private lateinit var types: TextView
    private lateinit var freshness: TextView
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        repository = StarIntelRepository.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(28))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "STARINTEL"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
            gravity = Gravity.CENTER
        }, matchWrap())

        status = metric("CONNECTING")
        documents = metric("—")
        targets = metric("—")
        types = TextView(this).apply {
            textSize = 11f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        freshness = TextView(this).apply {
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }

        root.addView(status, matchWrap(top = 8))
        root.addView(label("DOCUMENTS"), matchWrap(top = 10))
        root.addView(documents, matchWrap())
        root.addView(label("TARGETS"), matchWrap(top = 8))
        root.addView(targets, matchWrap())
        root.addView(types, matchWrap(top = 6))
        root.addView(freshness, matchWrap(top = 4))

        val graph = Button(this).apply {
            text = "ACTIVITY GRAPH"
            setOnClickListener { startActivity(Intent(this@MainActivity, GraphActivity::class.java)) }
        }
        val search = Button(this).apply {
            text = "SEARCH"
            setOnClickListener { startActivity(Intent(this@MainActivity, SearchActivity::class.java)) }
        }
        refresh = Button(this).apply {
            text = "REFRESH"
            setOnClickListener { load(force = true) }
        }
        val updates = Button(this).apply {
            text = "UPDATES"
            setOnClickListener { startActivity(Intent(this@MainActivity, UpdateActivity::class.java)) }
        }
        val settings = Button(this).apply {
            text = "SETTINGS"
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigActivity::class.java)) }
        }

        root.addView(graph, matchWrap(top = 10))
        root.addView(search, matchWrap(top = 3))
        root.addView(refresh, matchWrap(top = 3))
        root.addView(updates, matchWrap(top = 3))
        root.addView(settings, matchWrap(top = 3))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        load(force = false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(force: Boolean) {
        refresh.isEnabled = false
        status.text = "SYNCING"
        status.setTextColor(MUTED)
        scope.launch {
            val snapshot = repository.snapshot(forceRefresh = force)
            render(snapshot)
            refresh.isEnabled = true
        }
    }

    private fun render(snapshot: StarIntelSnapshot) {
        if (!snapshot.configured) {
            status.text = "SETUP REQUIRED"
            status.setTextColor(WARNING)
            documents.text = "—"
            targets.text = "—"
            types.text = "Open SETTINGS to pair this watch with StarIntel"
            freshness.text = snapshot.error.orEmpty()
            return
        }

        status.text = when {
            snapshot.reachable && !snapshot.stale -> "ONLINE · v${snapshot.version}"
            snapshot.reachable -> "STALE · v${snapshot.version}"
            else -> "OFFLINE · CACHED"
        }
        status.setTextColor(if (snapshot.reachable && !snapshot.stale) CYAN else WARNING)
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
    }

    private fun metric(initial: String) = TextView(this).apply {
        text = initial
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 9f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
