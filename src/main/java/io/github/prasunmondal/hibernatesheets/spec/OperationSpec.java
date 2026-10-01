package io.github.prasunmondal.hibernatesheets.spec;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.result.OperationResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A fluent, not-yet-executed operation against one worksheet.
 *
 * <p>Nothing touches the network until {@link #execute()} (or a {@code fetch*} method) is called.
 * A spec can instead be added to a {@link io.github.prasunmondal.hibernatesheets.Batch} to run with
 * other operations in a single request. Specs are mutable builders and not thread-safe; the resulting
 * {@link Operation} is immutable.</p>
 *
 * @param <R> result type produced by the engine for this operation
 */
public abstract class OperationSpec<R extends OperationResult> {

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    protected final Worksheet worksheet;

    protected OperationSpec(Worksheet worksheet) {
        this.worksheet = Objects.requireNonNull(worksheet, "worksheet");
    }

    public abstract OperationType type();

    public abstract Class<R> resultType();

    /** Validates the spec and freezes it into an {@link Operation}. */
    public abstract Operation toOperation();

    /** Sends this operation as a single-operation request and returns its result. */
    public R execute() {
        return worksheet.client().executeOne(toOperation(), resultType());
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
        return worksheet.client().objectMapper().convertValue(source, MAP_TYPE);
    }

    protected Operation operation(List<io.github.prasunmondal.hibernatesheets.query.Filter> filters,
                                  List<io.github.prasunmondal.hibernatesheets.query.Sort> orderBy,
                                  List<String> select,
                                  List<Assignment> values,
                                  List<List<Assignment>> rows,
                                  List<String> columns,
                                  boolean skipExisting,
                                  int limit,
                                  int offset) {
        return new Operation(type(), worksheet.spreadsheetId(), worksheet.name(), filters, orderBy, select,
                values, rows, columns, skipExisting, limit, offset);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + worksheet + "]";
    }
}
