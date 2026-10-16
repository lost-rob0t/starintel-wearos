package actor.starintel.quasar

import android.os.Bundle

/** Small route/view state only. Corpus payloads and credentials never enter saved state. */
internal class WorkspaceState(saved: Bundle? = null) {
    var route: String = saved?.getString("route") ?: "home"
    val history = saved?.getStringArrayList("history") ?: arrayListOf()
    val views: Bundle = saved?.getBundle("views") ?: Bundle()
    fun enter(next: String) {
        if (next == route) return
        history.add(route)
        while (history.size > 24) history.removeAt(0)
        route = next
    }
    fun back(): String? = if (history.isEmpty()) null else history.removeAt(history.lastIndex).also { route = it }
    fun save() = Bundle().apply {
        putString("route", route); putStringArrayList("history", history); putBundle("views", views)
    }
}
