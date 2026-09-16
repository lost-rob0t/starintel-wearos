package actor.starintel.wear.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

enum class StarIntelThemeId(val wire: String) {
    CYAN("cyan"),
    PURPLE("purple"),
    LIME("lime"),
    AMBER("amber");

    fun next(): StarIntelThemeId {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    companion object {
        fun fromWire(value: String?): StarIntelThemeId =
            entries.firstOrNull { it.wire == value } ?: CYAN
    }
}

data class StarIntelPalette(
    val id: StarIntelThemeId,
    val background: Int,
    val surface: Int,
    val accent: Int,
    val text: Int = Color.WHITE,
    val muted: Int = Color.rgb(176, 187, 199),
    val warning: Int = Color.rgb(255, 132, 132),
)

class StarIntelThemeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentId(): StarIntelThemeId = StarIntelThemeId.fromWire(prefs.getString(KEY_THEME, null))

    fun current(): StarIntelPalette = palette(currentId())

    fun set(id: StarIntelThemeId) {
        prefs.edit().putString(KEY_THEME, id.wire).apply()
    }

    fun cycle(): StarIntelPalette {
        val next = currentId().next()
        set(next)
        return palette(next)
    }

    companion object {
        private const val PREFS = "starintel_wear_ui"
        private const val KEY_THEME = "theme"

        fun palette(id: StarIntelThemeId): StarIntelPalette = when (id) {
            StarIntelThemeId.CYAN -> StarIntelPalette(
                id,
                background = Color.BLACK,
                surface = Color.rgb(10, 15, 19),
                accent = Color.rgb(0, 229, 255),
            )
            StarIntelThemeId.PURPLE -> StarIntelPalette(
                id,
                background = Color.rgb(5, 5, 9),
                surface = Color.rgb(16, 10, 23),
                accent = Color.rgb(198, 91, 255),
            )
            StarIntelThemeId.LIME -> StarIntelPalette(
                id,
                background = Color.rgb(4, 7, 5),
                surface = Color.rgb(9, 17, 12),
                accent = Color.rgb(126, 255, 128),
            )
            StarIntelThemeId.AMBER -> StarIntelPalette(
                id,
                background = Color.rgb(8, 6, 3),
                surface = Color.rgb(20, 14, 6),
                accent = Color.rgb(255, 190, 70),
            )
        }
    }
}

fun Button.applyStarIntelTheme(palette: StarIntelPalette) {
    val density = resources.displayMetrics.density
    backgroundTintList = null
    background = RippleDrawable(ColorStateList.valueOf(palette.accent and 0x40FFFFFF),
        GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = 6 * density
            setStroke(density.toInt().coerceAtLeast(1), palette.accent)
        }, null)
    setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(palette.muted, palette.accent)))
    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    textSize = 12f
    minHeight = (48 * density).toInt()
    setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
}

fun TextView.applyStarIntelText(palette: StarIntelPalette, muted: Boolean = false) {
    setTextColor(if (muted) palette.muted else palette.text)
}

fun EditText.applyStarIntelInput(palette: StarIntelPalette) {
    setTextColor(palette.text)
    setHintTextColor(palette.muted)
    backgroundTintList = ColorStateList.valueOf(palette.accent)
}
