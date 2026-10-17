package actor.starintel.quasar

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import actor.starintel.quasar.ide.SecretGuard
import java.lang.ref.WeakReference

/** Activity-owned bindings; process worker callbacks keep only weak bindings and generation tickets. */
internal class WorkspaceDraftFields(private val store: DraftCoordinator) {
    private class Field(val input: EditText, val name: String, var source: String) {
        var dirty = false
        var applying = false
        var key = ""
        var loading = false
        var failed = false
        var review = false
        lateinit var watcher: TextWatcher
    }
    private val handler = Handler(Looper.getMainLooper())
    private val fields = mutableListOf<Field>()
    private val disabled = linkedMapOf<View, Boolean>()
    private var status: TextView? = null
    private var dialog: AlertDialog? = null
    private var generation = 0L
    private var boundRoot: WeakReference<View>? = null
    var ready = true
        private set
    private val capturePending = Runnable { capture(); flush() }

    fun detach() {
        handler.removeCallbacks(capturePending)
        generation++
        dialog?.dismiss(); dialog = null
        fields.forEach { it.input.removeTextChangedListener(it.watcher) }
        fields.clear(); disabled.clear(); status = null; boundRoot = null
        ready = true
    }

    fun bind(root: View, route: String, state: Bundle) {
        detach()
        val ticket = generation
        val rootReference = WeakReference(root)
        boundRoot = rootReference
        val inputs = descendants(root).filterIsInstance<EditText>().filter { it.isSaveEnabled }.toList()
        val values = state.getStringArrayList("inputs").orEmpty()
        val keys = state.getStringArrayList("draftKeys").orEmpty()
        val pending = state.getBooleanArray("draftPending") ?: booleanArrayOf()
        val review = state.getBooleanArray("draftReview") ?: booleanArrayOf()
        val selections = state.getIntArray("selection") ?: intArrayOf()
        val panel = (root as? ScrollView)?.getChildAt(0) as? LinearLayout
        if (inputs.isNotEmpty() && panel != null) {
            status = QuasarDesign.body(root.context, "Drafts save privately after editing pauses", QuasarDesign.muted, 12f)
            panel.addView(status, QuasarDesign.match(top = root.context.dp(8)))
            panel.addView(QuasarDesign.action(root.context, "Discard screen drafts") {
                AlertDialog.Builder(root.context).setTitle("Discard this screen's drafts?")
                    .setMessage("This clears the local fields. Submitted server records are unchanged.")
                    .setNegativeButton("Keep drafts", null)
                    .setPositiveButton("Discard") { _, _ ->
                        fields.forEach { field ->
                            store.discardSaved(field.name); field.key = ""; field.dirty = false
                            field.failed = false; field.review = false; field.loading = false
                            apply(field, "", 0)
                        }
                        unlock(); status?.text = "Local drafts discarded"
                    }.show()
            }, QuasarDesign.match(top = root.context.dp(4)))
        }
        inputs.forEachIndexed { index, input ->
            val name = "$route:$index"
            val initial = runCatching { DraftLimits.utf8Bytes(input.text); input.text.toString() }.getOrDefault("")
            val field = Field(input, name, initial)
            fields.add(field)
            input.filters = (input.filters.toList() + DraftInputFilter { input.error = "Draft limit is 256 KiB; paste was not inserted" } +
                InputFilter { source, start, end, dest, dstart, dend ->
                    val length = dest.length.toLong() - (dend - dstart) + end - start
                    if (field.applying || (length <= Int.MAX_VALUE && store.canAcceptLength(name, length.toInt()))) null
                    else { input.error = "Open draft limit reached; save or discard a draft"; dest.subSequence(dstart, dend) }
                }).toTypedArray<InputFilter>()
            field.watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!field.applying) {
                        field.dirty = true
                        status?.text = "Draft save pending"
                        handler.removeCallbacks(capturePending); handler.postDelayed(capturePending, 300)
                    }
                }
            }
            input.addTextChangedListener(field.watcher)
            field.key = keys.getOrNull(index).orEmpty()
            field.review = review.getOrNull(index) == true
            val local = store.current(name)
            if (local != null) apply(field, local, selections.getOrNull(index))
            else if (field.key.isEmpty()) values.getOrNull(index)?.let { apply(field, it, selections.getOrNull(index)) }
            else {
                field.loading = true
                field.review = field.review || pending.getOrNull(index) == true
                val weak = WeakReference(this)
                runCatching { store.restoreInto(name, field.key) }.onSuccess { future ->
                    future.whenComplete { source, error ->
                        Handler(Looper.getMainLooper()).post {
                            val binding = weak.get() ?: return@post
                            if (binding.generation != ticket) return@post
                            val current = binding.fields.getOrNull(index) ?: return@post
                            current.loading = false
                            current.failed = error != null
                            if (error == null) binding.apply(current, source, selections.getOrNull(index))
                            rootReference.get()?.let(binding::finishRestore)
                        }
                    }
                }.onFailure { field.loading = false; field.failed = true }
            }
        }
        finishRestore(root)
    }

    private fun apply(field: Field, source: String, selection: Int?) {
        field.applying = true
        try {
            DraftLimits.utf8Bytes(source)
            field.input.setText(source)
            check(field.input.text.toString() == source) { "Restored source rejected by field filter" }
            field.input.setSelection((selection ?: source.length).coerceIn(0, source.length))
            field.source = source
        } catch (_: Exception) { field.failed = true }
        finally { field.applying = false }
    }

    private fun finishRestore(root: View) {
        if (fields.any { it.loading || it.failed || it.review }) {
            ready = false
            descendants(root).filter { it.isClickable || it is EditText }.forEach {
                if (it !in disabled) disabled[it] = it.isEnabled
                it.isEnabled = false
            }
            status?.text = if (fields.any { it.loading }) "Restoring saved drafts…" else "Draft restoration needs your review"
            if (fields.any { it.loading } || dialog != null) return
            val failed = fields.any { it.failed }
            dialog = AlertDialog.Builder(root.context).setTitle(if (failed) "Draft could not be restored" else "Review restored drafts")
                .setMessage(if (failed) "A saved draft is missing or corrupt. Reopen your source or explicitly discard unavailable drafts before submitting."
                    else "A save was pending at shutdown. This is the last saved version; review the source before submitting.")
                .setCancelable(false)
                .setPositiveButton(if (failed) "Discard unavailable drafts" else "Review source") { _, _ ->
                    fields.forEach { field ->
                        if (field.failed) {
                            store.discardSaved(field.name); field.key = ""; field.failed = false
                            apply(field, "", 0)
                        }
                        field.review = false
                    }
                    dialog = null; unlock()
                }.create().also { it.show() }
        } else unlock()
    }

    private fun unlock() {
        ready = fields.none { it.loading || it.failed || it.review }
        if (ready) { disabled.forEach { (view, enabled) -> view.isEnabled = enabled }; disabled.clear() }
    }

    private fun capture() {
        handler.removeCallbacks(capturePending)
        if (!ready) return
        fields.filter { it.dirty }.forEach { field ->
            runCatching {
                store.update(field.name, field.input.text)
                field.source = checkNotNull(store.current(field.name))
                field.key = DraftReference.key(field.name)
                field.dirty = false
            }.onFailure { field.input.error = "Draft capture failed; keep this editor open" }
        }
    }

    fun saveInto(state: Bundle): Boolean {
        if (boundRoot?.get() == null) return true
        capture()
        if (fields.any { it.dirty }) return false
        if (!ready) {
            state.putBooleanArray("draftReview", fields.map { it.review || it.failed }.toBooleanArray())
            return true // Retain original references while restore is pending or failed.
        }
        var inlineRemaining = 4096
        val keys = arrayListOf<String>()
        val values = arrayListOf<String>()
        val pending = mutableListOf<Boolean>()
        fields.forEach { field ->
            val source = field.source
            val inline = source.length <= inlineRemaining && !SecretGuard.containsLikelySecret(source)
            if (inline) { inlineRemaining -= source.length; values.add(source); keys.add("") }
            else {
                if (field.key.isEmpty()) runCatching { store.update(field.name, source) }
                    .onSuccess { field.key = DraftReference.key(field.name) }
                    .onFailure { field.input.error = "Draft capture failed; keep this editor open" }
                values.add(""); keys.add(field.key.ifEmpty { DraftReference.key(field.name) })
            }
            pending.add(field.dirty || store.isDirty(field.name))
        }
        state.putStringArrayList("inputs", values)
        state.putStringArrayList("draftKeys", keys)
        state.putBooleanArray("draftPending", pending.toBooleanArray())
        state.putBooleanArray("draftReview", fields.map { it.review }.toBooleanArray())
        state.putIntArray("selection", fields.map { it.input.selectionStart.coerceAtLeast(0) }.toIntArray())
        flush()
        return true
    }

    fun flush() {
        val ticket = generation
        val weak = WeakReference(this)
        store.flush().whenComplete { _, error ->
            Handler(Looper.getMainLooper()).post {
                val binding = weak.get() ?: return@post
                if (binding.generation != ticket) return@post
                binding.status?.text = if (error != null) "Draft save failed; keep the editor open and retry."
                    else if (binding.fields.any { it.dirty || binding.store.isDirty(it.name) }) "Draft save pending" else "Draft saved privately"
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
