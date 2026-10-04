package actor.starintel.collector;

/** Small explicit lifecycle used by the service and unit tests. */
final class AudioRecordingState {
    enum State { IDLE, STARTING, RECORDING, STOPPING, ERROR }

    private State state = State.IDLE;

    synchronized boolean requestStart(boolean permissionGranted) {
        if (!permissionGranted) {
            state = State.ERROR;
            return false;
        }
        if (state == State.STARTING || state == State.RECORDING) return false;
        state = State.STARTING;
        return true;
    }

    synchronized void started() {
        if (state != State.STARTING) throw new IllegalStateException("audio was not starting");
        state = State.RECORDING;
    }

    synchronized boolean requestStop() {
        if (state == State.IDLE || state == State.STOPPING) return false;
        state = State.STOPPING;
        return true;
    }

    synchronized void stopped() {
        state = State.IDLE;
    }

    synchronized void failed() {
        state = State.ERROR;
    }

    synchronized State current() {
        return state;
    }
}
