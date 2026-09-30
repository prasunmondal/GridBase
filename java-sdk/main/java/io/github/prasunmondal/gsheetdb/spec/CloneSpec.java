package io.github.prasunmondal.gsheetdb.spec;

import io.github.prasunmondal.gsheetdb.Worksheet;

/**
 * CLONE: copies every matching row to the bottom of the sheet, applying the assigned values to the
 * copies (e.g. a new id). The engine fails the request if nothing matches.
 */
public final class CloneSpec extends MutationSpec<CloneSpec> {

    public CloneSpec(Worksheet worksheet) {
        super(worksheet);
    }

    @Override
    public OperationType type() {
        return OperationType.CLONE;
    }

    @Override
    public Operation toOperation() {
        if (filters.isEmpty()) {
            throw new IllegalStateException("CLONE on '" + worksheet.name() + "' needs where(...)");
        }
        return filtered(java.util.List.of(), assignmentList());
    }
}
