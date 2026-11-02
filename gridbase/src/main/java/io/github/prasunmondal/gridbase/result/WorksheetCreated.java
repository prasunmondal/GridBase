package io.github.prasunmondal.gridbase.result;

/** Result of CREATE_WORKSHEET. */
public record WorksheetCreated(String operationId, String worksheet, long sheetId, long executionTimeMillis)
        implements OperationResult {

    /** Without an engine-reported time ({@link OperationResult#UNKNOWN_TIME}). */
    public WorksheetCreated(String operationId, String worksheet, long sheetId) {
        this(operationId, worksheet, sheetId, UNKNOWN_TIME);
    }
}
