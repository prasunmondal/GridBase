package io.github.prasunmondal.gridbase.result;

/** Result of a single engine operation. The concrete type depends on the operation type. */
public sealed interface OperationResult
        permits RowsResult, WorksheetCreated, ColumnsResult, AddColumnsResult, ClearResult, LastUpdatedResult {

    /** Value of {@link #executionTimeMillis()} when the engine did not report a time. */
    long UNKNOWN_TIME = -1;

    /** The operation id the engine echoed back. */
    String operationId();

    /**
     * Time the engine spent on this operation, in milliseconds: loading the worksheet when this
     * operation is the first to touch it, then compiling and running the operation. The commit, flush
     * and lock wait are shared by the whole request and are not included (see
     * {@link ExecutionResponse#executionTimeMillis()}). {@link #UNKNOWN_TIME} when the engine
     * deployment is older than per-operation timing.
     */
    long executionTimeMillis();
}
