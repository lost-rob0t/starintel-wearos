package actor.starintel.quasar

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

internal class AppearanceView(context: Context, saved: Bundle?, private val apply: () -> Unit) : ScrollView(context) {
    private val store = QuasarAppearance(context)
    private var theme = saved?.getString("theme") ?: store.theme
    private var mode = saved?.getString("mode") ?: store.mode
    private var editingDark = saved?.getBoolean("editingDark") ?: true
    private var draft = runCatching { JSONObject(saved?.getString("draft") ?: store.custom().toString()) }.getOrElse { store.custom() }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(32)) }
    private val preview = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val custom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val colors = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fields = linkedMapOf<String, EditText>()
    private val status = QuasarDesign.status(context, "Changes stay in the preview until you apply them.")
    private lateinit var name: EditText
    private lateinit var variantButtons: RadioGroup
    private var binding = false
    private val refresh = Runnable { captureDraft(); renderPreview() }

    init {
        isFillViewport = true
        addView(body)
        body.addView(QuasarDesign.eyebrow(context, "STARINTEL / APPEARANCE"))
        body.addView(QuasarDesign.title(context, "Make it yours", 30f), QuasarDesign.match(top = context.dp(6)))
        body.addView(QuasarDesign.body(context, "StarIntel by default. One visual system across your investigation workspace."), QuasarDesign.match(top = context.dp(8)))
        heading("Workspace theme")
        body.addView(choices(listOf("starintel" to "StarIntel · cyan / violet", "synthwave" to "Synthwave · pink / electric blue", "custom" to "Custom · your saved colors"), theme) {
            captureDraft(); theme = it; custom.visibility = if (it == "custom") View.VISIBLE else View.GONE; renderPreview()
        }, QuasarDesign.match(top = context.dp(8)))
        heading("Display mode")
        body.addView(choices(listOf("dark" to "Dark", "light" to "Light", "system" to "Follow system"), mode) { mode = it; renderPreview() }, QuasarDesign.match(top = context.dp(8)))
        body.addView(preview, QuasarDesign.match(top = context.dp(20)))
        custom.visibility = if (theme == "custom") View.VISIBLE else View.GONE
        custom.addView(QuasarDesign.eyebrow(context, "CUSTOM PALETTE"), QuasarDesign.match(top = context.dp(24)))
        custom.addView(QuasarDesign.body(context, "Save both variants. Text and the primary accent need 4.5:1 contrast. Secondary and status colors adapt for readability."), QuasarDesign.match(top = context.dp(8)))
        name = QuasarDesign.field(context, draft.optString("name", "My theme")).apply { contentDescription = "Theme name"; filters = arrayOf(InputFilter.LengthFilter(60)); watch(this) }
        custom.addView(QuasarDesign.body(context, "Theme name", QuasarDesign.text), QuasarDesign.match(top = context.dp(16)))
        custom.addView(name, QuasarDesign.match(top = context.dp(6)))
        variantButtons = choices(listOf("dark" to "Edit dark variant", "light" to "Edit light variant"), if (editingDark) "dark" else "light") {
            captureDraft(); editingDark = it == "dark"; bindColors(); renderPreview()
        }
        custom.addView(variantButtons, QuasarDesign.match(top = context.dp(16)))
        custom.addView(colors, QuasarDesign.match(top = context.dp(8)))
        custom.addView(QuasarDesign.action(context, "Import theme JSON") { importTheme() }, QuasarDesign.match(top = context.dp(16)))
        custom.addView(QuasarDesign.action(context, "Export theme JSON") { exportTheme() }, QuasarDesign.match(top = context.dp(8)))
        body.addView(custom)
        body.addView(status, QuasarDesign.match(top = context.dp(16)))
        body.addView(QuasarDesign.action(context, "Apply theme", primary = true) {
            captureDraft()
            runCatching { if (theme == "custom") store.saveCustom(draft.toString()); store.select(theme, mode) }
                .onSuccess { apply() }
                .onFailure { showError(it.message ?: "Theme could not be saved") }
        }, QuasarDesign.match(top = context.dp(12)))
        bindColors(); renderPreview()
        viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (height == 0 || body.height == 0) return
                scrollTo(0, saved?.getInt("scrollY") ?: 0)
                viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
    }

    fun saveWorkspace() = Bundle().apply {
        captureDraft(); putString("theme", theme); putString("mode", mode); putBoolean("editingDark", editingDark); putString("draft", draft.toString()); putInt("scrollY", scrollY)
    }

    private fun heading(label: String) = body.addView(QuasarDesign.title(context, label, 18f), QuasarDesign.match(top = context.dp(24)))

    private fun choices(items: List<Pair<String, String>>, selected: String, changed: (String) -> Unit) = RadioGroup(context).apply {
        orientation = RadioGroup.VERTICAL
        items.forEach { (id, label) ->
            addView(RadioButton(context).apply {
                this.id = View.generateViewId(); tag = id; text = label; textSize = 16f
                setTextColor(QuasarDesign.text); buttonTintList = ColorStateList.valueOf(QuasarDesign.cyan)
                minHeight = context.dp(52); setPadding(context.dp(8), context.dp(10), context.dp(12), context.dp(10))
                isChecked = id == selected
            }, LinearLayout.LayoutParams(-1, -2))
        }
        setOnCheckedChangeListener { group, checked -> changed(group.findViewById<RadioButton>(checked).tag.toString()) }
    }

    private fun watch(field: EditText) = field.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!binding) { field.error = null; removeCallbacks(refresh); postDelayed(refresh, 180) }
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    private fun bindColors() {
        binding = true; fields.clear(); colors.removeAllViews()
        val variant = if (editingDark) "dark" else "light"
        val values = draft.getJSONObject(variant)
        for ((key, label) in listOf("background" to "Background", "surface" to "Surface", "accent" to "Primary accent", "alternate" to "Secondary accent", "text" to "Text")) {
            colors.addView(QuasarDesign.body(context, label, QuasarDesign.text), QuasarDesign.match(top = context.dp(12)))
            val input = QuasarDesign.field(context, values.optString(key), hint = "#RRGGBB").apply {
                contentDescription = "$variant $label color"
                typeface = Typeface.MONOSPACE; filters = arrayOf(InputFilter.LengthFilter(7))
                watch(this)
            }
            fields[key] = input; colors.addView(input, QuasarDesign.match(top = context.dp(6)))
        }
        binding = false
    }

    private fun captureDraft() {
        if (!::name.isInitialized || binding) return
        draft.put("name", name.text.toString())
        val values = draft.getJSONObject(if (editingDark) "dark" else "light")
        fields.forEach { (key, field) -> values.put(key, field.text.toString()) }
    }

    private fun renderPreview() {
        val dark = if (theme == "custom") editingDark else when (mode) {
            "dark" -> true; "light" -> false; else -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val valid = runCatching { when (theme) {
            "synthwave" -> QuasarPalette.synthwave(dark)
            "custom" -> QuasarAppearance.customPalette(draft, dark)
            else -> QuasarPalette.starintel(dark)
        } }.getOrNull()
        val candidate = valid ?: store.palette(dark)
        preview.removeAllViews()
        preview.setPadding(context.dp(18), context.dp(18), context.dp(18), context.dp(18))
        preview.background = GradientDrawable().apply { setColor(candidate.background); cornerRadius = context.dp(18).toFloat(); setStroke(context.dp(1), candidate.border) }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            this.text = value; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        preview.addView(text(if (valid != null) "LIVE PREVIEW · ${if (dark) "DARK" else "LIGHT"}" else "SAVED PREVIEW · COMPLETE THE COLORS", 11f, candidate.accent, true))
        preview.addView(text("Investigation desk", 24f, candidate.text, true), QuasarDesign.match(top = context.dp(12)))
        preview.addView(text("Records, field maps and actor tools.", 14f, candidate.muted), QuasarDesign.match(top = context.dp(6)))
        preview.addView(text("Secondary accent", 13f, candidate.alternate), QuasarDesign.match(top = context.dp(8)))
        preview.addView(text("Ready", 13f, candidate.good), QuasarDesign.match(top = context.dp(18)))
        preview.addView(text("Needs attention", 13f, candidate.warning), QuasarDesign.match(top = context.dp(4)))
        preview.addView(text("Failed", 13f, candidate.danger), QuasarDesign.match(top = context.dp(4)))
        preview.addView(text("Primary action", 14f, candidate.onAccent, true).apply {
            gravity = Gravity.CENTER; minHeight = context.dp(52)
            background = GradientDrawable().apply { setColor(candidate.accent); cornerRadius = context.dp(12).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, QuasarDesign.match(top = context.dp(16)))
        preview.contentDescription = "${if (theme == "custom") draft.optString("name") else theme} ${if (dark) "dark" else "light"} theme preview"
    }

    private fun showError(message: String) {
        status.text = message
        if (message.startsWith("dark.") || message.startsWith("light.")) {
            val dark = message.startsWith("dark.")
            if (editingDark != dark) {
                val tag = if (dark) "dark" else "light"
                val button = (0 until variantButtons.childCount).map { variantButtons.getChildAt(it) as RadioButton }.single { it.tag == tag }
                variantButtons.check(button.id)
            }
            fields[message.substringAfter('.').substringBefore(':')]?.let { it.error = message; it.requestFocus() }
        } else name.error = message
    }

    private fun importTheme() {
        val input = QuasarDesign.field(context, lines = 10, hint = "Paste exported theme JSON").apply { contentDescription = "Theme JSON" }
        val dialog = AlertDialog.Builder(context).setTitle("Import custom theme").setView(input).setPositiveButton("Load preview", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            runCatching { QuasarAppearance.validateCustom(input.text.toString()) }
                .onSuccess { draft = it; name.setText(draft.getString("name")); bindColors(); renderPreview(); status.text = "Imported into preview. Apply theme to save."; dialog.dismiss() }
                .onFailure { input.error = it.message ?: "Invalid theme JSON" }
        } }
        dialog.show()
    }

    private fun exportTheme() {
        captureDraft()
        runCatching { QuasarAppearance.validateCustom(draft.toString()) }.onFailure { showError(it.message ?: "Complete the theme before exporting"); return }
        val source = draft.toString(2)
        val text = QuasarDesign.body(context, source, QuasarDesign.text, 13f).apply { typeface = Typeface.MONOSPACE; setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12)) }
        AlertDialog.Builder(context).setTitle("Custom theme JSON").setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton("Copy") { _, _ -> context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Quasar theme", source)) }
            .setNegativeButton("Close", null).show()
    }

    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
}
