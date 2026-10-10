package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.query.Filter;
import io.github.prasunmondal.gridbase.query.Sort;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, transport-neutral description of one engine operation.
 * Produced by the fluent specs; serialized by the client.
 *
 * @param limit {@code -1} means "no limit"
 */
public record Operation(
        OperationType type,
        String spreadsheetId,
        String worksheet,
        List<Filter> filters,
        List<Sort> orderBy,
        List<String> select,
        List<Assignment> values,
        List<List<Assignment>> rows,
        List<String> columns,
        boolean skipExisting,
        int limit,
        int offset) {

    public Operation {
        Objects.requireNonNull(type, "type");
        if (spreadsheetId == null || Compat.isBlank(spreadsheetId)) {
            throw new IllegalArgumentException("spreadsheetId must not be empty");
        }
        if (worksheet == null || Compat.isBlank(worksheet)) {
            throw new IllegalArgumentException("worksheet must not be empty");
        }
        filters = Compat.copyOf(filters);
        orderBy = Compat.copyOf(orderBy);
        select = Compat.copyOf(select);
        values = Compat.copyOf(values);
        rows = rows.stream().map(Compat::copyOf).collect(Compat.toList());
        columns = Compat.copyOf(columns);
        if (limit < -1) {
            throw new IllegalArgumentException("limit must be >= 0 (or -1 for no limit)");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
    }

    /** Copy of this operation with a different limit. */
    public Operation withLimit(int newLimit) {
        return new Operation(type, spreadsheetId, worksheet, filters, orderBy, select, values, rows,
                columns, skipExisting, newLimit, offset);
    }
}
