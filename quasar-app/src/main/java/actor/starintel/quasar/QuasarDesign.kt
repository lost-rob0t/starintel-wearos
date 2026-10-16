package actor.starintel.quasar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
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
    private var palette = QuasarPalette.starintel(true)
    val isDark get() = dark
    fun configure(context: Context) {
        val systemDark = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val appearance = QuasarAppearance(context)
        dark = appearance.isDark(systemDark)
        palette = appearance.palette(dark)
        if (context is android.app.Activity) context.setTheme(if (dark) R.style.Theme_Quasar_Dark else R.style.Theme_Quasar_Light)
    }
    val background get() = palette.background
    val panel get() = palette.surface
    val panelRaised get() = palette.raised
    val border get() = palette.border
    val cyan get() = palette.accent
    val pink get() = palette.alternate
    val amber get() = palette.warning
    val lime get() = palette.good
    val coral get() = palette.danger
    val text get() = palette.text
    val muted get() = palette.muted
    val onAccent get() = palette.onAccent

    /** One inset owner for all Quasar windows, including cutouts and the IME. */
    fun install(activity: android.app.Activity, content: View) {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        val frame = android.widget.FrameLayout(activity).apply {
            setBackgroundColor(QuasarDesign.background)
            addView(content)
        }
        val lightBars = !dark
        fun updateBars() {
            androidx.core.view.WindowCompat.getInsetsController(activity.window, frame).apply {
                if (isAppearanceLightStatusBars != lightBars) isAppearanceLightStatusBars = lightBars
                if (isAppearanceLightNavigationBars != lightBars) isAppearanceLightNavigationBars = lightBars
            }
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
            updateBars()
            val safe = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout() or androidx.core.view.WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        activity.setContentView(frame)
        // Starting-window attachment/focus can replace the early appearance request.
        // Reassert this window's palette after that handoff, including theme recreation.
        frame.viewTreeObserver.addOnWindowFocusChangeListener { focused -> if (focused) updateBars() }
        frame.post { updateBars() }
        updateBars()
        androidx.core.view.ViewCompat.requestApplyInsets(frame)
    }

    fun title(context: Context, value: String, size: Float = 26f) = TextView(context).apply {
        this.text = value
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(QuasarDesign.text)
        letterSpacing = -.025f
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

    fun status(context: Context, value: String) = body(context, value).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun pill(context: Context, value: String, color: Int) = TextView(context).apply {
        text = value
        textSize = 12f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        stylePill(context, this, color)
    }

    fun stylePill(context: Context, view: TextView, color: Int) {
        view.setTextColor(color)
        view.background = rounded(panelRaised, color, context.dp(99).toFloat())
    }

    fun card(context: Context, accent: Int? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = rounded(panel, accent ?: QuasarPalette.mix(border, panel, .5), context.dp(16).toFloat())
    }

    fun rowBackground(context: Context, selected: Boolean) = rounded(
        if (selected) panelRaised else panel,
        if (selected) cyan else border,
        context.dp(16).toFloat(),
    )

    fun action(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted, if (primary) onAccent else QuasarDesign.text)))
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
                if (primary) cyan else QuasarPalette.mix(border, panelRaised, .6),
                context.dp(12).toFloat(),
            )
            setOnClickListener { onClick() }
        }

    fun routeCard(
        context: Context,
        label: String,
        detail: String,
        accent: Int,
        onClick: () -> Unit,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14), context.dp(16), context.dp(14), context.dp(16))
        minimumHeight = context.dp(80)
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        androidx.core.view.ViewCompat.setScreenReaderFocusable(this, true)
        contentDescription = "$label. $detail"
        background = ripple(context, panel, QuasarPalette.mix(border, panel, .6), context.dp(16).toFloat())
        addView(android.widget.ImageView(context).apply {
            setImageDrawable(QuasarIcon(label, accent, context.dp(22)))
            setPadding(context.dp(10), context.dp(10), context.dp(10), context.dp(10))
            background = rounded(QuasarPalette.mix(accent, panel, .9), null, context.dp(12).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(context.dp(44), context.dp(44)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title(context, label, 18f))
            addView(body(context, detail, muted, 14f).apply { setTextIsSelectable(false) }, match(top = context.dp(4)))
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.dp(12) })
        addView(TextView(context).apply { text = "›"; textSize = 26f; setTextColor(muted); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(8) })
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
        highlightColor = Color.argb(42, Color.red(cyan), Color.green(cyan), Color.blue(cyan))
        if (android.os.Build.VERSION.SDK_INT >= 29) textCursorDrawable = textCursorDrawable?.mutate()?.apply { setTint(cyan) }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(panelRaised, cyan, context.dp(12).toFloat()).apply { setStroke(context.dp(2), cyan) })
            addState(intArrayOf(), rounded(panelRaised, QuasarPalette.mix(border, panelRaised, .25), context.dp(12).toFloat()))
        }
    }

    fun match(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = top
        bottomMargin = bottom
    }

    private fun rounded(fill: Int, stroke: Int?, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        if (stroke != null) setStroke(1, stroke)
    }

    private fun ripple(context: Context, fill: Int, stroke: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45, 255, 255, 255)),
        StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(panelRaised, QuasarPalette.mix(border, panelRaised, .6), radius))
            addState(intArrayOf(), rounded(fill, stroke, radius))
        },
        null,
    )
}

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
