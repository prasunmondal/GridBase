package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.SheetRequest;
import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** SELECT. Returns all columns unless {@link #columns(String...)} restricts them. */
public final class SelectSpec extends FilterSpec<SelectSpec> {

    private final List<String> projection = new ArrayList<>();

    public SelectSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.SELECT;
    }

    /** Only return these columns (in this order). */
    public SelectSpec columns(String... columns) {
        projection.addAll(Arrays.asList(columns));
        return this;
    }

    @Override
    public Operation toOperation() {
        return filtered(projection, List.of());
    }

    public List<Row> fetch() {
        return execute().rows();
    }

    public <T> List<T> fetch(Class<T> type) {
        return execute().as(type);
    }

    /** {@link #fetch()} without waiting; see {@link #executeAsync()}. */
    public CompletableFuture<List<Row>> fetchAsync() {
        return executeAsync().thenApply(RowsResult::rows);
    }

    public <T> CompletableFuture<List<T>> fetchAsync(Class<T> type) {
        return executeAsync().thenApply(r -> r.as(type));
    }

    /** Fetches with {@code limit 1} (the spec itself is not modified). */
    public Optional<Row> fetchFirst() {
        return firstRequest().execute();
    }

    /** {@link #fetch(Class)} as a not-yet-sent request, e.g. to {@code queue(...)} it. */
    public <T> SheetRequest<List<T>> request(Class<T> type) {
        return request().map(r -> r.as(type));
    }

    /** {@link #fetchFirst()} as a not-yet-sent request. */
    public SheetRequest<Optional<Row>> firstRequest() {
        Operation op = toOperation();
        if (op.limit() != 0) {
            op = op.withLimit(1);
        }
        return SheetRequest.of(worksheet.client(), List.of(op), r -> ((RowsResult) r.results().get(0)).first());
    }

    public <T> SheetRequest<Optional<T>> firstRequest(Class<T> type) {
        return firstRequest().map(row -> row.map(r -> r.as(type)));
    }

    public <T> Optional<T> fetchFirst(Class<T> type) {
        return fetchFirst().map(r -> r.as(type));
    }
}
