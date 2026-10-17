package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.result.RowsResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * REPLACE_ALL_DATA: replaces every data row (the header row is kept) with the given rows, in one step. The engine
 * clears and inserts at commit, so if the request fails the sheet is left as it was. No rows clears the
 * sheet. Each row is a {@code Map} or a POJO/record; omitted columns are left empty.
 */
public final class ReplaceAllDataSpec extends OperationSpec<RowsResult> {

    private final List<List<Assignment>> rows = new ArrayList<>();

    public ReplaceAllDataSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.REPLACE_ALL_DATA;
    }

    @Override
    public Class<RowsResult> resultType() {
        return RowsResult.class;
    }

    public ReplaceAllDataSpec row(Object row) {
        Map<String, Object> values = columnValues(row);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot write a row with no columns");
        }
        List<Assignment> assignments = new ArrayList<>(values.size());
        values.forEach((column, value) -> assignments.add(new Assignment(column, value, Assignment.Kind.SET)));
        rows.add(assignments);
        return this;
    }

    public ReplaceAllDataSpec rows(Collection<?> newRows) {
        newRows.forEach(this::row);
        return this;
    }

    /**
     * Values for these columns are dropped, instead of failing, if the sheet has no such column.
     * {@link io.github.prasunmondal.gridbase.mapping.OptionalColumn} properties are included automatically.
     */
    public ReplaceAllDataSpec optionalColumns(String... columns) {
        addOptionalColumns(columns);
        return this;
    }

    @Override
    public Operation toOperation() {
        return operation(Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), rows, Compat.listOf(), false, -1, 0);
    }
}
