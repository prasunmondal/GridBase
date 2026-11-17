package io.github.prasunmondal.gridbase;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.prasunmondal.gridbase.result.CommitTiming;
import io.github.prasunmondal.gridbase.result.ExecutionResponse;
import io.github.prasunmondal.gridbase.result.OperationResult;

import java.util.List;
import java.util.Optional;

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

    /**
     * How the commit and flush part of {@link #executionTimeMillis()} split up, per worksheet.
     * Empty when the engine deployment predates commit timing.
     */
    public Optional<CommitTiming> commitTiming() {
        return Optional.ofNullable(response.commitTiming());
    }

    public JsonNode debug() {
        return response.debug();
    }
}
