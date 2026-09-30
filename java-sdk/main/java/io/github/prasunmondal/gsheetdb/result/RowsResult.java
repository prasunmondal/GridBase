package io.github.prasunmondal.gsheetdb.result;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Result of SELECT, INSERT, UPDATE, DELETE, UPSERT and CLONE.
 * For writes, {@code rows} are the rows affected (inserted/updated/deleted/cloned) and
 * {@code rowCount} is how many there were.
 */
public record RowsResult(String operationId, int rowCount, List<Row> rows) implements OperationResult {

    public RowsResult {
        rows = List.copyOf(rows);
    }

    /** Alias for {@link #rowCount()}, reads better for writes: {@code update(...).execute().count()}. */
    public int count() {
        return rowCount;
    }

    public Optional<Row> first() {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public <T> List<T> as(Class<T> type) {
        return rows.stream().map(r -> r.as(type)).toList();
    }

    public <T> List<T> map(Function<Row, T> mapper) {
        return rows.stream().map(mapper).toList();
    }
}
