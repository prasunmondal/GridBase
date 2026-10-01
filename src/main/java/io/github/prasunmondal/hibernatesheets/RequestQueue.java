package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.HibernateSheets.Reply;
import io.github.prasunmondal.hibernatesheets.exception.ServerException;
import io.github.prasunmondal.hibernatesheets.spec.Operation;
import io.github.prasunmondal.hibernatesheets.spec.OperationType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Collects requests for up to {@code window} and sends them to the engine as one HTTP call.
 *
 * <p>Combined calls are sent one at a time, in submission order, from a single daemon thread, so the
 * engine processes queued requests in the order they were made. While a call is in flight, new
 * requests keep accumulating for the next one.</p>
 */
final class RequestQueue {

    private static final System.Logger LOG = System.getLogger(RequestQueue.class.getName());

    interface Sender {
        Reply send(List<Operation> operations, boolean readOnly);
    }

    interface Slicer {
        Reply slice(Reply combined, int offset, List<Operation> operations);
    }

    private record Pending(List<Operation> operations, boolean readOnly, CompletableFuture<Reply> future) {
    }

    private final Duration window;
    private final int maxOperations;
    private final Sender sender;
    private final Slicer slicer;
    private final Executor callbacks;
    private final ScheduledExecutorService worker;
    private volatile Thread workerThread;

    private final Deque<Pending> pending = new ArrayDeque<>();
    private int pendingOperations;
    private boolean flushScheduled;

    RequestQueue(Duration window, int maxOperations, Sender sender, Slicer slicer, Executor callbacks) {
        if (window.isNegative()) {
            throw new IllegalArgumentException("window must not be negative");
        }
        if (maxOperations < 1) {
            throw new IllegalArgumentException("maxOperations must be >= 1");
        }
        this.window = window;
        this.maxOperations = maxOperations;
        this.sender = Objects.requireNonNull(sender);
        this.slicer = Objects.requireNonNull(slicer);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hibernate-sheets-queue");
            t.setDaemon(true);
            workerThread = t;
            return t;
        });
    }

    /**
     * Schema operations are applied by the engine immediately, not at commit, so a combined call that
     * fails could not be safely re-sent per request; they always go alone. So do oversized requests.
     */
    boolean accepts(List<Operation> operations) {
        return operations.size() <= maxOperations
                && !Thread.currentThread().equals(workerThread)
                && operations.stream().noneMatch(op -> op.type() == OperationType.CREATE_WORKSHEET
                || op.type() == OperationType.CLEAR_WORKSHEET || op.type() == OperationType.ADD_COLUMNS);
    }

    /** The returned future completes on a callback thread, never on the queue thread. */
    CompletableFuture<Reply> submit(List<Operation> operations, boolean readOnly) {
        Pending p = new Pending(List.copyOf(operations), readOnly, new CompletableFuture<>());
        synchronized (this) {
            pending.add(p);
            pendingOperations += p.operations().size();
            if (pendingOperations >= maxOperations) {
                flushScheduled = true;
                worker.execute(this::flush);
            } else if (!flushScheduled) {
                flushScheduled = true;
                worker.schedule(this::flush, window.toNanos(), TimeUnit.NANOSECONDS);
            }
        }
        return p.future().whenCompleteAsync((reply, failure) -> { }, callbacks);
    }

    private void flush() {
        List<Pending> chunk = new ArrayList<>();
        synchronized (this) {
            int ops = 0;
            while (!pending.isEmpty()
                    && (chunk.isEmpty() || ops + pending.peek().operations().size() <= maxOperations)) {
                Pending p = pending.poll();
                chunk.add(p);
                ops += p.operations().size();
            }
            pendingOperations -= ops;
            flushScheduled = !pending.isEmpty();
            if (flushScheduled) {
                worker.execute(this::flush);
            }
        }
        if (!chunk.isEmpty()) {
            try {
                dispatch(chunk);
            } catch (Throwable t) {
                chunk.forEach(p -> p.future().completeExceptionally(t));
            }
        }
    }

    private void dispatch(List<Pending> chunk) {
        if (chunk.size() == 1) {
            Pending p = chunk.get(0);
            complete(p, () -> sender.send(p.operations(), p.readOnly()));
            return;
        }
        List<Operation> all = new ArrayList<>();
        chunk.forEach(p -> all.addAll(p.operations()));
        boolean readOnly = chunk.stream().allMatch(Pending::readOnly);
        LOG.log(System.Logger.Level.DEBUG, () -> "hibernate.sheets sending " + chunk.size()
                + " queued requests (" + all.size() + " operations) as one call");

        Reply combined;
        try {
            combined = sender.send(all, readOnly);
        } catch (ServerException e) {
            if (e.isRetryable()) {
                chunk.forEach(p -> p.future().completeExceptionally(e));
                return;
            }
            // The engine rejected the combined request and wrote no row changes. Re-send each request
            // alone so one caller's bad operation fails only that caller.
            LOG.log(System.Logger.Level.DEBUG, () -> "hibernate.sheets combined call rejected ("
                    + e.getMessage() + "); re-sending " + chunk.size() + " requests individually");
            chunk.forEach(p -> complete(p, () -> sender.send(p.operations(), p.readOnly())));
            return;
        }

        int offset = 0;
        for (Pending p : chunk) {
            int from = offset;
            complete(p, () -> slicer.slice(combined, from, p.operations()));
            offset += p.operations().size();
        }
    }

    private static void complete(Pending p, Supplier<Reply> work) {
        try {
            p.future().complete(work.get());
        } catch (RuntimeException e) {
            p.future().completeExceptionally(e);
        }
    }
}
