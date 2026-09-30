package io.github.prasunmondal.gsheetdb.result;

import java.util.List;

/** Result of GET_COLUMNS: the header row, left to right. */
public record ColumnsResult(String operationId, String worksheet, List<String> columns) implements OperationResult {

    public ColumnsResult {
        columns = List.copyOf(columns);
    }
}
