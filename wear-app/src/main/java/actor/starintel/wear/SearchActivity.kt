package actor.starintel.wear

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.SearchHit
import actor.starintel.wear.data.StarIntelSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SearchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: StarIntelSearchClient
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var search: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        client = StarIntelSearchClient.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(26))
            setBackgroundColor(BACKGROUND)
        }

        root.addView(TextView(this).apply {
            text = "SEARCH"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CYAN)
            gravity = Gravity.CENTER
        }, matchWrap())

        query = EditText(this).apply {
            setSingleLine(true)
            hint = "name, handle, URL, term…"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            setText(intent.getStringExtra(EXTRA_QUERY).orEmpty())
        }
        root.addView(query, matchWrap(top = 7))

        search = Button(this).apply {
            text = "RUN SEARCH"
            setOnClickListener { runSearch() }
        }
        root.addView(search, matchWrap(top = 4))

        status = TextView(this).apply {
            text = "Uses the server /api/v1/search surface"
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 4))

        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(results, matchWrap(top = 6))

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
            status.text = "Enter a search query"
            status.setTextColor(WARNING)
            return
        }

        search.isEnabled = false
        status.text = "Searching…"
        status.setTextColor(MUTED)
        results.removeAllViews()

        scope.launch {
            val response = client.search(q, limit = 20)
            search.isEnabled = true
            if (response.error != null) {
                status.text = response.error
                status.setTextColor(WARNING)
                return@launch
            }

            val total = response.totalRows?.let { "${response.hits.size} of $it" } ?: response.hits.size.toString()
            status.text = "$total result${if (response.hits.size == 1) "" else "s"}"
            status.setTextColor(CYAN)
            if (response.hits.isEmpty()) {
                results.addView(resultText("NO MATCHES", "Try another term"), matchWrap(top = 6))
            } else {
                response.hits.forEachIndexed { index, hit ->
                    results.addView(resultText("${index + 1}. ${hit.title}", subtitle(hit)), matchWrap(top = 5))
                }
            }
        }
    }

    private fun subtitle(hit: SearchHit): String = buildString {
        if (hit.secondary.isNotBlank()) append(hit.secondary)
        if (isNotEmpty()) append(" · ")
        append(hit.id)
    }

    private fun resultText(primary: String, secondary: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(Color.rgb(10, 15, 19))
        addView(TextView(this@SearchActivity).apply {
            text = primary
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        addView(TextView(this@SearchActivity).apply {
            text = secondary
            textSize = 9f
            setTextColor(MUTED)
        })
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_QUERY = "query"
        private val BACKGROUND = Color.rgb(5, 7, 10)
        private val CYAN = Color.rgb(0, 229, 255)
        private val MUTED = Color.rgb(176, 187, 199)
        private val WARNING = Color.rgb(255, 132, 132)
    }
}
