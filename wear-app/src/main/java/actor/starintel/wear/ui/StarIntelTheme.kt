package actor.starintel.wear.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    val muted: Int = Color.rgb(188, 198, 210),
    val warning: Int = Color.rgb(255, 112, 120),
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
                surface = Color.rgb(11, 16, 21),
                accent = Color.rgb(0, 229, 255),
            )
            StarIntelThemeId.PURPLE -> StarIntelPalette(
                id,
                background = Color.BLACK,
                surface = Color.rgb(18, 10, 25),
                accent = Color.rgb(198, 91, 255),
            )
            StarIntelThemeId.LIME -> StarIntelPalette(
                id,
                background = Color.BLACK,
                surface = Color.rgb(9, 18, 12),
                accent = Color.rgb(126, 255, 128),
            )
            StarIntelThemeId.AMBER -> StarIntelPalette(
                id,
                background = Color.BLACK,
                surface = Color.rgb(22, 15, 6),
                accent = Color.rgb(255, 190, 70),
            )
        }
    }
}

fun Button.applyStarIntelTheme(palette: StarIntelPalette) {
    backgroundTintList = null
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(palette.surface)
        cornerRadius = 18f * resources.displayMetrics.density
        setStroke((1.25f * resources.displayMetrics.density).toInt().coerceAtLeast(1), palette.accent)
    }
    setTextColor(palette.text)
    minHeight = (44f * resources.displayMetrics.density).toInt()
    isAllCaps = true
}

fun TextView.applyStarIntelText(palette: StarIntelPalette, muted: Boolean = false) {
    setTextColor(if (muted) palette.muted else palette.text)
}

fun EditText.applyStarIntelInput(palette: StarIntelPalette) {
    setTextColor(palette.text)
    setHintTextColor(palette.muted)
    backgroundTintList = null
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(palette.surface)
        cornerRadius = 14f * resources.displayMetrics.density
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), palette.accent)
    }
    setPadding(
        (12f * resources.displayMetrics.density).toInt(),
        (9f * resources.displayMetrics.density).toInt(),
        (12f * resources.displayMetrics.density).toInt(),
        (9f * resources.displayMetrics.density).toInt(),
    )
}
