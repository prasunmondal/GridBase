package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.result.ExecutionResponse;
import io.github.prasunmondal.hibernatesheets.spec.Operation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * A request that has not been sent yet: run it now with {@link #execute()}, or add it to an
 * {@link APIRequestsQueue} with {@link #queue} to send it together with other requests.
 *
 * <pre>{@code
 * SheetRequest<List<Employee>> all = Employee.repository().requests().findAll();
 * List<Employee> now = all.execute();                 // one call, right away
 *
 * APIRequestsQueue reqQ = new APIRequestsQueue();
 * Queued<List<Employee>> later = all.queue(reqQ);     // nothing sent yet
 * reqQ.execute();                                     // everything queued, in one call
 * later.get();
 * }</pre>
 *
 * <p>Immutable; the same request may be executed or queued any number of times.</p>
 *
 * @param <T> what the caller gets back
 */
public final class SheetRequest<T> {

    private final HibernateSheets client;
    private final List<Operation> operations;
    private final Function<ExecutionResponse, T> mapper;
    private final T constant;

    private SheetRequest(HibernateSheets client, List<Operation> operations,
                         Function<ExecutionResponse, T> mapper, T constant) {
        this.client = client;
        this.operations = operations;
        this.mapper = mapper;
        this.constant = constant;
    }

    /** Operations sent as one engine request (all-or-nothing for row changes), mapped by {@code mapper}. */
    public static <T> SheetRequest<T> of(HibernateSheets client, List<Operation> operations,
                                         Function<ExecutionResponse, T> mapper) {
        Objects.requireNonNull(client, "client");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("At least one operation is required; use completed(...)");
        }
        return new SheetRequest<>(client, List.copyOf(operations), Objects.requireNonNull(mapper), null);
    }

    /** A request that needs no network call (e.g. saving an empty list). */
    public static <T> SheetRequest<T> completed(T value) {
        return new SheetRequest<>(null, List.of(), null, value);
    }

    /** Sends this request now (through the client's cache and request queue, if configured). */
    public T execute() {
        return isCompleted() ? constant : mapper.apply(client.execute(operations));
    }

    public CompletableFuture<T> executeAsync() {
        return isCompleted() ? CompletableFuture.completedFuture(constant)
                : client.executeAsync(operations).thenApply(mapper);
    }

    /** Adds this request to {@code queue}; the result is available after {@code queue.execute()}. */
    public Queued<T> queue(APIRequestsQueue queue) {
        return queue.add(this);
    }

    /** The same request with its result transformed. */
    public <U> SheetRequest<U> map(Function<? super T, ? extends U> f) {
        Objects.requireNonNull(f);
        if (isCompleted()) {
            return completed(f.apply(constant));
        }
        return new SheetRequest<>(client, operations, r -> f.apply(mapper.apply(r)), null);
    }

    public List<Operation> operations() {
        return operations;
    }

    boolean isCompleted() {
        return client == null;
    }

    HibernateSheets client() {
        return client;
    }

    T resultOf(ExecutionResponse response) {
        return isCompleted() ? constant : mapper.apply(response);
    }
}
