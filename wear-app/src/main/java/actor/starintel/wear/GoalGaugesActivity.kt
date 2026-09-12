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
import actor.starintel.wear.data.GoalGaugeConfig
import actor.starintel.wear.data.GoalGaugeStore
import actor.starintel.wear.ui.StarIntelActivity
import actor.starintel.wear.ui.applyStarIntelInput
import actor.starintel.wear.ui.applyStarIntelTheme

class GoalGaugesActivity : StarIntelActivity() {
    private lateinit var store: GoalGaugeStore
    private lateinit var documents: EditText
    private lateinit var targets: EditText
    private lateinit var dtype: EditText
    private lateinit var dtypeGoal: EditText
    private lateinit var rate: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GoalGaugeStore.get(this)
        val current = store.load()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
            setBackgroundColor(palette.background)
        }
        root.addView(TextView(this).apply {
            text = "GAUGES"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
            gravity = Gravity.CENTER
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "Set progress goals for ranged complications"
            textSize = 10f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }, matchWrap(top = 2))

        documents = numericField(current.documentsGoal)
        targets = numericField(current.targetsGoal)
        dtype = textField(current.documentType, "document dtype, e.g. person")
        dtypeGoal = numericField(current.documentTypeGoal)
        rate = numericField(current.hourlyNetGrowthGoal)

        addField(root, "TOTAL DOCUMENT GOAL", documents)
        addField(root, "TARGET GOAL", targets)
        addField(root, "DOCUMENT TYPE", dtype)
        addField(root, "TYPE GOAL", dtypeGoal)
        addField(root, "DOCUMENTS / HOUR GOAL", rate)

        root.addView(Button(this).apply {
            text = "SAVE GAUGES"
            applyStarIntelTheme(palette)
            setOnClickListener { save() }
        }, matchWrap(top = 8))

        status = TextView(this).apply {
            text = "Battery and other ranged system complications drive bars automatically. StarIntel goal providers use these values."
            textSize = 9f
            setTextColor(palette.muted)
            gravity = Gravity.CENTER
        }
        root.addView(status, matchWrap(top = 5))

        setContentView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    private fun save() {
        val config = GoalGaugeConfig(
            documentsGoal = parseGoal(documents, "Document goal") ?: return,
            targetsGoal = parseGoal(targets, "Target goal") ?: return,
            documentType = dtype.text.toString().trim(),
            documentTypeGoal = parseGoal(dtypeGoal, "Type goal") ?: return,
            hourlyNetGrowthGoal = parseGoal(rate, "Hourly rate goal") ?: return,
        )
        if (config.documentTypeGoal > 0L && config.documentType.isBlank()) {
            warn("Document type is required when type goal is set")
            return
        }
        store.save(config)
        status.text = "SAVED · reselect/refresh a complication if the face does not update immediately"
        status.setTextColor(palette.accent)
    }

    private fun parseGoal(field: EditText, name: String): Long? {
        val raw = field.text.toString().trim()
        if (raw.isBlank()) return 0L
        val value = raw.toLongOrNull()
        if (value == null || value < 0L || value > MAX_GOAL) {
            warn("$name must be 0–$MAX_GOAL")
            return null
        }
        return value
    }

    private fun warn(message: String) {
        status.text = message
        status.setTextColor(palette.warning)
    }

    private fun addField(root: LinearLayout, label: String, field: EditText) {
        root.addView(TextView(this).apply {
            text = label
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.accent)
        }, matchWrap(top = 6))
        root.addView(field, matchWrap(top = 1))
    }

    private fun numericField(value: Long): EditText = EditText(this).apply {
        setSingleLine(true)
        hint = "0 = disabled"
        textSize = 12f
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.takeIf { it > 0L }?.toString().orEmpty())
        applyStarIntelInput(palette)
    }

    private fun textField(value: String, hintValue: String): EditText = EditText(this).apply {
        setSingleLine(true)
        hint = hintValue
        textSize = 12f
        inputType = InputType.TYPE_CLASS_TEXT
        setText(value)
        applyStarIntelInput(palette)
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_GOAL = 9_000_000_000_000L
    }
}
