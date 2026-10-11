package actor.starintel.quasar.workflows

/** A recent cached observation permits enqueueing; it never proves current connectivity. */
object WorkflowSnapshotPolicy {
    const val MAX_AGE_MILLIS = 5 * 60 * 1000L

    enum class Freshness { MISSING, FUTURE, STALE, RECENT }

    fun freshness(state: WorkflowWidgetState, now: Long): Freshness {
        val observed = state.updatedAtEpochMillis ?: return Freshness.MISSING
        if (observed > now) return Freshness.FUTURE
        return if (now - observed > MAX_AGE_MILLIS) Freshness.STALE else Freshness.RECENT
    }

    fun canRequest(state: WorkflowWidgetState, now: Long): Boolean =
        state.connectivity == WorkflowConnectivity.ONLINE && freshness(state, now) == Freshness.RECENT
}
