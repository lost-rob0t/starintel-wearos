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
import actor.starintel.wear.data.StarIntelSearchClient
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
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var search: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = StarIntelSearchClient.get(this)

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

        root.addView(Button(this).apply {
            text = "SEARCH ALERTS"
            applyStarIntelTheme(palette)
            setOnClickListener {
                startActivity(
                    Intent(this@SearchActivity, SearchAlertsActivity::class.java)
                        .putExtra(SearchAlertsActivity.EXTRA_QUERY, query.text.toString().trim()),
                )
            }
        }, matchWrap(top = 3))

        status = TextView(this).apply {
            visibility = android.view.View.GONE
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        if (query.text.isNotBlank()) runSearch()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runSearch() {
        val q = query.text.toString().trim()
        if (q.isBlank()) {
            showStatus("Enter a search query", warning = true)
            return
        }

        search.isEnabled = false
        showStatus("Searching…")
        results.removeAllViews()

        scope.launch {
            val response = client.search(q, limit = WATCH_RESULT_LIMIT)
            search.isEnabled = true
            if (response.error != null) {
                showStatus(response.error, warning = true)
                return@launch
            }

            val total = response.totalRows?.let { "${response.hits.size} of $it" } ?: response.hits.size.toString()
            showStatus("$total result${if (response.hits.size == 1) "" else "s"} · tap to open", accent = true)
            if (response.hits.isEmpty()) {
                results.addView(resultCard(null, "NO MATCHES", "Try another term"), matchWrap(top = 5))
            } else {
                response.hits.forEachIndexed { index, hit ->
                    results.addView(resultCard(hit, "${index + 1}. ${hit.title}", subtitle(hit)), matchWrap(top = 4))
                }
            }
        }
    }

    private fun showStatus(text: String, warning: Boolean = false, accent: Boolean = false) {
        status.text = text
        status.visibility = if (text.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        status.setTextColor(
            when {
                warning -> palette.warning
                accent -> palette.accent
                else -> palette.muted
            },
        )
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
            maxLines = 2
        })
        if (secondary.isNotBlank()) {
            addView(TextView(this@SearchActivity).apply {
                text = secondary
                textSize = 9f
                setTextColor(palette.muted)
                maxLines = 2
            })
        }
    }

    private fun subtitle(hit: SearchHit): String = buildString {
        if (hit.secondary.isNotBlank()) append(hit.secondary)
        if (isNotEmpty()) append(" · ")
        append(hit.id)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUERY = "query"
        private const val WATCH_RESULT_LIMIT = 16
    }
}
