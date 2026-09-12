package actor.starintel.wear

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
import actor.starintel.wear.data.StarIntelApiClient
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
    private lateinit var status: TextView
    private lateinit var submit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
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
            text = "Dispatch a StarIntel actor target"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        actor = field("Actor", "user-hunt", prefs.getString(KEY_ACTOR, "").orEmpty())
        target = field("Target", "username, host, URL…", "")
        dataset = field("Dataset", "investigation dataset", prefs.getString(KEY_DATASET, "").orEmpty())
        options = field("Options JSON array", "[]", "[]", multiline = true)

        root.addView(label("ACTOR"), matchWrap(top = 8))
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

        status = TextView(this).apply {
            text = "Uses the canonical v1 target API when available"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }
        root.addView(status, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createTarget() {
        val actorValue = actor.text.toString().trim()
        val targetValue = target.text.toString().trim()
        val datasetValue = dataset.text.toString().trim()
        val optionsValue = runCatching { JSONArray(options.text.toString().trim().ifBlank { "[]" }) }
            .getOrElse {
                status.text = "Options must be a JSON array"
                status.setTextColor(palette.warning)
                return
            }

        submit.isEnabled = false
        status.text = "Dispatching…"
        status.setTextColor(palette.muted)
        scope.launch {
            val result = StarIntelApiClient.get(this@TargetsActivity).createTarget(
                actor = actorValue,
                target = targetValue,
                dataset = datasetValue,
                options = optionsValue,
            )
            submit.isEnabled = true
            if (!result.accepted) {
                status.text = result.error ?: "Target rejected"
                status.setTextColor(palette.warning)
                return@launch
            }

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ACTOR, actorValue)
                .putString(KEY_DATASET, datasetValue)
                .apply()
            status.text = buildString {
                append(if (result.duplicate) "DUPLICATE · accepted" else "ACCEPTED")
                result.targetId?.let { append("\n").append(it) }
            }
            status.setTextColor(palette.accent)
            target.text.clear()
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
    }
}
