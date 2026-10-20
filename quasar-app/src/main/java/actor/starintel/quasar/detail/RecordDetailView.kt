package actor.starintel.quasar.detail

import actor.starintel.quasar.QuasarDesign
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout

/** Native detail projection. Only the selected expansion exists; detachment drops its worker. */
internal class RecordDetailView(context: Context, private val root: org.json.JSONObject, id: String) : LinearLayout(context) {
    private val doc = root.optJSONObject("document")
        ?: root.optJSONObject("data")?.optJSONObject("document") ?: root.optJSONObject("data") ?: root
    private val data = doc.optJSONObject("data") ?: doc
    private var session: RecordDetailSession? = null
    private var expansion: Boolean? = null
    private val preview = QuasarDesign.body(context, "", QuasarDesign.text, 12f).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        visibility = View.GONE
    }
    private val notice = QuasarDesign.body(context, "").apply { visibility = View.GONE }

    init {
        orientation = VERTICAL
        fun scalar(key: String, limit: Int = 512) = RecordDetailPreview.scalar(data, key, limit)
        addView(QuasarDesign.eyebrow(context, RecordDetailPreview.scalar(doc, "dtype", 80).ifBlank { "document" }))
        addView(QuasarDesign.title(context, scalar("name").ifBlank { scalar("title") }.ifBlank { id.take(512) }, 22f), spacing(8))
        addView(QuasarDesign.body(context, id.take(512)), spacing(8))
        addView(QuasarDesign.body(context, scalar("summary", 2048).ifBlank { scalar("description", 2048) }
            .ifBlank { "No summary supplied in this record." }, QuasarDesign.text, 16f), spacing(12))
        listOf("source", "dataset", "created_at", "updated_at", "observed_at").forEach { key ->
            val value = RecordDetailPreview.scalar(doc, key, 512).ifBlank { scalar(key) }
            if (value.isNotBlank()) addView(QuasarDesign.body(context, "${key.replace('_', ' ')} · $value"), spacing(8))
        }
        addView(QuasarDesign.action(context, "Structured fields") { toggle(false) }, spacing(16))
        addView(QuasarDesign.action(context, "Raw JSON") { toggle(true) }, spacing(8))
        addView(notice, spacing(8))
        addView(preview, spacing(8))
    }

    private fun spacing(top: Int) = QuasarDesign.match(top = (top * resources.displayMetrics.density).toInt())

    private fun toggle(raw: Boolean) {
        if (!isAttachedToWindow) return
        session?.cancel()
        preview.text = ""
        if (expansion == raw) {
            expansion = null
            preview.visibility = View.GONE
            notice.visibility = View.GONE
            return
        }
        expansion = raw
        notice.text = "Preparing preview…"
        notice.visibility = View.VISIBLE
        preview.visibility = View.VISIBLE
        val active = session ?: RecordDetailSession(root, data, { command -> post(command) }).also { session = it }
        active.request(raw, { result ->
            if (isAttachedToWindow && expansion == raw) {
                preview.text = result.text
                notice.text = if (result.truncated)
                    "Partial preview: limited to 16,384 characters, 1,024 values, 32 levels and 64 fields. This is not exportable JSON. Full export is unavailable in this view."
                else if (raw) "Complete raw preview." else "Structured field preview."
            }
        }, {
            if (isAttachedToWindow && expansion == raw) {
                preview.text = ""
                notice.text = "Preview unavailable. Collapse and expand to retry."
            }
        })
    }

    override fun onDetachedFromWindow() {
        session?.close()
        session = null
        expansion = null
        preview.text = ""
        preview.visibility = View.GONE
        notice.visibility = View.GONE
        super.onDetachedFromWindow()
    }
}
