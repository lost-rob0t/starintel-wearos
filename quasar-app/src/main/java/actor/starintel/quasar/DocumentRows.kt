package actor.starintel.quasar

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

/** A bounded display projection, created off the UI thread; never a second document store. */
internal data class DocumentRow(val id: String, val title: String, val kind: String, val summary: String) {
    companion object {
        fun from(rows: JSONArray, limit: Int = 100): List<DocumentRow> = buildList {
            for (index in 0 until minOf(rows.length(), limit)) {
                val row = rows.optJSONObject(index) ?: continue
                val doc = resultDocument(row)
                val id = resultId(row)
                if (id.isBlank()) continue
                val data = doc.optJSONObject("data") ?: doc
                add(DocumentRow(id, data.optString("name").ifBlank { data.optString("title") }.ifBlank { id }.take(240),
                    doc.optString("dtype").ifBlank { "document" }.take(80),
                    data.optString("summary").ifBlank { data.optString("description") }.take(400)))
            }
        }.distinctBy { it.id }
    }
}

internal class DocumentRows(private val onSelect: (DocumentRow) -> Unit) : ListAdapter<DocumentRow, DocumentRows.Holder>(Diff) {
    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            currentList.indexOfFirst { it.id == previous }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            currentList.indexOfFirst { it.id == value }.takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }
    class Holder(val row: LinearLayout, val title: TextView, val meta: TextView, val summary: TextView) : RecyclerView.ViewHolder(row)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val row = QuasarDesign.card(context).apply {
            layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) }
            minimumHeight = context.dp(72)
            isClickable = true
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        val title = QuasarDesign.title(context, "", 19f)
        val meta = QuasarDesign.body(context, "", QuasarDesign.cyan, size = 13f).apply { typeface = android.graphics.Typeface.MONOSPACE }
        val summary = QuasarDesign.body(context, "", QuasarDesign.muted).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        row.addView(title); row.addView(meta, QuasarDesign.match(top = context.dp(4)))
        row.addView(summary, QuasarDesign.match(top = context.dp(4)))
        return Holder(row, title, meta, summary)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.meta.text = "${item.kind} · ${item.id}${if (item.id == selectedId) " · Selected" else ""}"
        holder.summary.text = item.summary
        holder.summary.visibility = if (item.summary.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        holder.row.isSelected = item.id == selectedId
        holder.row.background = QuasarDesign.rowBackground(holder.row.context, item.id == selectedId)
        holder.row.contentDescription = "${item.title}. ${item.kind}. ${item.id}. ${item.summary}${if (item.id == selectedId) ". Selected" else ""}"
        holder.row.setOnClickListener { selectedId = item.id; onSelect(item) }
    }
    private object Diff : DiffUtil.ItemCallback<DocumentRow>() {
        override fun areItemsTheSame(oldItem: DocumentRow, newItem: DocumentRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DocumentRow, newItem: DocumentRow) = oldItem == newItem
    }
}
