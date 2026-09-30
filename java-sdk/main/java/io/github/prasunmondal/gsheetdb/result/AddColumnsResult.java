package io.github.prasunmondal.gsheetdb.result;

import java.util.List;

/**
 * Result of ADD_COLUMNS.
 *
 * @param columns        columns actually added
 * @param skippedColumns columns skipped because they already existed (only with {@code skipExisting})
 * @param startColumn    1-based sheet column where the new headers start
 */
public record AddColumnsResult(String operationId, String worksheet, List<String> columns,
                               List<String> skippedColumns, int startColumn, int count)
        implements OperationResult {

    public AddColumnsResult {
        columns = List.copyOf(columns);
        skippedColumns = List.copyOf(skippedColumns);
    }
}
