package io.github.prasunmondal.hibernatesheets.result;

import io.github.prasunmondal.hibernatesheets.internal.Compat;
import java.util.List;

/** Result of GET_COLUMNS: the header row, left to right. */
public record ColumnsResult(String operationId, String worksheet, List<String> columns) implements OperationResult {

    public ColumnsResult {
        columns = Compat.copyOf(columns);
    }
}
