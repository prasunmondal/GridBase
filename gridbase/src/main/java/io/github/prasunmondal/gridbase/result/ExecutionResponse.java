package io.github.prasunmondal.gridbase.result;

import io.github.prasunmondal.gridbase.internal.Compat;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * A successful engine response.
 *
 * @param executionTimeMillis time the engine spent on every operation plus the commit and flush (engines
 *                            before per-operation timing report only the last query's compile and run time,
 *                            or 0); the lock wait is not included
 * @param results one result per operation, in the order the operations were sent
 * @param debug   the engine's debug entries (useful while developing; may be {@code null})
 */
public record ExecutionResponse(String requestId, long executionTimeMillis,
                                List<OperationResult> results, JsonNode debug) {

    public ExecutionResponse {
        results = Compat.copyOf(results);
    }
}
