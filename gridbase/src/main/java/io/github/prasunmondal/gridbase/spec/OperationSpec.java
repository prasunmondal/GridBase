package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.internal.OptionalColumns;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.prasunmondal.gridbase.APIRequestsQueue;
import io.github.prasunmondal.gridbase.Queued;
import io.github.prasunmondal.gridbase.RequestFailure;
import io.github.prasunmondal.gridbase.SheetRequest;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.result.OperationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A fluent, not-yet-executed operation against one worksheet.
 *
 * <p>Nothing touches the network until {@link #execute()} (or a {@code fetch*} method) is called.
 * A spec can instead be added to a {@link io.github.prasunmondal.gridbase.Batch} to run with
 * other operations in a single request. Specs are mutable builders and not thread-safe; the resulting
 * {@link Operation} is immutable.</p>
 *
 * @param <R> result type produced by the engine for this operation
 */
public abstract class OperationSpec<R extends OperationResult> {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    protected final Worksheet worksheet;

    /** Columns written only if the sheet has them; see {@link io.github.prasunmondal.gridbase.mapping.OptionalColumn}. */
    protected final Set<String> optionalColumns = new LinkedHashSet<>();

    private final List<Consumer<RequestFailure>> failureActions = new ArrayList<>();

    private Class<?> lastRowType;
    private List<String> lastRowOptionalColumns;

    protected OperationSpec(Worksheet worksheet) {
        this.worksheet = Objects.requireNonNull(worksheet, "worksheet");
    }

    public abstract OperationType type();

    public abstract Class<R> resultType();

    /** Validates the spec and freezes it into an {@link Operation}. */
    public abstract Operation toOperation();

    /** Sends this operation as a single-operation request and returns its result. */
    public R execute() {
        if (!failureActions.isEmpty()) {
            return request().execute();
        }
        return worksheet.client().executeOne(toOperation(), resultType());
    }

    /**
     * Sends this operation without waiting. With a request queue configured, operations submitted
     * close together are sent in one HTTP call. Spec validation errors are thrown immediately.
     */
    public CompletableFuture<R> executeAsync() {
        if (!failureActions.isEmpty()) {
            return request().executeAsync();
        }
        return worksheet.client().executeOneAsync(toOperation(), resultType());
    }

    /**
     * Adds an action that runs if this operation fails when executed, fetched or queued; see
     * {@link SheetRequest#onFailure}. Not used when the spec is added to a
     * {@link io.github.prasunmondal.gridbase.Batch}; use {@code Batch.onFailure} there.
     */
    public OperationSpec<R> onFailure(Consumer<RequestFailure> action) {
        failureActions.add(Objects.requireNonNull(action, "action"));
        return this;
    }

    /** This operation as a not-yet-sent {@link SheetRequest} (validated now). */
    public SheetRequest<R> request() {
        Class<R> type = resultType();
        return withFailureActions(SheetRequest.of(worksheet.client(), Compat.listOf(toOperation()),
                r -> type.cast(r.results().get(0))));
    }

    /** {@code request} plus this spec's failure actions. */
    protected <T> SheetRequest<T> withFailureActions(SheetRequest<T> request) {
        SheetRequest<T> out = request;
        for (Consumer<RequestFailure> action : failureActions) {
            out = out.onFailure(action);
        }
        return out;
    }

    /** Adds this operation to {@code queue}; the result is available after {@code queue.execute()}. */
    public Queued<R> queue(APIRequestsQueue queue) {
        return request().queue(queue);
    }

    public Worksheet worksheet() {
        return worksheet;
    }

    /** Turns a {@code Map} or a POJO/record (via Jackson property names) into column/value pairs. */
    protected Map<String, Object> columnValues(Object source) {
        Objects.requireNonNull(source, "row");
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        if (source instanceof CharSequence || source instanceof Number || source instanceof Boolean
                || source instanceof Iterable<?>) {
            throw new IllegalArgumentException("A row must be a Map or an object with properties, not "
                    + source.getClass().getName());
        }
        if (source.getClass() != lastRowType) {
            lastRowType = source.getClass();
            lastRowOptionalColumns = OptionalColumns.of(worksheet.client().objectMapper(), lastRowType);
        }
        optionalColumns.addAll(lastRowOptionalColumns);
        return worksheet.client().objectMapper().convertValue(source, MAP_TYPE);
    }

    /**
     * Marks columns whose values are dropped, instead of failing, if the sheet has no such column.
     * {@link io.github.prasunmondal.gridbase.mapping.OptionalColumn} properties of a row object are added
     * automatically.
     */
    protected void addOptionalColumns(String... columns) {
        for (String column : columns) {
            optionalColumns.add(Objects.requireNonNull(column, "column"));
        }
    }

    protected Operation operation(List<io.github.prasunmondal.gridbase.query.Filter> filters,
                                  List<io.github.prasunmondal.gridbase.query.Sort> orderBy,
                                  List<String> select,
                                  List<Assignment> values,
                                  List<List<Assignment>> rows,
                                  List<String> columns,
                                  boolean skipExisting,
                                  int limit,
                                  int offset) {
        return new Operation(type(), worksheet.spreadsheetId(), worksheet.name(), filters, orderBy, select,
                values, rows, columns, skipExisting, limit, offset, new ArrayList<>(optionalColumns));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + worksheet + "]";
    }
}
