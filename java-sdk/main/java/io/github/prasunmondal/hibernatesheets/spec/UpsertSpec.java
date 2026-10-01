package io.github.prasunmondal.hibernatesheets.spec;

import io.github.prasunmondal.hibernatesheets.Worksheet;
import io.github.prasunmondal.hibernatesheets.query.Filters;

/**
 * UPSERT: if any row matches {@code where(...)}, every matching row is updated with the assigned values;
 * otherwise one new row is inserted with the assigned values.
 *
 * <p>The engine does <b>not</b> copy filter values into the inserted row, so a lookup column that is only
 * in {@code where} would be blank on insert. Use {@link #key(String, Object)}, which adds both the
 * filter and the value.</p>
 */
public final class UpsertSpec extends MutationSpec<UpsertSpec> {

    public UpsertSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.UPSERT;
    }

    /** Match on {@code column = value} and write that value on insert. */
    public UpsertSpec key(String column, Object value) {
        where(Filters.eq(column, value));
        return set(column, value);
    }

    @Override
    public Operation toOperation() {
        if (filters.isEmpty()) {
            // With no filter every existing row "matches", so the engine would update the whole sheet.
            throw new IllegalStateException("UPSERT on '" + worksheet.name() + "' needs key(...) or where(...)");
        }
        if (assignments.isEmpty()) {
            throw new IllegalStateException("UPSERT on '" + worksheet.name() + "' has nothing to set");
        }
        return filtered(java.util.List.of(), assignmentList());
    }
}
