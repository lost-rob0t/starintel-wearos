package actor.starintel.android;

/**
 * Stable Android-only interop contract.
 *
 * This coordinates independently installable StarIntel APKs. It is not a
 * replacement for the Star server, Quasar control-plane, JSON-LD document, or
 * star:// protocol authorities.
 */
public final class StarIntelAndroidContract {
    private StarIntelAndroidContract() {}

    public static final int VERSION = 1;

    public static final String PACKAGE_OPERATOR = "actor.starintel.operator";
    public static final String PACKAGE_COLLECTOR = "actor.starintel.collector";
    public static final String PACKAGE_MAPS = "actor.starintel.maps";
    public static final String PACKAGE_QUASAR = "actor.starintel.quasar";
    public static final String PACKAGE_COMPANION = "actor.starintel.wear";

    public static final String ACTION_OPEN_MAP = "actor.starintel.action.OPEN_MAP";
    public static final String ACTION_COLLECT = "actor.starintel.action.COLLECT";

    public static final String EXTRA_DOCUMENT_ID = "actor.starintel.extra.DOCUMENT_ID";
    public static final String EXTRA_LABEL = "actor.starintel.extra.LABEL";
    public static final String EXTRA_LATITUDE = "actor.starintel.extra.LATITUDE";
    public static final String EXTRA_LONGITUDE = "actor.starintel.extra.LONGITUDE";
    public static final String EXTRA_GEO_JSON = "actor.starintel.extra.GEO_JSON";
}
