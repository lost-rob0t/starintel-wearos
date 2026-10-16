package actor.starintel.quasar

import actor.starintel.design.SiTokens
import android.content.Context
import org.json.JSONObject

/** App-local appearance; never modifies the shared palette or another APK. */
internal data class QuasarPalette(
    val background: Int, val surface: Int, val raised: Int, val border: Int,
    val accent: Int, val alternate: Int, val text: Int, val muted: Int,
    val good: Int, val warning: Int, val danger: Int,
) {
    val onAccent: Int get() = if (SiTokens.contrastRatio(accent, 0xFF000000.toInt()) >= SiTokens.contrastRatio(accent, -1)) 0xFF000000.toInt() else -1
    companion object {
        fun hex(value: String): Int {
            require(value.matches(Regex("#[0-9a-fA-F]{6}"))) { "Use a six-digit color, for example #2DE2E6" }
            return (0xFF000000L or value.drop(1).toLong(16)).toInt()
        }
        fun format(value: Int) = "#%06X".format(value and 0xFFFFFF)
        fun mix(a: Int, b: Int, amount: Double): Int {
            fun part(shift: Int) = (((a ushr shift and 255) * (1 - amount) + (b ushr shift and 255) * amount).toInt()).coerceIn(0, 255)
            return (0xFF shl 24) or (part(16) shl 16) or (part(8) shl 8) or part(0)
        }
        fun legible(color: Int, surfaces: List<Int>, dark: Boolean, fallback: Int = if (dark) -1 else 0xFF000000.toInt()): Int {
            for (step in 0..100) {
                for (target in if (dark) listOf(-1, 0xFF000000.toInt()) else listOf(0xFF000000.toInt(), -1)) {
                    val adjusted = mix(color, target, step / 100.0)
                    if (surfaces.all { SiTokens.contrastRatio(adjusted, it) >= 4.5 }) return adjusted
                }
            }
            require(surfaces.all { SiTokens.contrastRatio(fallback, it) >= 4.5 }) { "The surfaces need a common readable foreground" }
            return fallback
        }
        fun create(background: Int, surface: Int, raised: Int, accent: Int, alternate: Int, text: Int, muted: Int, dark: Boolean): QuasarPalette {
            val surfaces = listOf(background, surface, raised)
            val readableText = legible(text, surfaces, dark, text)
            return QuasarPalette(background, surface, raised, mix(text, surface, .57),
                legible(accent, surfaces, dark, readableText), legible(alternate, surfaces, dark, readableText),
                readableText, legible(muted, surfaces, dark, readableText),
                legible(if (dark) hex("#8FE36B") else hex("#286339"), surfaces, dark, readableText),
                legible(if (dark) hex("#FBC56B") else hex("#805000"), surfaces, dark, readableText),
                legible(if (dark) hex("#FF91AA") else hex("#AA2349"), surfaces, dark, readableText))
        }
        fun starintel(dark: Boolean): QuasarPalette {
            if (dark) return SiTokens.CYAN.let { p ->
                val surfaces = listOf(p.background, p.surface, p.raised)
                create(p.background, p.surface, p.raised, p.accent, p.accentAlt, p.text, p.muted, true).copy(
                    good = legible(p.ok, surfaces, true), warning = legible(p.warn, surfaces, true), danger = legible(p.danger, surfaces, true))
            }
            return create(hex("#F3F6FC"), -1, hex("#E7EDF7"), hex("#006874"), hex("#7837A8"), hex("#122139"), hex("#465970"), false)
        }
        fun synthwave(dark: Boolean): QuasarPalette = if (dark)
            create(hex("#130D25"), hex("#201632"), hex("#2D2146"), hex("#FF86CF"), hex("#71E4F1"), hex("#FBF3FF"), hex("#CBBEDC"), true)
        else create(hex("#FAF3FF"), hex("#FFFBFF"), hex("#F0E5F7"), hex("#9C257B"), hex("#006D7B"), hex("#291536"), hex("#68536E"), false)
    }
}

internal class QuasarAppearance(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val theme get() = prefs.getString("theme", "starintel")!!.takeIf { it in THEMES } ?: "starintel"
    val mode get() = prefs.getString("mode", "dark")!!.takeIf { it in MODES } ?: "dark"
    fun isDark(systemDark: Boolean) = when (mode) { "light" -> false; "system" -> systemDark; else -> true }
    fun palette(dark: Boolean): QuasarPalette = when (theme) {
        "synthwave" -> QuasarPalette.synthwave(dark)
        "custom" -> runCatching { customPalette(custom(), dark) }.getOrElse { QuasarPalette.starintel(dark) }
        else -> QuasarPalette.starintel(dark)
    }
    fun select(theme: String, mode: String) {
        require(theme in THEMES && mode in MODES)
        prefs.edit().putString("theme", theme).putString("mode", mode).apply()
    }
    fun custom(): JSONObject = runCatching { JSONObject(prefs.getString("custom", null) ?: defaultCustom().toString()) }.getOrElse { defaultCustom() }
    fun saveCustom(source: String) {
        val parsed = validateCustom(source)
        prefs.edit().putString("custom", parsed.toString()).apply()
    }
    companion object {
        const val PREFS = "quasar_appearance_v1"
        val THEMES = listOf("starintel", "synthwave", "custom")
        val MODES = listOf("dark", "light", "system")
        fun defaultCustom() = JSONObject().put("name", "My StarIntel").apply {
            for (dark in listOf(true, false)) {
                val p = QuasarPalette.starintel(dark)
                put(if (dark) "dark" else "light", JSONObject()
                    .put("background", QuasarPalette.format(p.background)).put("surface", QuasarPalette.format(p.surface))
                    .put("accent", QuasarPalette.format(p.accent)).put("alternate", QuasarPalette.format(p.alternate))
                    .put("text", QuasarPalette.format(p.text)))
            }
        }
        fun validateCustom(source: String): JSONObject {
            require(source.toByteArray(Charsets.UTF_8).size <= 8192) { "Theme must be at most 8 KB" }
            val json = JSONObject(source)
            require(json.optString("name").trim().length in 1..60) { "Give the theme a name (1–60 characters)" }
            customPalette(json, true); customPalette(json, false)
            return json
        }
        fun customPalette(json: JSONObject, dark: Boolean): QuasarPalette {
            val variant = if (dark) "dark" else "light"
            val colors = json.optJSONObject(variant) ?: error("Add the $variant color variant")
            fun color(key: String) = runCatching { QuasarPalette.hex(colors.getString(key)) }.getOrElse { error("$variant.$key: use #RRGGBB") }
            val bg = color("background"); val surface = color("surface"); val text = color("text"); val accent = color("accent")
            val bgLuminance = (SiTokens.contrastRatio(bg, 0xFF000000.toInt()) - 1) * .05
            require(if (dark) bgLuminance <= .18 else bgLuminance >= .6) { "$variant.background: choose a ${if (dark) "dark" else "light"} background so system-bar icons remain readable" }
            val raised = QuasarPalette.mix(surface, bg, .25)
            val surfaces = listOf(bg, surface, raised)
            require(surfaces.all { SiTokens.contrastRatio(text, it) >= 4.5 }) { "$variant.text: text needs at least 4.5:1 contrast on both surfaces" }
            require(surfaces.all { SiTokens.contrastRatio(accent, it) >= 4.5 }) { "$variant.accent: choose a more readable accent (minimum 4.5:1)" }
            return QuasarPalette.create(bg, surface, raised, accent, color("alternate"), text, QuasarPalette.mix(text, surface, .3), dark)
        }
    }
}
