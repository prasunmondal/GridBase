package io.github.prasunmondal.hibernatesheets;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs every HTTP call to the engine (retries included) on one line each, so they are easy to spot and
 * filter. Enable it with {@code logNetworkCalls()} on {@link SheetProperties.Builder} or
 * {@link HibernateSheets.Builder}:
 *
 * <pre>
 * GridBase >> 3f2a9c1e attempt 1 | 2 ops: SELECT Customers, INSERT Orders | spreadsheet 1C8rsAWa... | 412 chars | body={"requestId":...}
 * GridBase << 3f2a9c1e OK in 1840 ms | 5.2 KB
 * </pre>
 *
 * <p>Lines go to the {@code java.util.logging} logger {@value #LOGGER_NAME} at {@link Level#SEVERE} by default,
 * which shows <b>red</b>: Android maps it to {@code Log.e} (Logcat colors errors red; filter with
 * {@code tag:GridBaseNetwork}), and desktop consoles print it to stderr (red in IntelliJ / Android Studio).
 * Change the level with {@link #level}, send lines elsewhere with {@link #to}, or add ANSI red for plain
 * terminals with {@link #ansiColor}.</p>
 *
 * <p>Request bodies contain the data you read and write (never the access token, which is a header).
 * Bodies are cut to {@link #maxBodyChars} characters; responses are summarised, not printed, unless
 * {@link #maxResponseChars} is set. Immutable; each setter returns a new logger.</p>
 */
public final class NetworkLogger {

    /** Logger name, and on Android the Logcat tag. */
    public static final String LOGGER_NAME = "GridBaseNetwork";

    private static final Logger JUL = Logger.getLogger(LOGGER_NAME);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonFactory JSON_FACTORY = JSON.getFactory();
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final Level level;
    private final int maxBodyChars;
    private final int maxResponseChars;
    private final boolean ansiColor;
    private final Consumer<String> sink;

    private NetworkLogger(Level level, int maxBodyChars, int maxResponseChars, boolean ansiColor,
                          Consumer<String> sink) {
        this.level = level;
        this.maxBodyChars = maxBodyChars;
        this.maxResponseChars = maxResponseChars;
        this.ansiColor = ansiColor;
        this.sink = sink;
    }

    /** {@link Level#SEVERE} (red), bodies up to 2000 characters, responses summarised, no ANSI codes. */
    public static NetworkLogger defaults() {
        return new NetworkLogger(Level.SEVERE, 2000, 0, false, null);
    }

    /**
     * Level for the {@value #LOGGER_NAME} logger. {@link Level#OFF} turns logging off, also for a custom
     * {@link #to sink}; any other level only matters for {@code java.util.logging}.
     */
    public NetworkLogger level(Level newLevel) {
        return new NetworkLogger(Objects.requireNonNull(newLevel), maxBodyChars, maxResponseChars, ansiColor, sink);
    }

    /** Longest request body printed; 0 leaves the body out (the operation summary stays). */
    public NetworkLogger maxBodyChars(int chars) {
        return new NetworkLogger(level, nonNegative(chars), maxResponseChars, ansiColor, sink);
    }

    /** Longest response body printed; 0 (default) prints only status, time and size. */
    public NetworkLogger maxResponseChars(int chars) {
        return new NetworkLogger(level, maxBodyChars, nonNegative(chars), ansiColor, sink);
    }

    /**
     * Wraps each line in ANSI red. Useful in plain terminals; leave it off for Logcat and log files,
     * which show the escape codes literally.
     */
    public NetworkLogger ansiColor(boolean enabled) {
        return new NetworkLogger(level, maxBodyChars, maxResponseChars, enabled, sink);
    }

    /** Sends lines to {@code sink} instead of {@code java.util.logging}, e.g. {@code s -> Log.e("Sheets", s)}. */
    public NetworkLogger to(Consumer<String> newSink) {
        return new NetworkLogger(level, maxBodyChars, maxResponseChars, ansiColor, Objects.requireNonNull(newSink));
    }

    /** Pre-network-call action: one line describing the request about to be sent. */
    public void before(NetworkCall call) {
        if (!enabled()) {
            return;
        }
        String body = call.requestBody();
        StringBuilder line = new StringBuilder("GridBase >> ").append(shortId(call.requestId()))
                .append(" attempt ").append(call.attempt());
        summarise(body, line);
        line.append(" | ").append(body.length()).append(" chars");
        if (maxBodyChars > 0) {
            line.append(" | body=").append(cut(body, maxBodyChars));
        }
        emit(line.toString());
    }

    /** Post-network-call action: one line with the outcome, time taken and reply size. */
    public void after(NetworkCallResult result) {
        if (!enabled()) {
            return;
        }
        StringBuilder line = new StringBuilder("GridBase << ").append(shortId(result.call().requestId())).append(' ');
        long ms = result.elapsed().toMillis();
        if (!result.succeeded()) {
            RuntimeException f = result.failure();
            line.append("FAILED in ").append(ms).append(" ms: ").append(f.getClass().getSimpleName())
                    .append(": ").append(f.getMessage());
        } else {
            String reply = result.responseBody();
            String engineError = engineError(reply);
            line.append(engineError == null ? "OK" : "ENGINE ERROR").append(" in ").append(ms).append(" ms | ")
                    .append(size(reply));
            if (engineError != null && !engineError.isEmpty()) {
                line.append(": ").append(engineError);
            }
            if (maxResponseChars > 0) {
                line.append(" | response=").append(cut(reply, maxResponseChars));
            }
        }
        emit(line.toString());
    }

    private boolean enabled() {
        return level != Level.OFF && (sink != null || JUL.isLoggable(level));
    }

    private void emit(String line) {
        String text = ansiColor ? RED + line + RESET : line;
        if (sink != null) {
            sink.accept(text);
        } else {
            JUL.log(level, text);
        }
    }

    /** " | 2 ops: SELECT Customers, INSERT Orders | spreadsheet 1C8rsAWa..." from the request JSON. */
    private static void summarise(String body, StringBuilder line) {
        try {
            JsonNode ops = JSON.readTree(body).path("operations");
            if (!ops.isArray() || ops.size() == 0) {
                return;
            }
            Set<String> spreadsheets = new LinkedHashSet<>();
            line.append(" | ").append(ops.size()).append(ops.size() == 1 ? " op: " : " ops: ");
            int shown = 0;
            for (JsonNode op : ops) {
                if (shown == 8) {
                    line.append(", ...");
                    break;
                }
                line.append(shown++ == 0 ? "" : ", ").append(op.path("type").asText("?"))
                        .append(' ').append(op.path("worksheet").asText("?"));
                spreadsheets.add(op.path("spreadsheetId").asText(""));
            }
            for (String s : spreadsheets) {
                if (!s.isEmpty()) {
                    line.append(" | spreadsheet ").append(s.length() > 8 ? s.substring(0, 8) + "..." : s);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not the engine's JSON (custom transport, tests): log without the summary.
        }
    }

    /** The engine's error message when the reply says success:false; "" when it gives none; null on success. */
    private static String engineError(String reply) {
        if (reply == null) {
            return null;
        }
        Boolean success = null;
        String error = "";
        try (JsonParser p = JSON_FACTORY.createParser(reply)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.getCurrentName();
                JsonToken value = p.nextToken();
                if ("success".equals(field) && value.isBoolean()) {
                    success = p.getBooleanValue();
                } else if ("error".equals(field) && value == JsonToken.VALUE_STRING) {
                    error = p.getText();
                } else {
                    p.skipChildren();
                }
            }
        } catch (IOException e) {
            return null; // not JSON (e.g. an HTML page); the client reports that itself
        }
        return Boolean.FALSE.equals(success) ? error : null;
    }

    private static String shortId(String requestId) {
        return requestId != null && requestId.length() > 8 ? requestId.substring(0, 8) : String.valueOf(requestId);
    }

    private static String cut(String text, int max) {
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine
                : oneLine.substring(0, max) + "... (+" + (oneLine.length() - max) + " chars)";
    }

    private static String size(String text) {
        int n = text.length();
        return n < 1024 ? n + " chars" : String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
    }

    private static int nonNegative(int chars) {
        if (chars < 0) {
            throw new IllegalArgumentException("chars must be >= 0");
        }
        return chars;
    }
}
