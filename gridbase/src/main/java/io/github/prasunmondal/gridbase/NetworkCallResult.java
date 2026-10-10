package io.github.prasunmondal.gridbase;

import java.time.Duration;

/**
 * Outcome of one HTTP attempt; passed to post-network-call actions.
 * Exactly one of {@code responseBody} / {@code failure} is non-null.
 */
public record NetworkCallResult(NetworkCall call, String responseBody, RuntimeException failure, Duration elapsed) {

    public boolean succeeded() {
        return failure == null;
    }
}
