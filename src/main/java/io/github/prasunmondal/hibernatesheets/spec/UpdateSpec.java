package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.internal.Compat;
import io.github.prasunmondal.hibernatesheets.Worksheet;

/**
 * UPDATE matching rows. A spec without {@code where(...)} is rejected unless {@link #all()} is called,
 * so a forgotten filter can never rewrite the whole sheet.
 */
public final class UpdateSpec extends MutationSpec<UpdateSpec> {

    private boolean allRows;

    public UpdateSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.UPDATE;
    }

    /** Explicitly allow updating every row. */
    public UpdateSpec all() {
        this.allRows = true;
        return this;
    }

    @Override
    public Operation toOperation() {
        if (assignments.isEmpty()) {
            throw new IllegalStateException("UPDATE on '" + worksheet.name() + "' has nothing to set");
        }
        if (filters.isEmpty() && !allRows) {
            throw new IllegalStateException("UPDATE on '" + worksheet.name()
                    + "' has no where(...) and would change every row; call all() if that is intended");
        }
        return filtered(Compat.listOf(), assignmentList());
    }
}
