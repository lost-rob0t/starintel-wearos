package actor.starintel.wear

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.wear.data.StarIntelApiClient
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class DocumentViewerActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var fields: LinearLayout
    private lateinit var raw: TextView
    private lateinit var rawToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val documentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "DOCUMENT"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = documentId.ifBlank { "No document id" }
            textSize = 9f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 3))

        status = TextView(this).apply {
            text = "Loading…"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 6))

        fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(fields, matchWrap(top = 6))

        rawToggle = Button(this).apply {
            text = "SHOW RAW JSON"
            applyStarIntelTheme(palette)
            visibility = View.GONE
        }
        raw = TextView(this).apply {
            textSize = 9f
            setTextColor(palette.muted)
            visibility = View.GONE
            setTextIsSelectable(true)
        }
        rawToggle.setOnClickListener {
            val showing = raw.visibility == View.VISIBLE
            raw.visibility = if (showing) View.GONE else View.VISIBLE
            rawToggle.text = if (showing) "SHOW RAW JSON" else "HIDE RAW JSON"
        }
        root.addView(rawToggle, matchWrap(top = 8))
        root.addView(raw, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        if (documentId.isBlank()) {
            status.text = "Document id required"
            status.setTextColor(palette.warning)
        } else {
            load(documentId)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load(id: String) {
        scope.launch {
            val result = StarIntelApiClient.get(this@DocumentViewerActivity).document(id)
            if (result.error != null || result.json == null) {
                status.text = result.error ?: "Document unavailable"
                status.setTextColor(palette.warning)
                return@launch
            }
            status.text = "Loaded"
            status.setTextColor(palette.accent)
            renderDocument(result.json)
        }
    }

    private fun renderDocument(document: JSONObject) {
        fields.removeAllViews()
        val priority = listOf("_id", "dtype", "dataset", "schema_version", "version", "date_added", "date_updated")
        val rendered = mutableSetOf<String>()
        priority.forEach { key ->
            if (document.has(key) && !document.isNull(key)) {
                addField(key, document.get(key))
                rendered.add(key)
            }
        }

        document.optJSONObject("data")?.let { data ->
            addSection("DATA")
            val keys = data.keys().asSequence().toList().sorted()
            keys.forEach { key -> addField(key, data.get(key)) }
            rendered.add("data")
        }

        val remaining = document.keys().asSequence().toList().sorted().filterNot { it in rendered }
        if (remaining.isNotEmpty()) {
            addSection("OTHER FIELDS")
            remaining.forEach { key -> addField(key, document.get(key)) }
        }

        raw.text = document.toString(2).take(MAX_RAW_CHARS)
        rawToggle.visibility = View.VISIBLE
    }

    private fun addSection(title: String) {
        fields.addView(TextView(this).apply {
            text = title
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
        }, matchWrap(top = 7))
    }

    private fun addField(key: String, value: Any) {
        fields.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            setBackgroundColor(palette.surface)
            addView(TextView(this@DocumentViewerActivity).apply {
                text = key
                textSize = 9f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(palette.accent)
            })
            addView(TextView(this@DocumentViewerActivity).apply {
                text = displayValue(value)
                textSize = 10f
                setTextColor(palette.text)
                setTextIsSelectable(true)
            })
        }, matchWrap(top = 3))
    }

    private fun displayValue(value: Any): String = when (value) {
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }.take(MAX_FIELD_CHARS)

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DOCUMENT_ID = "document_id"
        private const val MAX_FIELD_CHARS = 2_000
        private const val MAX_RAW_CHARS = 16_384
    }
}
