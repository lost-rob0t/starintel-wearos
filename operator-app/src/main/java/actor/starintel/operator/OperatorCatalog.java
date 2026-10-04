package actor.starintel.operator;

import actor.starintel.android.config.OperatorContracts;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure model of the Operator hub: which apps exist, what data routes where, and the
 * exact intent each feature row fires. Unit-testable without Android.
 */
final class OperatorCatalog {
    enum Availability { INSTALLED, MISSING }

    static final class Feature {
        final String label;
        final String description;
        final String targetPackage;
        final String action;
        final String collectKind;
        final boolean needsGeoPayload;

        Feature(String label, String description, String targetPackage, String action,
                String collectKind, boolean needsGeoPayload) {
            this.label = label;
            this.description = description;
            this.targetPackage = targetPackage;
            this.action = action;
            this.collectKind = collectKind;
            this.needsGeoPayload = needsGeoPayload;
        }
    }

    static final class AppSection {
        final String packageName;
        final String name;
        final String role;
        final List<Feature> features;

        AppSection(String packageName, String name, String role, List<Feature> features) {
            this.packageName = packageName;
            this.name = name;
            this.role = role;
            this.features = List.copyOf(features);
        }
    }

    private OperatorCatalog() {}

    static List<AppSection> sections() {
        List<AppSection> sections = new ArrayList<>();
        sections.add(new AppSection(
                OperatorContracts.PACKAGE_QUASAR,
                "Quasar",
                "Corpus · maps · graphs · agents",
                List.of(
                        new Feature("Open corpus", "Browse datasets and documents", OperatorContracts.PACKAGE_QUASAR, null, null, false),
                        new Feature("Field map", "Geo documents around the last fix", OperatorContracts.PACKAGE_QUASAR, OperatorContracts.ACTION_OPEN_MAP, null, true),
                        new Feature("Latest document", "Open the newest captured document", OperatorContracts.PACKAGE_QUASAR, OperatorContracts.ACTION_OPEN_DOCUMENT, null, false))));
        sections.add(new AppSection(
                OperatorContracts.PACKAGE_COLLECTOR,
                "Collector",
                "Wireless · audio · photo capture",
                List.of(
                        new Feature("Mission", "Start or read the collection session", OperatorContracts.PACKAGE_COLLECTOR, OperatorContracts.ACTION_COLLECT, null, false),
                        new Feature("Voice segment", "Record an analyzed audio capture", OperatorContracts.PACKAGE_COLLECTOR, OperatorContracts.ACTION_COLLECT, OperatorContracts.KIND_AUDIO, false),
                        new Feature("Photo", "Capture an analyzed frame", OperatorContracts.PACKAGE_COLLECTOR, OperatorContracts.ACTION_COLLECT, OperatorContracts.KIND_PHOTO, false))));
        sections.add(new AppSection(
                OperatorContracts.PACKAGE_HACKMODE,
                "Hackmode",
                "Typed operations · Kali/Termux runtime",
                List.of(
                        new Feature("Open console", "Launch the Hackmode control surface", OperatorContracts.PACKAGE_HACKMODE, null, null, false))));
        sections.add(new AppSection(
                OperatorContracts.PACKAGE_COMPANION,
                "Companion",
                "Phone + watch package catalog",
                List.of(
                        new Feature("Watch packages", "Install and update watch packages", OperatorContracts.PACKAGE_COMPANION, null, null, false))));
        return sections;
    }

    static Availability availability(String packageName, boolean launchable) {
        return launchable ? Availability.INSTALLED : Availability.MISSING;
    }
}
