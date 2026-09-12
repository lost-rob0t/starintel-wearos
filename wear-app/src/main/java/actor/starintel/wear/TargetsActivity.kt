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
import actor.starintel.wear.data.StarIntelApiClient
import actor.starintel.wear.data.StarIntelSearchClient
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelInput
import actor.starintel.wear.ui.applyStarIntelTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

class TargetsActivity : StarIntelActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var actor: EditText
    private lateinit var target: EditText
    private lateinit var dataset: EditText
    private lateinit var options: EditText
    private lateinit var createStatus: TextView
    private lateinit var listStatus: TextView
    private lateinit var targetList: LinearLayout
    private lateinit var submit: Button
    private lateinit var refresh: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "TARGETS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Browse active target documents or dispatch a new one"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        root.addView(sectionTitle("RECENT TARGETS"), matchWrap(top = 9))
        refresh = Button(this).apply {
            text = "REFRESH TARGETS"
            applyStarIntelTheme(palette)
            setOnClickListener { refreshTargets() }
        }
        root.addView(refresh, matchWrap(top = 3))

        listStatus = TextView(this).apply {
            text = "Loading target documents…"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }
        root.addView(listStatus, matchWrap(top = 3))

        targetList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(targetList, matchWrap(top = 4))

        root.addView(sectionTitle("CREATE TARGET"), matchWrap(top = 11))

        actor = field("Actor", "user-hunt", prefs.getString(KEY_ACTOR, "").orEmpty())
        target = field("Target", "username, host, URL…", "")
        dataset = field("Dataset", "investigation dataset", prefs.getString(KEY_DATASET, "").orEmpty())
        options = field("Options JSON array", "[]", "[]", multiline = true)

        root.addView(label("ACTOR"), matchWrap(top = 5))
        root.addView(actor, matchWrap(top = 1))
        root.addView(label("TARGET"), matchWrap(top = 5))
        root.addView(target, matchWrap(top = 1))
        root.addView(label("DATASET"), matchWrap(top = 5))
        root.addView(dataset, matchWrap(top = 1))
        root.addView(label("OPTIONS"), matchWrap(top = 5))
        root.addView(options, matchWrap(top = 1))

        submit = Button(this).apply {
            text = "CREATE TARGET"
            applyStarIntelTheme(palette)
            setOnClickListener { createTarget() }
        }
        root.addView(submit, matchWrap(top = 7))

        createStatus = TextView(this).apply {
            text = "Uses the canonical v1 target API"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }
        root.addView(createStatus, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })

        refreshTargets()
    }

    override fun onResume() {
        super.onResume()
        if (::targetList.isInitialized) refreshTargets()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshTargets() {
        if (!::refresh.isInitialized) return
        refresh.isEnabled = false
        listStatus.text = "Loading target documents…"
        listStatus.setTextColor(palette.muted)
        targetList.removeAllViews()

        scope.launch {
            val client = StarIntelSearchClient.get(this@TargetsActivity)
            val targetDocs = client.search("dtype:target", limit = TARGET_QUERY_LIMIT)
            val investigationDocs = client.search("dtype:investigation-target", limit = TARGET_QUERY_LIMIT)
            refresh.isEnabled = true

            val hits = (targetDocs.hits + investigationDocs.hits)
                .distinctBy(SearchHit::id)
                .take(MAX_TARGET_CARDS)
            val errors = listOfNotNull(targetDocs.error, investigationDocs.error).distinct()

            when {
                hits.isNotEmpty() -> {
                    listStatus.text = "${hits.size} target document${if (hits.size == 1) "" else "s"} · tap to inspect"
                    listStatus.setTextColor(palette.accent)
                    hits.forEach { hit -> targetList.addView(targetCard(hit), matchWrap(top = 4)) }
                }
                errors.isNotEmpty() -> {
                    listStatus.text = errors.first()
                    listStatus.setTextColor(palette.warning)
                }
                else -> {
                    listStatus.text = "No target documents yet"
                    listStatus.setTextColor(palette.muted)
                }
            }
        }
    }

    private fun targetCard(hit: SearchHit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        setBackgroundColor(palette.surface)
        isClickable = true
        isFocusable = true
        contentDescription = "Open target ${hit.title}"
        setOnClickListener {
            startActivity(
                Intent(this@TargetsActivity, DocumentViewerActivity::class.java)
                    .putExtra(DocumentViewerActivity.EXTRA_DOCUMENT_ID, hit.id),
            )
        }
        addView(TextView(this@TargetsActivity).apply {
            text = hit.title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.text)
            maxLines = 2
        })
        addView(TextView(this@TargetsActivity).apply {
            text = buildString {
                if (hit.secondary.isNotBlank()) append(hit.secondary).append(" · ")
                append(hit.id)
            }
            textSize = 9f
            setTextColor(palette.muted)
            maxLines = 2
        })
    }

    private fun createTarget() {
        val actorValue = actor.text.toString().trim()
        val targetValue = target.text.toString().trim()
        val datasetValue = dataset.text.toString().trim()
        val optionsValue = runCatching { JSONArray(options.text.toString().trim().ifBlank { "[]" }) }
            .getOrElse {
                createStatus.text = "Options must be a JSON array"
                createStatus.setTextColor(palette.warning)
                return
            }

        submit.isEnabled = false
        createStatus.text = "Dispatching…"
        createStatus.setTextColor(palette.muted)
        scope.launch {
            val result = StarIntelApiClient.get(this@TargetsActivity).createTarget(
                actor = actorValue,
                target = targetValue,
                dataset = datasetValue,
                options = optionsValue,
            )
            submit.isEnabled = true
            if (!result.accepted) {
                createStatus.text = result.error ?: "Target rejected"
                createStatus.setTextColor(palette.warning)
                return@launch
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ACTOR, actorValue)
                .putString(KEY_DATASET, datasetValue)
                .apply()
            createStatus.text = buildString {
                append(if (result.duplicate) "DUPLICATE · accepted" else "ACCEPTED")
                result.targetId?.let { append("\n").append(it) }
            }
            createStatus.setTextColor(palette.accent)
            target.text.clear()
            refreshTargets()
        }
    }

    private fun field(label: String, hintText: String, initial: String, multiline: Boolean = false): EditText =
        EditText(this).apply {
            hint = hintText
            setText(initial)
            textSize = 12f
            if (!multiline) setSingleLine(true)
            minLines = if (multiline) 2 else 1
            maxLines = if (multiline) 4 else 1
            contentDescription = label
            inputType = InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            applyStarIntelInput(palette)
        }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.accent)
        gravity = Gravity.CENTER
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 9f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.accent)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREFS = "starintel_wear_targets"
        private const val KEY_ACTOR = "last_actor"
        private const val KEY_DATASET = "last_dataset"
        private const val TARGET_QUERY_LIMIT = 12
        private const val MAX_TARGET_CARDS = 20
    }
}
