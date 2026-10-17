package io.github.prasunmondal.gridbase;

import java.time.Duration;

/**
 * A request that failed for good, after any retries; passed to failure actions. The failure is a
 * transport error or an error reported by the engine.
 *
 * <p>A call's own failure action can also see failures that happened before or after any request was
 * sent (e.g. while mapping the reply); then {@code requestId} and {@code requestBody} are {@code null}
 * and {@code attempts} is 0.</p>
 *
 * @param readOnly whether the request contained only reads
 * @param attempts number of HTTP attempts made, retries included
 * @param elapsed  time from the first attempt to the final failure, retry delays included
 */
public record RequestFailure(String requestId, String requestBody, boolean readOnly, int attempts,
                             RuntimeException failure, Duration elapsed) {
}
