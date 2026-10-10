package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.GridBase.Outcome;
import io.github.prasunmondal.gridbase.GridBase.Reply;
import io.github.prasunmondal.gridbase.spec.Operation;

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
import java.util.function.Function;

/**
 * Collects requests for up to {@code window} and sends them to the engine as one HTTP call.
 *
 * <p>Combined calls are sent one at a time, in submission order, from a single daemon thread, so the
 * engine processes queued requests in the order they were made. While a call is in flight, new
 * requests keep accumulating for the next one.</p>
 */
final class RequestQueue {

    private record Pending(List<Operation> operations, CompletableFuture<Reply> future) {
    }

    private final Duration window;
    private final int maxOperations;
    private final Function<List<List<Operation>>, List<Outcome<Reply>>> sendCombined;
    private final Executor callbacks;
    private final ScheduledExecutorService worker;
    private volatile Thread workerThread;

    private final Deque<Pending> pending = new ArrayDeque<>();
    private int pendingOperations;
    private boolean flushScheduled;

    RequestQueue(Duration window, int maxOperations,
                 Function<List<List<Operation>>, List<Outcome<Reply>>> sendCombined, Executor callbacks) {
        if (window.isNegative()) {
            throw new IllegalArgumentException("window must not be negative");
        }
        if (maxOperations < 1) {
            throw new IllegalArgumentException("maxOperations must be >= 1");
        }
        this.window = window;
        this.maxOperations = maxOperations;
        this.sendCombined = Objects.requireNonNull(sendCombined);
        this.callbacks = Objects.requireNonNull(callbacks);
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gridbase-queue");
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
                && !GridBase.hasSchemaOperation(operations);
    }

    /** The returned future completes on a callback thread, never on the queue thread. */
    CompletableFuture<Reply> submit(List<Operation> operations) {
        Pending p = new Pending(Compat.copyOf(operations), new CompletableFuture<>());
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
        if (chunk.isEmpty()) {
            return;
        }
        try {
            List<Outcome<Reply>> outcomes = sendCombined.apply(chunk.stream().map(Pending::operations).collect(Compat.toList()));
            for (int i = 0; i < chunk.size(); i++) {
                Outcome<Reply> o = outcomes.get(i);
                if (o.failure() != null) {
                    chunk.get(i).future().completeExceptionally(o.failure());
                } else {
                    chunk.get(i).future().complete(o.value());
                }
            }
        } catch (Throwable t) {
            chunk.forEach(p -> p.future().completeExceptionally(t));
        }
    }
}
