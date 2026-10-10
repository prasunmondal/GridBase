package io.github.prasunmondal.hibernatesheets;

import io.github.prasunmondal.hibernatesheets.exception.HibernateSheetsException;

import java.time.Duration;
import java.util.Objects;

/**
 * When to re-send a failed request.
 *
 * <p>Only failures flagged {@link HibernateSheetsException#isRetryable() retryable} are retried
 * (network errors, timeouts, HTTP 429/5xx, Apps Script quota/lock errors). By default only
 * <b>read-only</b> requests (SELECT, GET_COLUMNS) are retried: a timed-out write may already have been
 * committed by the engine, and re-sending an INSERT would duplicate rows.</p>
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final double multiplier;
    private final Duration maxDelay;
    private final boolean retryWrites;

    private RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier,
                        Duration maxDelay, boolean retryWrites) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = Objects.requireNonNull(initialDelay);
        this.multiplier = multiplier;
        this.maxDelay = Objects.requireNonNull(maxDelay);
        this.retryWrites = retryWrites;
    }

    /** 3 attempts, 500 ms then 1 s back-off, reads only. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(500), 2.0, Duration.ofSeconds(8), false);
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO, false);
    }

    public static RetryPolicy of(int maxAttempts, Duration initialDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, 2.0, Duration.ofSeconds(8), false);
    }

    public RetryPolicy withMultiplier(double newMultiplier) {
        return new RetryPolicy(maxAttempts, initialDelay, newMultiplier, maxDelay, retryWrites);
    }

    public RetryPolicy withMaxDelay(Duration newMaxDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, newMaxDelay, retryWrites);
    }

    /** Also retry requests that modify data. Only enable if duplicate writes are acceptable. */
    public RetryPolicy retryingWrites(boolean enabled) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay, enabled);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    boolean shouldRetry(HibernateSheetsException failure, int attempt, boolean readOnly) {
        return attempt < maxAttempts && failure.isRetryable() && (readOnly || retryWrites);
    }

    /** Delay before attempt {@code attempt + 1} (attempts are 1-based). */
    Duration delayAfter(int attempt) {
        double millis = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        return Duration.ofMillis((long) Math.min(millis, maxDelay.toMillis()));
    }
}
