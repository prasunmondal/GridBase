package io.github.prasunmondal.gridbase.exception;

/**
 * The request never produced a usable engine response: network failure, HTTP error status,
 * a timeout, or a non-JSON body (typically a Google sign-in / error HTML page).
 */
public class TransportException extends GridBaseException {

    private final int httpStatus;

    public TransportException(String message, int httpStatus, Throwable cause, boolean retryable) {
        super(message, cause, retryable);
        this.httpStatus = httpStatus;
    }

    /** HTTP status of the final response, or {@code -1} if no response was received. */
    public int getHttpStatus() {
        return httpStatus;
    }
}
