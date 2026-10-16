package actor.starintel.quasar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import actor.starintel.design.SiTokens

internal object QuasarDesign {
    // App-local roles: changing Quasar must not recolor Companion or Operator.
    private var dark = true
    fun configure(context: Context) {
        dark = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    val background get() = Color.parseColor(if (dark) "#101820" else "#F3F5F7")
    val panel get() = Color.parseColor(if (dark) "#19232D" else "#FFFFFF")
    val panelRaised get() = Color.parseColor(if (dark) "#23313E" else "#E7EDF2")
    val border get() = Color.parseColor(if (dark) "#526271" else "#738493")
    val cyan get() = Color.parseColor(if (dark) "#81CEEA" else "#005D7A")
    val pink get() = cyan
    val amber get() = Color.parseColor(if (dark) "#F6CC80" else "#765000")
    val lime get() = Color.parseColor(if (dark) "#9DD6AF" else "#22603B")
    val coral get() = Color.parseColor(if (dark) "#FFB4AA" else "#A62F28")
    val text get() = Color.parseColor(if (dark) "#E5EDF3" else "#18232C")
    val muted get() = Color.parseColor(if (dark) "#B2C1CD" else "#465C6C")

    /** One inset owner for all Quasar windows, including cutouts and the IME. */
    fun install(activity: android.app.Activity, content: View) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val frame = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(background)
            addView(content)
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(frame)
        androidx.core.view.WindowCompat.getInsetsController(activity.window, frame).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        androidx.core.view.ViewCompat.requestApplyInsets(frame)
    }

    fun title(context: Context, value: String, size: Float = 26f) = TextView(context).apply {
        this.text = value
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(QuasarDesign.text)
        letterSpacing = -.02f
    }

    fun eyebrow(context: Context, value: String, color: Int = cyan) = TextView(context).apply {
        text = value.uppercase()
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        letterSpacing = .06f
    }

    fun body(context: Context, value: String, color: Int = muted, size: Float = 14f) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.16f)
        setTextIsSelectable(true)
    }

    fun pill(context: Context, value: String, color: Int) = TextView(context).apply {
        text = value
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        background = rounded(Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)), color, context.dp(99).toFloat())
    }

    fun card(context: Context, accent: Int? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = rounded(panel, accent ?: border, context.dp(8).toFloat())
    }

    fun action(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) QuasarDesign.background else QuasarDesign.text)
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            }
            setPadding(context.dp(16), context.dp(15), context.dp(16), context.dp(15))
            minHeight = context.dp(52)
            isClickable = true
            isFocusable = true
            background = ripple(
                context,
                if (primary) cyan else panelRaised,
                if (primary) cyan else border,
                context.dp(8).toFloat(),
            )
            setOnClickListener { onClick() }
        }

    fun routeCard(
        context: Context,
        label: String,
        detail: String,
        accent: Int,
        onClick: () -> Unit,
    ) = card(context, border).apply {
        minimumHeight = context.dp(64)
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        contentDescription = "$label. $detail"
        background = ripple(context, panel, border, context.dp(8).toFloat())
        addView(title(context, label, 18f))
        addView(body(context, detail, muted, 14f), match(top = context.dp(4)))
        setOnClickListener { onClick() }
    }

    fun field(context: Context, value: String = "", lines: Int = 1, hint: String = "") = EditText(context).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        setTextColor(QuasarDesign.text)
        setHintTextColor(muted)
        setPadding(context.dp(14), context.dp(13), context.dp(14), context.dp(13))
        minHeight = context.dp(52)
        setSingleLine(lines == 1)
        minLines = lines
        if (lines > 1) {
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        background = rounded(panelRaised, border, context.dp(8).toFloat())
    }

    fun match(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(1, stroke)
    }

    private fun ripple(context: Context, fill: Int, stroke: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
        rounded(fill, stroke, radius),
        null,
    )
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
