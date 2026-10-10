package io.github.prasunmondal.gridbase.spec;

import io.github.prasunmondal.gridbase.internal.Compat;
import io.github.prasunmondal.gridbase.Worksheet;

import java.util.List;

/**
 * DELETE matching rows (the sheet rows are removed, not just cleared).
 * Requires {@code where(...)} or an explicit {@link #all()}.
 */
public final class DeleteSpec extends FilterSpec<DeleteSpec> {

    private boolean allRows;

    public DeleteSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.DELETE;
    }

    /** Explicitly allow deleting every row. Prefer {@link Worksheet#clear()} for that. */
    public DeleteSpec all() {
        this.allRows = true;
        return this;
    }

    @Override
    public Operation toOperation() {
        if (filters.isEmpty() && !allRows) {
            throw new IllegalStateException("DELETE on '" + worksheet.name()
                    + "' has no where(...) and would delete every row; call all() if that is intended");
        }
        return filtered(Compat.listOf(), Compat.listOf());
    }
}
