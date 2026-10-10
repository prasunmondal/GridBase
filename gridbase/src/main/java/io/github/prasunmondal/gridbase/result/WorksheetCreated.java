package io.github.prasunmondal.gridbase.result;

/** Result of CREATE_WORKSHEET. */
public record WorksheetCreated(String operationId, String worksheet, long sheetId) implements OperationResult {
}
