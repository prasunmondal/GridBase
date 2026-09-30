package io.github.prasunmondal.gsheetdb.result;

/** Result of a single engine operation. The concrete type depends on the operation type. */
public sealed interface OperationResult
        permits RowsResult, WorksheetCreated, ColumnsResult, AddColumnsResult, ClearResult {

    /** The operation id the engine echoed back. */
    String operationId();
}
