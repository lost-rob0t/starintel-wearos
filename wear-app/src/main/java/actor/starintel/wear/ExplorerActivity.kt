package actor.starintel.wear

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.SearchHit
import actor.starintel.wear.data.StarIntelRepository
import actor.starintel.wear.data.StarIntelSearchClient
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelInput
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ExplorerActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var browseTypes: LinearLayout
    private lateinit var run: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "EXPLORER"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Browse the StarIntel corpus"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        query = EditText(this).apply {
            setSingleLine(true)
            hint = "FTS query, name, URL, dtype…"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            applyStarIntelInput(palette)
        }
        root.addView(query, matchWrap(top = 7))

        run = Button(this).apply {
            text = "EXPLORE"
            applyStarIntelTheme(palette)
            setOnClickListener { search(query.text.toString()) }
        }
        root.addView(run, matchWrap(top = 3))
        root.addView(Button(this).apply {
            text = "SURPRISE ME · RANDOM DOCS"
            applyStarIntelTheme(palette)
            setOnClickListener { randomDocuments() }
        }, matchWrap(top = 3))

        status = TextView(this).apply {
            text = "Pick a document type or enter a query"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        browseTypes = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(browseTypes, matchWrap(top = 4))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        renderTypeShortcuts()
    }

    override fun onResume() {
        super.onResume()
        renderTypeShortcuts()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun renderTypeShortcuts() {
        if (!::browseTypes.isInitialized) return
        browseTypes.removeAllViews()
        val snapshot = StarIntelRepository.get(this).cachedSnapshot()
        val types = snapshot.documentsByType.entries
            .filter { it.key.isNotBlank() && it.value > 0 }
            .sortedByDescending { it.value }
            .take(6)
        if (types.isEmpty()) return

        browseTypes.addView(TextView(this).apply {
            text = "TOP DOCUMENT TYPES"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
        }, matchWrap(top = 3))

        types.forEach { (dtype, count) ->
            browseTypes.addView(Button(this).apply {
                text = "$dtype · $count"
                textSize = 10f
                applyStarIntelTheme(palette)
                setOnClickListener {
                    val expression = "dtype:$dtype"
                    query.setText(expression)
                    search(expression)
                }
            }, matchWrap(top = 2))
        }
    }

    private fun search(rawQuery: String) {
        val q = rawQuery.trim()
        if (q.isBlank()) {
            status.text = "Enter a query or pick a type"
            status.setTextColor(palette.warning)
            return
        }

        run.isEnabled = false
        status.text = "Exploring…"
        status.setTextColor(palette.muted)
        results.removeAllViews()
        scope.launch {
            val response = StarIntelSearchClient.get(this@ExplorerActivity).search(q, limit = 20)
            run.isEnabled = true
            if (response.error != null) {
                status.text = response.error
                status.setTextColor(palette.warning)
                return@launch
            }
            status.text = response.totalRows?.let { "${response.hits.size} of $it documents" }
                ?: "${response.hits.size} documents"
            status.setTextColor(palette.accent)
            if (response.hits.isEmpty()) {
                results.addView(TextView(this@ExplorerActivity).apply {
                    text = "NO DOCUMENTS"
                    gravity = Gravity.CENTER
                    textSize = 11f
                    setTextColor(palette.muted)
                }, matchWrap(top = 6))
                return@launch
            }
            response.hits.forEach { hit -> results.addView(documentCard(hit), matchWrap(top = 4)) }
        }
    }

    private fun randomDocuments() {
        run.isEnabled = false
        status.text = "Picking documents…"
        status.setTextColor(palette.muted)
        results.removeAllViews()
        scope.launch {
            val response = StarIntelSearchClient.get(this@ExplorerActivity).search("*:*", limit = 50)
            run.isEnabled = true
            if (response.error != null) {
                status.text = "Random browse unavailable · ${response.error}"
                status.setTextColor(palette.warning)
                return@launch
            }
            val picked = response.hits.shuffled().take(8)
            status.text = "${picked.size} random documents · tap OPEN or GRAPH"
            status.setTextColor(palette.accent)
            picked.forEach { hit -> results.addView(documentCard(hit), matchWrap(top = 4)) }
        }
    }

    private fun documentCard(hit: SearchHit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(palette.surface)
        addView(TextView(this@ExplorerActivity).apply {
            text = hit.title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
        })
        addView(TextView(this@ExplorerActivity).apply {
            text = buildString {
                if (hit.secondary.isNotBlank()) append(hit.secondary).append(" · ")
                append(hit.id)
            }
            textSize = 9f
            setTextColor(palette.muted)
        })
        addView(LinearLayout(this@ExplorerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(this@ExplorerActivity).apply {
                text = "OPEN"
                textSize = 9f
                applyStarIntelTheme(palette)
                setOnClickListener {
                    startActivity(
                        Intent(this@ExplorerActivity, DocumentViewerActivity::class.java)
                            .putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, hit.id),
                    )
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@ExplorerActivity).apply {
                text = "GRAPH"
                textSize = 9f
                applyStarIntelTheme(palette)
                setOnClickListener {
                    startActivity(
                        Intent(this@ExplorerActivity, GraphActivity::class.java)
                            .putExtra(GraphActivity.EXTRA_DOCUMENT_ID, hit.id),
                    )
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        }, matchWrap(top = 4))
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
