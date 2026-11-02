package io.github.prasunmondal.gridbase.result;

import io.github.prasunmondal.gridbase.internal.Compat;
import java.util.List;

/**
 * Result of ADD_COLUMNS.
 *
 * @param columns        columns actually added
 * @param skippedColumns columns skipped because they already existed (only with {@code skipExisting})
 * @param startColumn    1-based sheet column where the new headers start
 */
public record AddColumnsResult(String operationId, String worksheet, List<String> columns,
                               List<String> skippedColumns, int startColumn, int count,
                               long executionTimeMillis)
        implements OperationResult {

    public AddColumnsResult {
        columns = Compat.copyOf(columns);
        skippedColumns = Compat.copyOf(skippedColumns);
    }

    /** Without an engine-reported time ({@link OperationResult#UNKNOWN_TIME}). */
    public AddColumnsResult(String operationId, String worksheet, List<String> columns,
                            List<String> skippedColumns, int startColumn, int count) {
        this(operationId, worksheet, columns, skippedColumns, startColumn, count, UNKNOWN_TIME);
    }
}
