package io.github.prasunmondal.gridbase.result;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of GET_LAST_UPDATED: Drive's last-modified time of the whole spreadsheet file. It changes on any
 * edit to any tab, including edits made directly in Google Sheets.
 */
public record LastUpdatedResult(String operationId, String spreadsheetId, Instant lastUpdated)
        implements OperationResult {

    public LastUpdatedResult {
        Objects.requireNonNull(lastUpdated, "lastUpdated");
    }
}
