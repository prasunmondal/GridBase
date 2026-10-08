package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.result.OperationResult;

import java.util.Objects;

/**
 * Typed handle to the result of an operation added to a {@link Batch}.
 * Resolves once the batch has executed successfully.
 */
public final class Ref<R extends OperationResult> {

    private final Batch batch;
    private final int index;
    private final Class<R> type;

    Ref(Batch batch, int index, Class<R> type) {
        this.batch = Objects.requireNonNull(batch);
        this.index = index;
        this.type = Objects.requireNonNull(type);
    }

    /** @throws IllegalStateException if the batch has not executed (or failed) */
    public R get() {
        BatchResult result = batch.result();
        if (result == null) {
            throw new IllegalStateException("Batch has not been executed successfully yet");
        }
        return result.get(this);
    }

    int index() {
        return index;
    }

    Class<R> type() {
        return type;
    }

    Batch batch() {
        return batch;
    }
}
