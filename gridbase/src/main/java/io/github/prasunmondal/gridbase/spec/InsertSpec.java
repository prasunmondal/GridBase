package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.Worksheet;
import io.github.prasunmondal.gridbase.result.RowsResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * INSERT one or more rows, appended below the last used row in one write.
 * Each row is a {@code Map} or a POJO/record; omitted columns are left empty.
 */
public final class InsertSpec extends OperationSpec<RowsResult> {

    private final List<List<Assignment>> rows = new ArrayList<>();

    public InsertSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.INSERT;
    }

    @Override
    public Class<RowsResult> resultType() {
        return RowsResult.class;
    }

    public InsertSpec row(Object row) {
        Map<String, Object> values = columnValues(row);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Cannot insert a row with no columns");
        }
        List<Assignment> assignments = new ArrayList<>(values.size());
        values.forEach((column, value) -> assignments.add(new Assignment(column, value, Assignment.Kind.SET)));
        rows.add(assignments);
        return this;
    }

    public InsertSpec rows(Collection<?> newRows) {
        newRows.forEach(this::row);
        return this;
    }

    @Override
    public Operation toOperation() {
        if (rows.isEmpty()) {
            throw new IllegalStateException("INSERT into '" + worksheet.name() + "' has no rows");
        }
        // Always the bulk "rows" form; a single row is a one-element batch.
        return operation(Compat.listOf(), Compat.listOf(), Compat.listOf(), Compat.listOf(), rows, Compat.listOf(), false, -1, 0);
    }
}
