package io.github.prasunmondal.hibernatesheets.exception;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/**
 * The engine received the request and answered {@code success: false}
 * (validation error, unknown column, worksheet not found, Apps Script quota, ...).
 *
 * <p>The engine throws before {@code commitAll()}, so row operations in the failed request were
 * <b>not</b> written. Schema operations (create worksheet, add columns, clear) that ran earlier in the
 * same request are applied immediately by the engine and are not rolled back.</p>
 */
public class ServerException extends HibernateSheetsException {

    private static final List<String> TRANSIENT_MARKERS = List.of(
            "too many times in a short time",
            "lock timeout",
            "timed out",
            "service unavailable",
            "try again later",
            "rate limit");

    private final String serverMessage;
    private final String exceptionType;
    private final List<String> serverStackTrace;
    private final JsonNode debug;

    public ServerException(String serverMessage, String exceptionType,
                           List<String> serverStackTrace, JsonNode debug) {
        super("hibernate.sheets engine error"
                        + (exceptionType == null || exceptionType.isEmpty() ? "" : " [" + exceptionType + "]")
                        + ": " + serverMessage,
                null, isTransient(serverMessage));
        this.serverMessage = serverMessage;
        this.exceptionType = exceptionType;
        this.serverStackTrace = serverStackTrace == null ? List.of() : List.copyOf(serverStackTrace);
        this.debug = debug;
    }

    private static boolean isTransient(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("for one day")) {
            return false; // daily quota: retrying within seconds cannot succeed
        }
        return TRANSIENT_MARKERS.stream().anyMatch(lower::contains);
    }

    /** The {@code error} field as sent by the engine. */
    public String getServerMessage() {
        return serverMessage;
    }

    /** The JavaScript error name, e.g. {@code Error} or {@code TypeError}. */
    public String getExceptionType() {
        return exceptionType;
    }

    /** The Apps Script stack trace, one frame per entry. */
    public List<String> getServerStackTrace() {
        return serverStackTrace;
    }

    /** The engine's debug entries at the time of failure (may be {@code null}). */
    public JsonNode getDebug() {
        return debug;
    }
}
