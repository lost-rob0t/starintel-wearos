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

internal object QuasarDesign {
    val background = Color.rgb(7, 7, 12)
    val panel = Color.rgb(18, 18, 31)
    val panelRaised = Color.rgb(25, 24, 42)
    val border = Color.rgb(48, 46, 76)
    val cyan = Color.rgb(45, 226, 230)
    val pink = Color.rgb(246, 1, 157)
    val amber = Color.rgb(251, 169, 34)
    val lime = Color.rgb(98, 255, 0)
    val coral = Color.rgb(221, 84, 110)
    val text = Color.rgb(243, 244, 245)
    val muted = Color.rgb(164, 166, 184)

    fun title(context: Context, value: String, size: Float = 30f) = TextView(context).apply {
        this.text = value
        textSize = size
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(QuasarDesign.text)
        letterSpacing = -.02f
    }

    fun eyebrow(context: Context, value: String, color: Int = cyan) = TextView(context).apply {
        text = value.uppercase()
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        letterSpacing = .18f
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
        textSize = 11f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(color)
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        background = rounded(Color.argb(25, Color.red(color), Color.green(color), Color.blue(color)), color, context.dp(99).toFloat())
    }

    fun card(context: Context, accent: Int? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
        background = rounded(panel, accent ?: border, context.dp(18).toFloat())
    }

    fun action(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit) =
        TextView(context).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (primary) QuasarDesign.background else QuasarDesign.text)
            setPadding(context.dp(16), context.dp(15), context.dp(16), context.dp(15))
            minHeight = context.dp(52)
            isClickable = true
            isFocusable = true
            background = ripple(
                context,
                if (primary) cyan else panelRaised,
                if (primary) cyan else border,
                context.dp(15).toFloat(),
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
        minimumHeight = context.dp(126)
        isClickable = true
        isFocusable = true
        background = ripple(context, panel, border, context.dp(18).toFloat())
        addView(View(context).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(context.dp(28), context.dp(3)))
        addView(title(context, label, 18f), match(top = context.dp(18)))
        addView(body(context, detail, muted, 12f), match(top = context.dp(6)))
        setOnClickListener { onClick() }
    }

    fun field(context: Context, value: String = "", lines: Int = 1, hint: String = "") = EditText(context).apply {
        setText(value)
        this.hint = hint
        textSize = 15f
        setTextColor(QuasarDesign.text)
        setHintTextColor(muted)
        setPadding(context.dp(14), context.dp(13), context.dp(14), context.dp(13))
        setSingleLine(lines == 1)
        minLines = lines
        if (lines > 1) {
            gravity = Gravity.TOP
            typeface = Typeface.MONOSPACE
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        background = rounded(panelRaised, border, context.dp(14).toFloat())
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
