package actor.starintel.collector;

final class CollectorMissionSnapshot {
    enum Stage { COLLECT, PROCESS, SYNC }

    final boolean active;
    final boolean audioActive;
    final long networks;
    final long observations;
    final long captures;
    final long queued;
    final long accepted;
    final Stage stage;

    CollectorMissionSnapshot(
            boolean active,
            boolean audioActive,
            long networks,
            long observations,
            long captures,
            long queued,
            long accepted) {
        this.active = active;
        this.audioActive = audioActive;
        this.networks = nonNegative(networks);
        this.observations = nonNegative(observations);
        this.captures = nonNegative(captures);
        this.queued = nonNegative(queued);
        this.accepted = nonNegative(accepted);
        this.stage = deriveStage(active, this.captures, this.queued, this.accepted);
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
