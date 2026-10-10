package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.GridBase.Outcome;
import io.github.prasunmondal.gridbase.exception.QueueExecutionException;
import io.github.prasunmondal.gridbase.result.ExecutionResponse;
import io.github.prasunmondal.gridbase.result.OperationResult;
import io.github.prasunmondal.gridbase.spec.OperationSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects requests and sends them only when {@link #execute()} is called — as few HTTP calls as
 * possible, usually one.
 *
 * <pre>{@code
 * APIRequestsQueue reqQ = new APIRequestsQueue();
 * Queued<List<Customer>> customers = Customer.repository().requests().findAll().queue(reqQ);
 * Queued<List<Delivery>> today     = Delivery.repository().requests().findWhere(eq("date", today)).queue(reqQ);
 * reqQ.execute();
 * customers.get();
 * }</pre>
 *
 * <ul>
 *   <li>Fresh cache hits are answered without a network call; everything else for the same client
 *       goes out in one HTTP call, in the order queued. Requests on different clients (different
 *       script URL / settings) go out as one call per client.</li>
 *   <li>Each request succeeds or fails on its own: if the engine rejects the combined call, each request
 *       is re-sent alone so only the faulty one fails. A request made of several operations (e.g.
 *       {@code upsertAll}) stays all-or-nothing.</li>
 *   <li>{@code create}, {@code clear} and {@code addColumns} (including the repository's {@code save},
 *       {@code saveAll} and {@code deleteAll}, which clear) are sent in a call of their own (in order),
 *       as are requests larger than {@code maxOperationsPerCall}.</li>
 * </ul>
 *
 * <p>Single use: after {@code execute()} create a new queue. Not meant to be shared between threads.</p>
 */
public final class APIRequestsQueue {

    private record Entry<T>(SheetRequest<T> request, Queued<T> handle) {
    }

    private final int maxOperationsPerCall;
    private final List<Entry<?>> entries = new ArrayList<>();
    private boolean executed;

    /** At most 100 operations per HTTP call. */
    public APIRequestsQueue() {
        this(100);
    }

    public APIRequestsQueue(int maxOperationsPerCall) {
        if (maxOperationsPerCall < 1) {
            throw new IllegalArgumentException("maxOperationsPerCall must be >= 1");
        }
        this.maxOperationsPerCall = maxOperationsPerCall;
    }

    public synchronized <T> Queued<T> add(SheetRequest<T> request) {
        if (executed) {
            throw new IllegalStateException("Queue already executed; create a new one");
        }
        Queued<T> handle = new Queued<>();
        entries.add(new Entry<>(request, handle));
        return handle;
    }

    public <R extends OperationResult> Queued<R> add(OperationSpec<R> spec) {
        return add(spec.request());
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * Sends everything queued and completes every {@link Queued} handle.
     *
     * @throws QueueExecutionException if any request failed (after all handles are completed; the
     *                                 successful ones can still be read)
     */
    public synchronized void execute() {
        if (executed) {
            throw new IllegalStateException("Queue already executed; create a new one");
        }
        executed = true;

        Map<GridBase, List<Entry<?>>> byClient = new LinkedHashMap<>();
        for (Entry<?> e : entries) {
            if (e.request().isCompleted()) {
                complete(e, null);
            } else {
                byClient.computeIfAbsent(e.request().client(), c -> new ArrayList<>()).add(e);
            }
        }

        for (Map.Entry<GridBase, List<Entry<?>>> group : byClient.entrySet()) {
            List<Entry<?>> list = group.getValue();
            List<GridBase.Planned> requests = list.stream()
                    .map(e -> new GridBase.Planned(e.request().operations(), e.request().isForceRefresh()))
                    .collect(Compat.toList());
            List<Outcome<ExecutionResponse>> outcomes;
            try {
                outcomes = group.getKey().executeTogether(requests, maxOperationsPerCall);
            } catch (RuntimeException ex) {
                list.forEach(e -> e.handle().fail(ex));
                continue;
            }
            for (int i = 0; i < list.size(); i++) {
                Outcome<ExecutionResponse> o = outcomes.get(i);
                if (o.failure() != null) {
                    list.get(i).handle().fail(o.failure());
                } else {
                    complete(list.get(i), o.value());
                }
            }
        }

        List<RuntimeException> failures = entries.stream()
                .map(e -> e.handle().failure())
                .filter(f -> f != null)
                .collect(Compat.toList());
        if (!failures.isEmpty()) {
            throw new QueueExecutionException(failures, entries.size());
        }
    }

    private static <T> void complete(Entry<T> e, ExecutionResponse response) {
        try {
            e.handle().complete(e.request().resultOf(response));
        } catch (RuntimeException ex) {
            e.handle().fail(ex);
        }
    }
}
