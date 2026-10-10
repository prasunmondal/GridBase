package io.github.prasunmondal.gridbase.exception;

/**
 * Base type for every error raised by the SDK. Unchecked, so callers only catch what they care about.
 */
public class HibernateSheetsException extends RuntimeException {

    private final boolean retryable;

    public HibernateSheetsException(String message) {
        this(message, null, false);
    }

    public HibernateSheetsException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    /** Whether the failure is transient (network blip, quota, 5xx) and the same request may succeed later. */
    public boolean isRetryable() {
        return retryable;
    }
}
