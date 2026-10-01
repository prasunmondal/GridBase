package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.result.Row;
import io.github.prasunmondal.hibernatesheets.result.RowsResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

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

    /** Fetches with {@code limit 1} (the spec itself is not modified). */
    public Optional<Row> fetchFirst() {
        Operation op = toOperation();
        if (op.limit() != 0) {
            op = op.withLimit(1);
        }
        return worksheet.client().executeOne(op, RowsResult.class).first();
    }

    public <T> Optional<T> fetchFirst(Class<T> type) {
        return fetchFirst().map(r -> r.as(type));
    }
}
