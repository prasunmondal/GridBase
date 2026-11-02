package io.github.prasunmondal.gridbase.result;

import io.github.prasunmondal.gridbase.internal.Compat;
import java.util.List;

/** Result of GET_COLUMNS: the header row, left to right. */
public record ColumnsResult(String operationId, String worksheet, List<String> columns, long executionTimeMillis)
        implements OperationResult {

    public ColumnsResult {
        columns = Compat.copyOf(columns);
    }

    /** Without an engine-reported time ({@link OperationResult#UNKNOWN_TIME}). */
    public ColumnsResult(String operationId, String worksheet, List<String> columns) {
        this(operationId, worksheet, columns, UNKNOWN_TIME);
    }
}
