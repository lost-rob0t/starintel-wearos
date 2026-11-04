package actor.starintel.quasar

/**
 * Curated knowledge about named server actors so the dispatch form can offer
 * honest hints without fabricating server capability. The live registry
 * remains authoritative; this catalog only describes actors we know by name.
 */
internal data class KnownActor(
    val name: String,
    val kind: String,
    val accepts: List<String>,
    val description: String,
    val monitor: Boolean,
)

internal object ActorCatalog {
    val watchlist = KnownActor(
        name = "watchlist",
        kind = "monitor",
        accepts = listOf("message", "social-media-post"),
        description = "Watches ingested message and social-media-post documents against " +
            "server-side watch-word and target-author rules and files alert documents for matches. " +
            "It runs continuously on the server; dispatching a target to it is not supported.",
        monitor = true,
    )

    val all: List<KnownActor> = listOf(watchlist)

    fun byName(name: String): KnownActor? =
        all.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun isMonitor(name: String): Boolean = byName(name)?.monitor == true

    /** One-line hint for the TARGET field of a dispatch against [actorName]. */
    fun targetHint(actorName: String, accepts: List<String>): String {
        val known = byName(actorName)
        if (known != null && known.monitor) {
            return "${known.name} is a monitor: it runs continuously on the server and dispatch is not supported."
        }
        val contract = known?.accepts ?: accepts.filter { it.isNotBlank() }.take(8)
        return if (contract.isEmpty()) {
            "Paste the identifier, URL, or query this actor should work on."
        } else {
            "Accepts ${contract.joinToString(", ")}. Paste the ${contract.first()} identifier, URL, or query it should work on."
        }
    }
}
