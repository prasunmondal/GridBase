package io.github.prasunmondal.gsheetdb.transport;

import io.github.prasunmondal.gsheetdb.exception.TransportException;

/**
 * Moves one request JSON document to the engine and returns the reply body.
 *
 * <p>The default is {@link HttpTransport} (the deployed web app's {@code /exec} URL). Implement this
 * to add custom auth, route through your own HTTP stack, or stub the engine in tests.
 * Implementations must be thread-safe.</p>
 */
@FunctionalInterface
public interface Transport {

    /**
     * @param requestJson the complete request body
     * @return the raw engine reply body (JSON)
     * @throws TransportException when no usable reply was received
     */
    String send(String requestJson) throws TransportException;
}
