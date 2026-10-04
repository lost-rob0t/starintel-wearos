package actor.starintel.quasar

internal enum class MobileSurface(val label: String) {
    STATS("Stats"),
    GRAPHS("Graphs"),
    DATASETS("Datasets"),
    DOCUMENTS("Documents"),
    ADD_DOCUMENT("Add document"),
    AGENTS("Agents"),
    ACTORS("Actors"),
    IMPORT("Import"),
    TARGETS("Targets"),
    SETTINGS("Settings"),
}

internal fun routeColumnCount(screenWidthDp: Int): Int = if (screenWidthDp >= 600) 2 else 1
