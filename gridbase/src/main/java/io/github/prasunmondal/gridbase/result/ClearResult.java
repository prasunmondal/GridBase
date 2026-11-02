package io.github.prasunmondal.gridbase.result;

/** Result of CLEAR_WORKSHEET (header row is preserved). */
public record ClearResult(String operationId, String worksheet, int rowsCleared, int columnsCleared,
                          long executionTimeMillis)
        implements OperationResult {

    /** Without an engine-reported time ({@link OperationResult#UNKNOWN_TIME}). */
    public ClearResult(String operationId, String worksheet, int rowsCleared, int columnsCleared) {
        this(operationId, worksheet, rowsCleared, columnsCleared, UNKNOWN_TIME);
    }
}
