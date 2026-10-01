package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.query.Filter;
import io.github.prasunmondal.hibernatesheets.query.Sort;

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
        if (spreadsheetId == null || spreadsheetId.isBlank()) {
            throw new IllegalArgumentException("spreadsheetId must not be empty");
        }
        if (worksheet == null || worksheet.isBlank()) {
            throw new IllegalArgumentException("worksheet must not be empty");
        }
        filters = List.copyOf(filters);
        orderBy = List.copyOf(orderBy);
        select = List.copyOf(select);
        values = List.copyOf(values);
        rows = rows.stream().map(List::copyOf).toList();
        columns = List.copyOf(columns);
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
