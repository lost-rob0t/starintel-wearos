package actor.starintel.mobile

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button

internal fun Button.terminalStyle() {
    val d = resources.displayMetrics.density
    val cyan = Color.rgb(0, 229, 255)
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(0x3300E5FF), GradientDrawable().apply {
        setColor(Color.rgb(6, 15, 18)); cornerRadius = 6 * d; setStroke(d.toInt().coerceAtLeast(1), cyan)
    }, null)
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.GRAY, cyan)))
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    minHeight = (52 * d).toInt()
}
