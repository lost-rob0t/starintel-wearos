package actor.starintel.wear.complications

/** Stable data types the WFF v1 StarIntel slot framework accepts. */
enum class SlotDataType {
    SHORT_TEXT,
    LONG_TEXT,
    RANGED_VALUE,
    EMPTY,
}

enum class SlotFamily {
    CIRCULAR,
    CURVED,
    TEXT,
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
 * and tests. Existing IDs 1..3 are intentionally preserved.
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
    val textRegion = SlotContract(
        id = 6,
        key = "text_region",
        family = SlotFamily.TEXT,
        supportedTypes = setOf(SlotDataType.SHORT_TEXT, SlotDataType.LONG_TEXT, SlotDataType.EMPTY),
    )

    val all: List<SlotContract> = listOf(
        lowerLeft,
        lowerCenter,
        lowerRight,
        leftEdge,
        rightEdge,
        textRegion,
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
