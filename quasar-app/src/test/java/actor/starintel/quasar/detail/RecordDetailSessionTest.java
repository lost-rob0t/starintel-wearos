package actor.starintel.quasar.detail;

import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RecordDetailSessionTest {
    private static ThreadPoolExecutor worker() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    }

    @Test public void collapsedSessionDoesNoTraversalOrSerialization() {
        JSONObject root = new RecordDetailPreviewTest.NoSerialization() {
            @Override public java.util.Iterator<String> keys() { throw new AssertionError("Collapsed traversal"); }
        };
        ThreadPoolExecutor executor = worker();
        try (RecordDetailSession session = new RecordDetailSession(root, root, Runnable::run, executor)) {
            assertEquals(0, executor.getTaskCount());
        }
    }

    @Test public void delayedUiResultIsRejectedAfterSelectionChange() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicInteger displayed = new AtomicInteger();
        JSONObject root = new JSONObject().put("name", "old");
        try (RecordDetailSession session = new RecordDetailSession(root, root, ui::add)) {
            session.request(true, r -> displayed.incrementAndGet(), e -> { throw new AssertionError(e); });
            Runnable stale = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(stale);
            session.cancel();
            stale.run();
            assertEquals(0, displayed.get());
            session.request(false, r -> displayed.incrementAndGet(), e -> { throw new AssertionError(e); });
            Runnable current = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(current);
            current.run();
            assertEquals(1, displayed.get());
        }
    }

    @Test public void closeSuppressesQueuedDeliveryAndFurtherWork() throws Exception {
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicInteger displayed = new AtomicInteger();
        ThreadPoolExecutor executor = worker();
        JSONObject root = new JSONObject();
        RecordDetailSession session = new RecordDetailSession(root, root, ui::add, executor);
        try {
            session.request(true, r -> displayed.incrementAndGet(), e -> { throw new AssertionError(e); });
            Runnable stale = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(stale);
            session.close();
            stale.run();
            session.request(true, r -> displayed.incrementAndGet(), e -> { throw new AssertionError(e); });
            assertEquals(0, displayed.get());
            assertTrue(executor.isShutdown());
        } finally { session.close(); }
    }

    @Test public void rapidRequestsKeepOnlyOnePendingTask() throws Exception {
        ThreadPoolExecutor executor = worker();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        AtomicInteger displayed = new AtomicInteger();
        JSONObject root = new JSONObject();
        try (RecordDetailSession session = new RecordDetailSession(root, root, ui::add, executor)) {
            for (int i = 0; i < 1000; i++) {
                session.request(i % 2 == 0, r -> displayed.incrementAndGet(), e -> { throw new AssertionError(e); });
                assertTrue(executor.getQueue().size() <= 1);
            }
            release.countDown();
            Runnable delivery = ui.poll(5, TimeUnit.SECONDS);
            assertNotNull(delivery);
            delivery.run();
            assertEquals(1, displayed.get());
        } finally { release.countDown(); }
    }

    @Test public void serializationRunsOffCallingThread() throws Exception {
        Thread caller = Thread.currentThread();
        JSONObject root = new JSONObject() {
            @Override public java.util.Iterator<String> keys() {
                assertNotSame(caller, Thread.currentThread());
                return super.keys();
            }
        };
        BlockingQueue<Runnable> ui = new LinkedBlockingQueue<>();
        try (RecordDetailSession session = new RecordDetailSession(root, root, ui::add)) {
            session.request(true, r -> {}, e -> { throw new AssertionError(e); });
            assertNotNull(ui.poll(5, TimeUnit.SECONDS));
        }
    }
}
