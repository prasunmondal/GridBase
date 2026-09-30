package io.github.prasunmondal.gsheetdb.result;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A successful engine response.
 *
 * @param results one result per operation, in the order the operations were sent
 * @param debug   the engine's debug entries (useful while developing; may be {@code null})
 */
public record ExecutionResponse(String requestId, long executionTimeMillis,
                                List<OperationResult> results, JsonNode debug) {

    public ExecutionResponse {
        results = List.copyOf(results);
    }
}
