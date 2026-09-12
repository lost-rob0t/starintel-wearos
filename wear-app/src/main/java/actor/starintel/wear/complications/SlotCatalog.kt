package actor.starintel.wear.complications

/** Stable data types the WFF v1 StarIntel slot framework accepts. */
enum class SlotDataType {
    SHORT_TEXT,
    LONG_TEXT,
    RANGED_VALUE,
    SMALL_IMAGE,
    EMPTY,
}

enum class SlotFamily {
    CIRCULAR,
    CURVED,
    TEXT,
    PANEL,
}

data class SlotContract(
    val id: Int,
    val key: String,
    val family: SlotFamily,
    val supportedTypes: Set<SlotDataType>,
)

enum class SlotVisualState {
    BLANK,
    CONFIGURED,
}

/**
 * Shared slot ABI between the WFF resource, providers, companion configuration,
 * and tests. IDs are stable because Wear OS persists provider assignments by ID.
 *
 * WFF v1 caps a face at eight slots, so the three Astra faces intentionally
 * reuse this catalog instead of manufacturing per-face IDs.
 */
object SlotCatalog {
    val lowerLeft = SlotContract(
        id = 1,
        key = "lower_left",
        family = SlotFamily.CIRCULAR,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )
    val lowerCenter = SlotContract(
        id = 2,
        key = "lower_center",
        family = SlotFamily.CIRCULAR,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )
    val lowerRight = SlotContract(
        id = 3,
        key = "lower_right",
        family = SlotFamily.CIRCULAR,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )
    val leftEdge = SlotContract(
        id = 4,
        key = "left_edge",
        family = SlotFamily.CURVED,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )
    val rightEdge = SlotContract(
        id = 5,
        key = "right_edge",
        family = SlotFamily.CURVED,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )

    /** Historical key retained for companion/config compatibility; WFF #50 made it the fourth lower slot. */
    val textRegion = SlotContract(
        id = 6,
        key = "text_region",
        family = SlotFamily.CIRCULAR,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.RANGED_VALUE, SlotDataType.EMPTY),
    )

    val activityGraph = SlotContract(
        id = 7,
        key = "activity_graph",
        family = SlotFamily.PANEL,
        supportedTypes = setOf(SlotDataType.SMALL_IMAGE, SlotDataType.EMPTY),
    )

    val weatherGeo = SlotContract(
        id = 8,
        key = "weather_geo",
        family = SlotFamily.PANEL,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.SMALL_IMAGE, SlotDataType.EMPTY),
    )

    val all: List<SlotContract> = listOf(
        lowerLeft,
        lowerCenter,
        lowerRight,
        leftEdge,
        rightEdge,
        textRegion,
        activityGraph,
        weatherGeo,
    )

    init {
        require(all.map { it.id }.distinct().size == all.size) { "slot IDs must be unique" }
        require(all.map { it.key }.distinct().size == all.size) { "slot keys must be unique" }
        require(all.all { SlotDataType.EMPTY in it.supportedTypes }) { "every slot must support EMPTY" }
    }

    fun byId(id: Int): SlotContract? = all.firstOrNull { it.id == id }

    /**
     * A slot without an assigned and currently available provider is visually
     * blank. Callers must never substitute sample values or decorative filler.
     */
    fun visualState(providerAssigned: Boolean, providerAvailable: Boolean): SlotVisualState =
        if (providerAssigned && providerAvailable) SlotVisualState.CONFIGURED else SlotVisualState.BLANK
}
