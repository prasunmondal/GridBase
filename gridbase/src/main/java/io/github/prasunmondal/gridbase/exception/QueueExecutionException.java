package io.github.prasunmondal.gridbase.exception;

import io.github.prasunmondal.gridbase.internal.Compat;
import java.util.List;

/**
 * Some requests in an {@code APIRequestsQueue} failed. The others completed normally; read each one's
 * result or failure from its {@code Queued} handle. The first failure is the cause, the rest are
 * attached as suppressed exceptions.
 */
public class QueueExecutionException extends GridBaseException {

    private final List<RuntimeException> failures;
    private final int requestCount;

    public QueueExecutionException(List<RuntimeException> failures, int requestCount) {
        super(failures.size() + " of " + requestCount + " queued requests failed; first: "
                + failures.get(0).getMessage(), failures.get(0), false);
        this.failures = Compat.copyOf(failures);
        this.requestCount = requestCount;
        failures.stream().skip(1).forEach(this::addSuppressed);
    }

    public List<RuntimeException> failures() {
        return failures;
    }

    public int requestCount() {
        return requestCount;
    }
}
