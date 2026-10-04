package actor.starintel.collector;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CollectorMissionSnapshotTest {
    @Test
    public void activeAudioSessionIsFullCollection() {
        CollectorMissionSnapshot snapshot = new CollectorMissionSnapshot(true, true, 10, 20, 2, 0, 0);
        assertEquals("COLLECTING · FULL SENSOR SET", snapshot.headline());
        assertEquals("STOP SESSION", snapshot.primaryAction());
        assertEquals(CollectorMissionSnapshot.Stage.COLLECT, snapshot.stage);
    }

    @Test
    public void pendingDocumentsAdvanceToProcessing() {
        CollectorMissionSnapshot snapshot = new CollectorMissionSnapshot(false, false, 10, 20, 2, 4, 0);
        assertEquals("READY · DOCUMENTS WAITING", snapshot.headline());
        assertEquals(CollectorMissionSnapshot.Stage.PROCESS, snapshot.stage);
    }

    @Test
    public void acceptedDocumentsAdvanceToSync() {
        CollectorMissionSnapshot snapshot = new CollectorMissionSnapshot(false, false, 10, 20, 2, 0, 4);
        assertEquals(CollectorMissionSnapshot.Stage.SYNC, snapshot.stage);
    }

    @Test
    public void countersClampAtZero() {
        CollectorMissionSnapshot snapshot = new CollectorMissionSnapshot(false, false, -1, -2, -3, -4, -5);
        assertEquals(0, snapshot.networks);
        assertEquals(0, snapshot.observations);
        assertEquals(0, snapshot.captures);
    }
}
