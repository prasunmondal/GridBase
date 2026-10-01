package io.github.prasunmondal.hibernatesheets;

/**
 * One HTTP attempt about to be sent to the engine; passed to pre-network-call actions.
 *
 * @param attempt 1-based; greater than 1 when the request is being retried
 */
public record NetworkCall(String requestId, String requestBody, int attempt) {
}
