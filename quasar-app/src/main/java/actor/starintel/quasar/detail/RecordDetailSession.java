package actor.starintel.quasar.detail;

import org.json.JSONObject;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One record's lazy expansion. Calls and UI delivery are serialized on the UI executor.
 * Close when the detail container detaches; collapse cancels queued/running work.
 * Nothing is serialized in the constructor. A request replaces the previous expansion.
 */
public final class RecordDetailSession implements AutoCloseable {
    private final JSONObject root;
    private final JSONObject data;
    private final Executor ui;
    private final ThreadPoolExecutor worker;
    private final AtomicLong generation = new AtomicLong();
    private Future<?> pending;
    private boolean closed;

    public RecordDetailSession(JSONObject root, JSONObject data, Executor ui) {
        this(root, data, ui, new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "quasar-detail-preview");
                thread.setDaemon(true);
                return thread;
            }));
        worker.allowCoreThreadTimeOut(true);
    }

    RecordDetailSession(JSONObject root, JSONObject data, Executor ui, ThreadPoolExecutor worker) {
        this.root = root; this.data = data; this.ui = ui; this.worker = worker;
    }

    public synchronized void request(boolean raw, Consumer<RecordDetailPreview.Result> success,
                                     Consumer<RuntimeException> failure) {
        cancel();
        if (closed) return;
        long token = generation.get();
        pending = worker.submit(() -> {
            try {
                RecordDetailPreview.Result result = raw
                    ? RecordDetailPreview.raw(root, () -> generation.get() != token)
                    : RecordDetailPreview.fields(data, () -> generation.get() != token);
                ui.execute(() -> { if (generation.get() == token) success.accept(result); });
            } catch (CancellationException ignored) {
                // Cancellation and supersession are not user-visible failures.
            } catch (RuntimeException error) {
                ui.execute(() -> { if (generation.get() == token) failure.accept(error); });
            }
        });
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        pending = null;
        worker.purge();
    }

    @Override public synchronized void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
    }
}
