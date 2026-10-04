package actor.starintel.collector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioRecordingStateTest {
    @Test
    public void startAndStopAreExplicitAndIdempotent() {
        AudioRecordingState state = new AudioRecordingState();
        assertTrue(state.requestStart(true));
        assertEquals(AudioRecordingState.State.STARTING, state.current());
        state.started();
        assertEquals(AudioRecordingState.State.RECORDING, state.current());
        assertFalse(state.requestStart(true));
        assertTrue(state.requestStop());
        assertFalse(state.requestStop());
        state.stopped();
        assertEquals(AudioRecordingState.State.IDLE, state.current());
    }

    @Test
    public void permissionDenialNeverEntersRecording() {
        AudioRecordingState state = new AudioRecordingState();
        assertFalse(state.requestStart(false));
        assertEquals(AudioRecordingState.State.ERROR, state.current());
        assertTrue(state.requestStop());
        state.stopped();
        assertEquals(AudioRecordingState.State.IDLE, state.current());
    }

    @Test
    public void failedRecorderCanBeRetriedAfterStopRecovery() {
        AudioRecordingState state = new AudioRecordingState();
        assertTrue(state.requestStart(true));
        state.failed();
        assertTrue(state.requestStop());
        state.stopped();
        assertTrue(state.requestStart(true));
    }
}
