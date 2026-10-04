package actor.starintel.android.config;

/**
 * Cross-app contracts the Operator hub uses to route data to the right viewer.
 *
 * Actions are explicit, bounded, and signature-scoped by the receiver's permissions.
 * Every action carries the minimum payload needed to open the data; receivers must
 * degrade to a plain launch when extras are absent.
 */
public final class OperatorContracts {
    /** Open the Collector mission surface. Extra: {@code kind} = audio|photo (optional). */
    public static final String ACTION_COLLECT = "actor.starintel.action.COLLECT";
    public static final String EXTRA_COLLECT_KIND = "actor.starintel.extra.COLLECT_KIND";

    /** Open one StarIntel document in Quasar. Extra: {@code doc_id}; optional {@code dataset}. */
    public static final String ACTION_OPEN_DOCUMENT = "actor.starintel.action.OPEN_DOCUMENT";
    public static final String EXTRA_DOCUMENT_ID = "actor.starintel.extra.DOC_ID";
    public static final String EXTRA_DATASET = "actor.starintel.extra.DATASET";

    /** Open the Quasar field map focused on a point. Extras: {@code lat}, {@code lon}, {@code label}. */
    public static final String ACTION_OPEN_MAP = "actor.starintel.action.OPEN_MAP";
    public static final String EXTRA_LATITUDE = "actor.starintel.extra.LAT";
    public static final String EXTRA_LONGITUDE = "actor.starintel.extra.LON";
    public static final String EXTRA_LABEL = "actor.starintel.extra.LABEL";

    public static final String PACKAGE_QUASAR = "actor.starintel.quasar";
    public static final String PACKAGE_COLLECTOR = "actor.starintel.collector";
    public static final String PACKAGE_HACKMODE = "actor.starintel.hackmode";
    public static final String PACKAGE_COMPANION = "actor.starintel.wear";
    public static final String PACKAGE_OPERATOR = "actor.starintel.operator";

    public static final String KIND_AUDIO = "audio";
    public static final String KIND_PHOTO = "photo";

    private OperatorContracts() {}

    public static boolean validLatitude(double value) {
        return Double.isFinite(value) && value >= -90.0d && value <= 90.0d;
    }

    public static boolean validLongitude(double value) {
        return Double.isFinite(value) && value >= -180.0d && value <= 180.0d;
    }

    public static String normalizeDocumentId(String value) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty() || clean.length() > 256) return "";
        return clean;
    }
}
