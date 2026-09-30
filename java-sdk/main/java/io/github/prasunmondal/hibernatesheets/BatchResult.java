package io.github.prasunmondal.hibernatesheets;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.hibernatesheets.result.ExecutionResponse;
import io.github.prasunmondal.hibernatesheets.result.OperationResult;

import java.util.List;

/** Results of an executed {@link Batch}, in the order operations were added. */
public final class BatchResult {

    private final Batch batch;
    private final ExecutionResponse response;

    BatchResult(Batch batch, ExecutionResponse response) {
        this.batch = batch;
        this.response = response;
    }

    public <R extends OperationResult> R get(Ref<R> ref) {
        if (ref.batch() != batch) {
            throw new IllegalArgumentException("Ref belongs to a different batch");
        }
        return ref.type().cast(response.results().get(ref.index()));
    }

    public List<OperationResult> results() {
        return response.results();
    }

    public String requestId() {
        return response.requestId();
    }

    public long executionTimeMillis() {
        return response.executionTimeMillis();
    }

    public JsonNode debug() {
        return response.debug();
    }
}
