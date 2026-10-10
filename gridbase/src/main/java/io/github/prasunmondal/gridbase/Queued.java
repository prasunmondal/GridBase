package io.github.prasunmondal.gridbase;

import io.github.prasunmondal.gridbase.exception.GridBaseException;

/**
 * Handle to a request added to an {@link APIRequestsQueue}. Holds its result (or failure) once the
 * queue has executed.
 */
public final class Queued<T> {

    private volatile boolean done;
    private volatile T value;
    private volatile RuntimeException failure;

    Queued() {
    }

    /**
     * @throws IllegalStateException if the queue has not executed yet
     * @throws RuntimeException      this request's own failure (e.g. {@code ServerException})
     */
    public T get() {
        if (!done) {
            throw new IllegalStateException("The queue has not been executed yet");
        }
        if (failure != null) {
            throw failure;
        }
        return value;
    }

    public boolean isDone() {
        return done;
    }

    public boolean failed() {
        return done && failure != null;
    }

    /** {@code null} unless the request failed. */
    public RuntimeException failure() {
        return failure;
    }

    void complete(T result) {
        value = result;
        done = true;
    }

    void fail(Throwable t) {
        failure = t instanceof RuntimeException r ? r
                : new GridBaseException(String.valueOf(t.getMessage()), t, false);
        done = true;
    }
}
