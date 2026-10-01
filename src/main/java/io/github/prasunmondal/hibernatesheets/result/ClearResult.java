package io.github.prasunmondal.hibernatesheets.result;

/** Result of CLEAR_WORKSHEET (header row is preserved). */
public record ClearResult(String operationId, String worksheet, int rowsCleared, int columnsCleared)
        implements OperationResult {
}
