package actor.starintel.collector;

import java.util.Arrays;

final class CollectorMissionSnapshot {
    enum Stage { COLLECT, PROCESS, SYNC }

    static final int MAX_ROUTE_POINTS = 96;

    final boolean active;
    final boolean audioActive;
    final long networks;
    final long observations;
    final long captures;
    final long queued;
    final long accepted;
    final Stage stage;
    final double[] routeLatitudes;
    final double[] routeLongitudes;

    CollectorMissionSnapshot(
            boolean active,
            boolean audioActive,
            long networks,
            long observations,
            long captures,
            long queued,
            long accepted) {
        this(active, audioActive, networks, observations, captures, queued, accepted,
                new double[0], new double[0]);
    }

    CollectorMissionSnapshot(
            boolean active,
            boolean audioActive,
            long networks,
            long observations,
            long captures,
            long queued,
            long accepted,
            double[] routeLatitudes,
            double[] routeLongitudes) {
        this.active = active;
        this.audioActive = audioActive;
        this.networks = nonNegative(networks);
        this.observations = nonNegative(observations);
        this.captures = nonNegative(captures);
        this.queued = nonNegative(queued);
        this.accepted = nonNegative(accepted);
        this.stage = deriveStage(active, this.captures, this.queued, this.accepted);
        int points = Math.min(Math.min(routeLatitudes.length, routeLongitudes.length), MAX_ROUTE_POINTS);
        this.routeLatitudes = Arrays.copyOf(routeLatitudes, points);
        this.routeLongitudes = Arrays.copyOf(routeLongitudes, points);
    }

    /** Loads the mission state plus the most recent bounded route window. */
    static CollectorMissionSnapshot fromStore(boolean active, boolean audioActive, StarWirelessStore store) {
        return new CollectorMissionSnapshot(
                active,
                audioActive,
                store.networkCount(),
                store.observationCount(),
                store.captureCount(),
                store.queuedCount(),
                store.acceptedCount(),
                store.recentRouteLatitudes(MAX_ROUTE_POINTS),
                store.recentRouteLongitudes(MAX_ROUTE_POINTS));
    }

    double[] routeLatitudes() {
        return routeLatitudes;
    }

    double[] routeLongitudes() {
        return routeLongitudes;
    }

    String networkSummary() {
        return networks == 0 ? "NO NETWORKS YET" : networks + " NETWORKS SEEN";
    }

    String headline() {
        if (active && audioActive) return "COLLECTING · FULL SENSOR SET";
        if (active) return "COLLECTING · WIRELESS + LOCATION";
        if (queued > 0) return "READY · DOCUMENTS WAITING";
        return "READY TO COLLECT";
    }

    String primaryAction() {
        return active ? "STOP SESSION" : "START MISSION";
    }

    static Stage deriveStage(boolean active, long captures, long queued, long accepted) {
        if (active || captures == 0) return Stage.COLLECT;
        if (queued > 0 || accepted == 0) return Stage.PROCESS;
        return Stage.SYNC;
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }
}
